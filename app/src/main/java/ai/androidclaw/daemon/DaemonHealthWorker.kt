package ai.androidclaw.daemon

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class DaemonHealthWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            if (!AgentDaemonService.isRunning) {
                AgentDaemonService.start(applicationContext)
            }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
