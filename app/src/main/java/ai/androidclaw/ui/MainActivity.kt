package ai.androidclaw.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import ai.androidclaw.R
import ai.androidclaw.agent.AgentManager
import ai.androidclaw.agent.core.AgentResponse
import ai.androidclaw.config.ConfigManager
import ai.androidclaw.db.AppDatabase
import ai.androidclaw.db.entity.Conversation
import ai.androidclaw.db.entity.Message
import ai.androidclaw.db.entity.MessageRole
import ai.androidclaw.databinding.ActivityMainBinding
import ai.androidclaw.service.ClawAccessibilityService
import ai.androidclaw.ui.adapter.ChatAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var configManager: ConfigManager
    private lateinit var agentManager: AgentManager
    private lateinit var database: AppDatabase
    private lateinit var chatAdapter: ChatAdapter

    private var currentConversationId: Long = 0
    private var isProcessing = false

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
        database = AppDatabase.getInstance(this)

        setupToolbar()
        setupRecyclerView()
        setupInput()
        initConversation()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
    }

    private fun setupRecyclerView() {
        chatAdapter = ChatAdapter { message ->
            // Handle long click - copy text or delete
            Toast.makeText(this, "长按消息", Toast.LENGTH_SHORT).show()
        }

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
    }

    private fun initConversation() {
        if (!configManager.isConfigured()) {
            Toast.makeText(this, "请先配置模型API", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }

        val config = configManager.getModelConfig()
        if (!agentManager.isReady()) {
            agentManager.initialize(
                apiKey = config.apiKey,
                modelName = config.modelName,
                baseUrl = config.baseUrl.ifBlank { null }
            )
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
        val content = getString(R.string.image_selected)
        sendMessageToAgent(content, MessageRole.USER.value, "image", uri.toString())
    }

    private fun sendMessageToAgent(
        content: String,
        role: String,
        messageType: String,
        mediaUrl: String? = null
    ) {
        isProcessing = true
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
            agentManager.execute(content) { response ->
                handleAgentResponse(response)
            }
        }
    }

    private fun handleAgentResponse(response: AgentResponse) {
        lifecycleScope.launch(Dispatchers.Main) {
            binding.progressBar.visibility = android.view.View.GONE
            isProcessing = false

            // Save AI response
            val aiMessage = Message(
                conversation_id = currentConversationId,
                role = MessageRole.ASSISTANT.value,
                content = response.message,
                message_type = "text",
                status = if (response.error == null) "sent" else "failed",
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
            R.id.action_clear -> {
                clearConversation()
                true
            }
            R.id.action_skills -> {
                startActivity(Intent(this, SkillsActivity::class.java))
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
}
