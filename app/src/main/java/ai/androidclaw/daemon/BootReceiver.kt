package ai.androidclaw.daemon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Log.d(TAG, "Received $action, starting daemon")
            AgentDaemonService.start(context.applicationContext)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
