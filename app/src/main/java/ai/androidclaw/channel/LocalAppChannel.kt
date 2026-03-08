package ai.androidclaw.channel

import ai.androidclaw.agent.AgentManager
import ai.androidclaw.agent.core.AgentResponse
import ai.androidclaw.config.ConfigManager
import android.content.Context

class LocalAppChannel(context: Context) {

    private val appContext = context.applicationContext
    private val configManager = ConfigManager.getInstance(appContext)
    private val agentManager = AgentManager.getInstance(appContext)

    fun execute(
        text: String,
        imageDataUrl: String? = null,
        traceLogger: ((ai.androidclaw.agent.core.AgentTraceEvent) -> Unit)? = null,
        callback: (AgentResponse) -> Unit
    ) {
        agentManager.execute(
            message = text,
            imageDataUrl = imageDataUrl,
            systemPrompt = configManager.getAgentSystemPrompt(),
            maxIterations = configManager.getAgentMaxIterations(),
            traceLogger = traceLogger,
            callback = callback
        )
    }
}
