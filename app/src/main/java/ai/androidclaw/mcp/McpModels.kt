package ai.androidclaw.mcp

import java.util.UUID

enum class McpTransport(val displayName: String) {
    STDIO("stdio"),
    SSE("sse"),
    STREAMABLE_HTTP("streamable_http")
}

data class McpServerConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val transport: McpTransport = McpTransport.STDIO,
    val enabled: Boolean = true,
    val command: String = "",
    val args: String = "",
    val envJson: String = "",
    val endpoint: String = "",
    val headersJson: String = "",
    val timeoutMs: Int = 30_000,
    val description: String = ""
)
