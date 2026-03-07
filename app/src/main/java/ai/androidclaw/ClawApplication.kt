package ai.androidclaw

import android.app.Application
import android.util.Log
import ai.androidclaw.daemon.AgentDaemonService
import ai.androidclaw.daemon.DaemonWorkScheduler

class ClawApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        DaemonWorkScheduler.schedule(this)
        runCatching {
            AgentDaemonService.start(this)
        }.onFailure { e ->
            Log.w(TAG, "Failed to start daemon from Application", e)
        }
    }

    companion object {
        private const val TAG = "ClawApplication"

        lateinit var instance: ClawApplication
            private set
    }
}
