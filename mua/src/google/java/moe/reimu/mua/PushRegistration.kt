package moe.reimu.mua

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal object PushRegistration {
    private const val REFRESH_WORK = "mua-fcm-token-refresh"

    private fun scheduleRefresh(context: Context) {
        val manager = WorkManager.getInstance(context)
        if (!context.pushPreferences().getBoolean("enabled", false)) {
            manager.cancelUniqueWork(REFRESH_WORK)
            return
        }
        val refresh = PeriodicWorkRequestBuilder<TokenRefreshWorker>(30, TimeUnit.DAYS)
            .setInitialDelay(30, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // KEEP preserves the original deadline when the UI, service or callback runs.
        manager.enqueueUniquePeriodicWork(REFRESH_WORK, ExistingPeriodicWorkPolicy.KEEP, refresh)
    }

    fun schedule(context: Context, config: RemoteConfig = context.loadRemoteConfig()): Operation? {
        scheduleRefresh(context)
        // Disabled configs can still need removal; validate their saved endpoint explicitly.
        val target = config.copy(enabled = true)
        if (target.validationError() != null) return null
        val endpoint = target.pushEndpoint()
        val key = MessageDigest.getInstance("SHA-256").digest(endpoint.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val work = OneTimeWorkRequestBuilder<PushRegistrationWorker>()
            .setInputData(workDataOf("url" to endpoint, "credential" to target.token, "name" to target.deviceName))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // Serialize writes to each identity. Workers read the latest token/opt-in state,
        // so older queued work cannot re-register a token after a newer disable/rotation.
        return WorkManager.getInstance(context).enqueueUniqueWork("mua-push-$key", ExistingWorkPolicy.APPEND_OR_REPLACE, work)
    }
}

class PushRegistrationWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val endpoint = inputData.getString("url") ?: return Result.failure()
        val savedCredential = inputData.getString("credential") ?: return Result.failure()
        val savedName = inputData.getString("name").orEmpty()
        fun currentState(): PushRegistrationState {
            val current = applicationContext.loadRemoteConfig()
            val isCurrent = current.copy(enabled = true).validationError() == null &&
                current.pushEndpoint() == endpoint
            return PushRegistrationState(
                credential = if (isCurrent) current.token else savedCredential,
                name = if (isCurrent) current.deviceName else savedName,
                enabled = isCurrent && current.enabled &&
                    applicationContext.pushPreferences().getBoolean("enabled", false),
            )
        }
        return try {
            val status = syncPushRegistration(
                state = ::currentState,
                refreshToken = { refreshFcmToken(applicationContext) },
                upload = { state, token ->
                    PushRegistrationClient.register(endpoint, state.credential, state.name, token)
                },
            ) ?: return Result.retry()
            when {
                status in 200..299 -> Result.success()
                status == 408 || status == 429 || status >= 500 -> Result.retry()
                else -> {
                    // A changed configuration schedules new work. Do not poison its chain
                    // with a terminal failure, or expose credentials/response bodies in logs.
                    android.util.Log.w("MuaFcm", "Registration rejected (HTTP $status)")
                    Result.success()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
