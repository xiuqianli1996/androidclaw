package ai.androidclaw.mcp

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class McpConfigManager private constructor(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun getAllServers(): List<McpServerConfig> {
        val json = prefs.getString(KEY_SERVERS, "[]") ?: "[]"
        return runCatching {
            gson.fromJson<List<McpServerConfig>>(json, object : TypeToken<List<McpServerConfig>>() {}.type)
        }.getOrDefault(emptyList())
    }

    fun getEnabledServers(): List<McpServerConfig> = getAllServers().filter { it.enabled }

    fun upsert(server: McpServerConfig) {
        val all = getAllServers().toMutableList()
        val index = all.indexOfFirst { it.id == server.id }
        if (index >= 0) {
            all[index] = server
        } else {
            all.add(server)
        }
        saveAllServers(all)
    }

    fun setEnabled(serverId: String, enabled: Boolean) {
        val all = getAllServers().map {
            if (it.id == serverId) it.copy(enabled = enabled) else it
        }
        saveAllServers(all)
    }

    fun delete(serverId: String) {
        saveAllServers(getAllServers().filterNot { it.id == serverId })
    }

    private fun saveAllServers(servers: List<McpServerConfig>) {
        prefs.edit().putString(KEY_SERVERS, gson.toJson(servers)).apply()
    }

    companion object {
        private const val PREFS_NAME = "android_claw_mcp"
        private const val KEY_SERVERS = "mcp_servers"

        @Volatile
        private var instance: McpConfigManager? = null

        fun getInstance(context: Context): McpConfigManager {
            return instance ?: synchronized(this) {
                instance ?: McpConfigManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
