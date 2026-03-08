package ai.androidclaw.ui

import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import ai.androidclaw.R
import ai.androidclaw.agent.AgentManager
import ai.androidclaw.agent.core.AgentTraceEvent
import ai.androidclaw.agent.core.AgentTraceType
import ai.androidclaw.agent.core.AgentResponse
import ai.androidclaw.channel.LocalAppChannel
import ai.androidclaw.config.ConfigManager
import ai.androidclaw.db.AppDatabase
import ai.androidclaw.db.entity.Conversation
import ai.androidclaw.db.entity.Message
import ai.androidclaw.db.entity.MessageRole
import ai.androidclaw.databinding.ActivityMainBinding
import ai.androidclaw.agent.logging.AgentLogManager
import ai.androidclaw.service.ClawAccessibilityService
import ai.androidclaw.ui.adapter.ChatAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import kotlin.math.max
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var configManager: ConfigManager
    private lateinit var agentManager: AgentManager
    private lateinit var localChannel: LocalAppChannel
    private lateinit var database: AppDatabase
    private lateinit var logManager: AgentLogManager
    private lateinit var chatAdapter: ChatAdapter

    private var currentConversationId: Long = 0
    private var isProcessing = false
    private var isPaused = false
    private var currentTaskStartedAt: Long = 0
    private var currentTraceMessageId: Long? = null
    private val currentTraceBuffer = StringBuilder()
    private val traceMutex = Mutex()
    private var currentTraceStatus: String = "待命"
    private var currentTraceStep: Int = 0
    private var currentTraceTotalSteps: Int = 0

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handleImageSelected(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!PermissionChecker.hasAllRequiredPermissions(this)) {
            startActivity(Intent(this, PermissionGuideActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configManager = ConfigManager.getInstance(this)
        agentManager = AgentManager.getInstance(this)
        localChannel = LocalAppChannel(this)
        database = AppDatabase.getInstance(this)
        logManager = AgentLogManager.getInstance(this)

        setupToolbar()
        setupRecyclerView()
        setupInput()
        initConversation()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
    }

    private fun setupRecyclerView() {
        chatAdapter = ChatAdapter(
            onMessageLongClick = { message ->
                val text = message.content
                if (text.isNotBlank()) {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("message", text))
                    Toast.makeText(this, "已复制消息内容", Toast.LENGTH_SHORT).show()
                }
            },
            onImageClick = { message ->
                openImageViewer(message)
            }
        )

        binding.rvMessages.apply {
            layoutManager = LinearLayoutManager(this@MainActivity).apply {
                stackFromEnd = true
            }
            adapter = chatAdapter
        }
    }

    private fun setupInput() {
        binding.btnSend.setOnClickListener {
            sendMessage()
        }

        binding.btnAttach.setOnClickListener {
            imagePickerLauncher.launch("image/*")
        }

        binding.etMessage.setOnEditorActionListener { _, _, _ ->
            sendMessage()
            true
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnPause.setOnClickListener {
            togglePauseResume()
        }

        updatePauseButtonUi()
    }

    private fun initConversation() {
        if (!configManager.isConfigured()) {
            Toast.makeText(this, "请先配置模型API", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }

        val config = configManager.getModelConfig()
        if (!agentManager.isReady()) {
            agentManager.initialize(config)
        }

        lifecycleScope.launch {
            // Create or get existing conversation
            val conversation = withContext(Dispatchers.IO) {
                database.conversationDao().getConversationByChannel("agent", "default")
                    ?: database.conversationDao().insert(
                        Conversation(
                            title = "AI Assistant",
                            channel_type = "agent",
                            channel_id = "default"
                        )
                    ).let { id ->
                        database.conversationDao().getConversationById(id)
                    }
            }

            conversation?.let {
                currentConversationId = it.id
                loadMessages()
            }
        }
    }

    private fun loadMessages() {
        lifecycleScope.launch {
            database.messageDao().getMessagesByConversation(currentConversationId)
                .collectLatest { messages ->
                    chatAdapter.submitList(messages) {
                        if (chatAdapter.itemCount > 0) {
                            binding.rvMessages.scrollToPosition(chatAdapter.itemCount - 1)
                        }
                    }
                }
        }
    }

    private fun sendMessage() {
        val content = binding.etMessage.text?.toString()?.trim() ?: return
        if (content.isEmpty() || isProcessing) return

        binding.etMessage.text?.clear()
        sendMessageToAgent(content, MessageRole.USER.value, "text")
    }

    private fun handleImageSelected(uri: Uri) {
        val persistedUri = persistImageToAppStorage(uri)
        val content = getString(R.string.image_selected)
        sendMessageToAgent(content, MessageRole.USER.value, "image", persistedUri)
    }

    private fun persistImageToAppStorage(uri: Uri): String? {
        return runCatching {
            val inStream = contentResolver.openInputStream(uri) ?: return null
            inStream.use { input ->
                val mime = contentResolver.getType(uri) ?: "image/jpeg"
                val ext = mime.substringAfterLast('/').ifBlank { "jpg" }
                val dir = File(filesDir, "chat_images").apply { mkdirs() }
                val file = File(dir, "img_${System.currentTimeMillis()}.$ext")
                file.outputStream().use { output -> input.copyTo(output) }
                Uri.fromFile(file).toString()
            }
        }.getOrNull()
    }

    private fun sendMessageToAgent(
        content: String,
        role: String,
        messageType: String,
        mediaUrl: String? = null
    ) {
        isProcessing = true
        currentTaskStartedAt = System.currentTimeMillis()
        currentTraceMessageId = null
        currentTraceBuffer.clear()
        currentTraceStatus = "运行中"
        currentTraceStep = 0
        currentTraceTotalSteps = 0
        isPaused = false
        agentManager.resumeExecution()
        updatePauseButtonUi()
        binding.progressBar.visibility = android.view.View.VISIBLE

        lifecycleScope.launch {
            // Save user message
            val userMessage = Message(
                conversation_id = currentConversationId,
                role = role,
                content = content,
                message_type = messageType,
                media_url = mediaUrl,
                status = "sent"
            )

            withContext(Dispatchers.IO) {
                database.messageDao().insert(userMessage)
                database.conversationDao().updateTimestamp(currentConversationId)
            }

            // Check accessibility service
            if (ClawAccessibilityService.instance == null) {
                Toast.makeText(
                    this@MainActivity,
                    "无障碍服务未启动，部分功能可能不可用",
                    Toast.LENGTH_LONG
                ).show()
            }

            // Send to agent
            val imageDataUrl = if (messageType == "image" && mediaUrl != null) {
                encodeImageAsDataUrl(Uri.parse(mediaUrl))
            } else {
                null
            }

            logManager.append(currentConversationId, "USER_MESSAGE", content)

            localChannel.execute(
                text = content,
                imageDataUrl = imageDataUrl,
                traceLogger = { event -> appendTrace(event) }
            ) { response ->
                handleAgentResponse(response)
            }
        }
    }

    private fun appendTrace(event: AgentTraceEvent) {
        logManager.append(
            currentConversationId,
            "${event.type.name}@step${event.iteration}",
            event.content
        )

        if (event.type == AgentTraceType.LLM_RESPONSE || event.type == AgentTraceType.TOOL_CALL || event.type == AgentTraceType.TOOL_RESULT) {
            lifecycleScope.launch(Dispatchers.IO) {
                val preview = event.content.replace("\n", " ").replace("\\s+".toRegex(), " ").take(1200)
                val line = when (event.type) {
                    AgentTraceType.LLM_RESPONSE -> "[决策 ${event.iteration}] $preview"
                    AgentTraceType.TOOL_CALL -> "[工具 ${event.iteration}] $preview"
                    AgentTraceType.TOOL_RESULT -> "[结果 ${event.iteration}] $preview"
                    else -> preview
                }

                if (event.iteration > currentTraceStep) {
                    currentTraceStep = event.iteration
                }
                if (event.type == AgentTraceType.LLM_RESPONSE) {
                    val detectedTotal = detectPlannedStepCount(event.content)
                    if (detectedTotal > currentTraceTotalSteps) {
                        currentTraceTotalSteps = detectedTotal
                    }
                }

                traceMutex.withLock {
                    if (currentTraceBuffer.isNotEmpty()) currentTraceBuffer.append('\n')
                    currentTraceBuffer.append(line)

                    val existingId = currentTraceMessageId
                    if (existingId == null) {
                        val msg = Message(
                            conversation_id = currentConversationId,
                            role = MessageRole.SYSTEM.value,
                            content = buildTraceMessageContent(),
                            message_type = "text",
                            status = "sent"
                        )
                        val insertedId = database.messageDao().insert(msg)
                        currentTraceMessageId = insertedId
                    } else {
                        database.messageDao().updateContent(existingId, buildTraceMessageContent())
                    }
                }
                database.conversationDao().updateTimestamp(currentConversationId)
            }
        }
    }

    private fun encodeImageAsDataUrl(uri: Uri): String? {
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            }

            val sample = calculateInSampleSize(bounds.outWidth, bounds.outHeight, 1280, 1280)
            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, decodeOptions)
            } ?: return@runCatching null

            val normalized = resizeIfNeeded(bitmap, 1280)
            val compressed = compressBitmapForModel(normalized)

            if (normalized !== bitmap) normalized.recycle()
            bitmap.recycle()

            if (compressed.isEmpty()) return@runCatching null
            val base64 = android.util.Base64.encodeToString(compressed, android.util.Base64.NO_WRAP)
            "data:image/jpeg;base64,$base64"
        }.getOrNull()
    }

    private fun calculateInSampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            var halfHeight = height / 2
            var halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return max(1, inSampleSize)
    }

    private fun resizeIfNeeded(bitmap: Bitmap, maxSide: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val maxCurrent = max(w, h)
        if (maxCurrent <= maxSide) return bitmap
        val scale = maxSide.toFloat() / maxCurrent.toFloat()
        val targetW = (w * scale).toInt().coerceAtLeast(1)
        val targetH = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
    }

    private fun compressBitmapForModel(bitmap: Bitmap): ByteArray {
        var quality = 82
        var result = ByteArray(0)
        repeat(5) {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            result = out.toByteArray()
            if (result.size <= 350 * 1024) return result
            quality -= 10
        }
        return result
    }

    private fun handleAgentResponse(response: AgentResponse) {
        lifecycleScope.launch(Dispatchers.Main) {
            binding.progressBar.visibility = android.view.View.GONE
            isProcessing = false
            isPaused = false
            currentTraceStatus = if (response.error == null && response.done) "已完成" else "失败"
            updateTraceStatus(currentTraceStatus)
            updatePauseButtonUi()

            // Save AI response
            val aiMessage = Message(
                conversation_id = currentConversationId,
                role = MessageRole.ASSISTANT.value,
                content = response.message,
                message_type = "text",
                status = if (response.error == null) "sent" else "failed",
                metadata = buildAgentMetadata(response),
                tool_calls = if (response.toolCalls.isNotEmpty()) {
                    com.google.gson.Gson().toJson(response.toolCalls)
                } else null
            )

            withContext(Dispatchers.IO) {
                database.messageDao().insert(aiMessage)
                database.conversationDao().updateTimestamp(currentConversationId)
            }

            if (response.error != null) {
                Toast.makeText(this@MainActivity, "Error: ${response.error}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_tools -> {
                showToolsDialog()
                true
            }
            R.id.action_mcp -> {
                startActivity(Intent(this, McpSettingsActivity::class.java))
                true
            }
            R.id.action_clear -> {
                clearConversation()
                true
            }
            R.id.action_skills -> {
                startActivity(Intent(this, SkillsActivity::class.java))
                true
            }
            R.id.action_logs -> {
                startActivity(Intent(this, AgentLogListActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun clearConversation() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                database.messageDao().deleteAllByConversation(currentConversationId)
            }
            Toast.makeText(this@MainActivity, "对话已清空", Toast.LENGTH_SHORT).show()
        }
    }

    private fun togglePauseResume() {
        if (!isProcessing) {
            Toast.makeText(this, "当前没有运行中的任务", Toast.LENGTH_SHORT).show()
            return
        }
        isPaused = !isPaused
        if (isPaused) {
            agentManager.pauseExecution()
            currentTraceStatus = "已暂停"
            updateTraceStatus(currentTraceStatus)
            Toast.makeText(this, "任务已暂停", Toast.LENGTH_SHORT).show()
        } else {
            agentManager.resumeExecution()
            currentTraceStatus = "运行中"
            updateTraceStatus(currentTraceStatus)
            Toast.makeText(this, "任务已恢复", Toast.LENGTH_SHORT).show()
        }
        updatePauseButtonUi()
    }

    private fun buildTraceMessageContent(): String {
        return buildString {
            appendLine("[执行过程]")
            appendLine("状态: $currentTraceStatus")
            if (currentTraceStep > 0) {
                if (currentTraceTotalSteps > 0) {
                    appendLine("进度: step ${currentTraceStep}/${currentTraceTotalSteps}")
                } else {
                    appendLine("进度: step ${currentTraceStep}")
                }
            }
            if (currentTraceBuffer.isNotEmpty()) {
                append(currentTraceBuffer.toString())
            }
        }.trim()
    }

    private fun detectPlannedStepCount(content: String): Int {
        val normalized = content
            .replace("\r\n", "\n")
            .replace("\r", "\n")

        val lineMatches = normalized.lines().mapNotNull { line ->
            val m = Regex("^\\s*(\\d+)[.)、:]\\s+").find(line.trim())
            m?.groupValues?.getOrNull(1)?.toIntOrNull()
        }
        if (lineMatches.isNotEmpty()) {
            return lineMatches.maxOrNull()?.coerceIn(1, 20) ?: 0
        }

        val inlineMatches = Regex("(第\\s*(\\d+)\\s*步)").findAll(normalized)
            .mapNotNull { it.groupValues.getOrNull(2)?.toIntOrNull() }
            .toList()
        if (inlineMatches.isNotEmpty()) {
            return inlineMatches.maxOrNull()?.coerceIn(1, 20) ?: 0
        }

        return 0
    }

    private fun updateTraceStatus(status: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            traceMutex.withLock {
                currentTraceStatus = status
                val id = currentTraceMessageId ?: return@withLock
                database.messageDao().updateContent(id, buildTraceMessageContent())
                database.conversationDao().updateTimestamp(currentConversationId)
            }
        }
    }

    private fun updatePauseButtonUi() {
        binding.btnPause.setImageResource(if (isPaused) R.drawable.ic_play else R.drawable.ic_pause)
        binding.btnPause.contentDescription = if (isPaused) "恢复" else "暂停"
        binding.btnPause.alpha = if (isProcessing) 1f else 0.5f
    }

    private fun buildAgentMetadata(response: AgentResponse): String {
        val meta = mutableMapOf<String, Any>()
        meta["elapsedMs"] = if (response.elapsedMs > 0) response.elapsedMs else (System.currentTimeMillis() - currentTaskStartedAt)
        meta["iterations"] = response.iterations
        response.inputTokens?.let { meta["inputTokens"] = it }
        response.outputTokens?.let { meta["outputTokens"] = it }
        response.totalTokens?.let { meta["totalTokens"] = it }
        response.finishReason?.let { meta["finishReason"] = it }
        return com.google.gson.Gson().toJson(meta)
    }

    private fun openImageViewer(message: Message) {
        val uri = message.media_url ?: return
        val intent = Intent(this, ImageViewerActivity::class.java)
        intent.putExtra("uri", uri)
        startActivity(intent)
    }

    private fun showToolsDialog() {
        if (!agentManager.isReady()) {
            Toast.makeText(this, "Agent 尚未初始化", Toast.LENGTH_SHORT).show()
            return
        }

        val specs = agentManager.getToolSpecifications().sortedBy { it.name() }
        val message = if (specs.isEmpty()) {
            "暂无可用工具"
        } else {
            specs.joinToString("\n\n") { "${it.name()}\n${it.description()}" }
        }

        AlertDialog.Builder(this)
            .setTitle("内置工具列表")
            .setMessage(message)
            .setPositiveButton("关闭", null)
            .show()
    }
}
