package moe.reimu.mua

import android.content.Context
import androidx.core.content.edit
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.await
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Fetch through Firebase rather than treating our cached token as authoritative. */
internal suspend fun refreshFcmToken(context: Context): String {
    val token = suspendCancellableCoroutine<String> { continuation ->
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            when {
                task.isCanceled -> continuation.resumeWithException(IllegalStateException("FCM token retrieval cancelled"))
                task.isSuccessful -> continuation.resume(task.result)
                else -> continuation.resumeWithException(task.exception ?: IllegalStateException("FCM token retrieval failed"))
            }
        }
    }
    context.pushPreferences().edit { putString("token", token) }
    return token
}

/** WorkManager persists this monthly job across process death and device reboots. */
class TokenRefreshWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (!applicationContext.pushPreferences().getBoolean("enabled", false)) return Result.success()
        return try {
            val config = applicationContext.loadRemoteConfig()
            // A configured central connection fetches and uploads in its serialized job.
            // Local-only users still refresh their token for external push senders.
            if (!config.enabled || config.validationError() != null) refreshFcmToken(applicationContext)
            // Uploads, including unchanged tokens, run through the same serialized queue
            // as enable/disable and configuration changes. Wait for durable enqueue;
            // the upload job then retains its own retries.
            PushRegistration.schedule(applicationContext)?.await()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
