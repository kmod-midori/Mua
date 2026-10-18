package moe.reimu.displaycontrol

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import moe.reimu.nekoassistant.IUserService

/** System-owned notification snapshots; no persistent notification cache. */
class AgentNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() = synchronized(Companion) {
        if (sessionActive) instance = this else requestUnbind()
    }

    override fun onListenerDisconnected() = synchronized(Companion) {
        if (instance === this) instance = null
        if (sessionActive) requestRebind(ComponentName(this, javaClass))
    }

    override fun onDestroy() {
        synchronized(Companion) { if (instance === this) instance = null }
        super.onDestroy()
    }

    companion object {
        @Volatile private var sessionOwner: Any? = null
        private val sessionActive get() = sessionOwner != null
        @Volatile private var instance: AgentNotificationListener? = null

        @Synchronized
        internal fun setActive(context: Context, owner: Any, active: Boolean) {
            if (!active && sessionOwner != null && sessionOwner !== owner) return
            sessionOwner = if (active) owner else null
            if (!active) {
                val old = instance
                instance = null
                runCatching { old?.requestUnbind() }
            }
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, AgentNotificationListener::class.java),
                if (active) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }

        private fun listener(): AgentNotificationListener {
            check(sessionActive) { "Notification access is inactive" }
            return checkNotNull(instance) { "Notification listener is connecting; retry shortly" }
        }

        private fun find(key: String): Pair<AgentNotificationListener, StatusBarNotification> {
            require(key.isNotBlank()) { "Notification key is required" }
            val service = listener()
            val notification = service.getActiveNotifications(arrayOf(key))?.firstOrNull { it.key == key }
            return service to checkNotNull(notification) { "Notification no longer exists; refresh notifications_list" }
        }

        fun list(): List<NotificationInfo> = listener().activeNotifications.orEmpty().map { sbn ->
            val n = sbn.notification
            NotificationInfo(
                key = sbn.key, `package` = sbn.packageName, id = sbn.id, tag = sbn.tag,
                userId = sbn.user.hashCode(), postTime = sbn.postTime, groupKey = sbn.groupKey,
                ongoing = sbn.isOngoing, clearable = sbn.isClearable, category = n.category,
                title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
                text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
                bigText = n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
                textLines = n.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map { it.toString() }.orEmpty(),
                contentIntent = n.contentIntent?.let(::intentInfo),
                actions = n.actions.orEmpty().mapIndexed { index, action ->
                    NotificationActionInfo(index, action.title?.toString().orEmpty(), action.actionIntent?.let(::intentInfo),
                        action.remoteInputs.orEmpty().map { input ->
                            NotificationInputInfo(input.resultKey, input.label?.toString().orEmpty(),
                                input.allowFreeFormInput, input.choices?.map { it.toString() }.orEmpty())
                        })
                },
            )
        }

        private fun intentInfo(intent: PendingIntent) =
            NotificationIntentInfo(intent.isActivity, intent.creatorPackage, intent.isImmutable)

        fun clear(key: String): String {
            val (service, _) = find(key)
            service.cancelNotification(key)
            return "Dismissal requested; Android may retain ongoing or protected notifications"
        }

        fun click(key: String, displayId: Int, remote: IUserService): String {
            val (_, sbn) = find(key)
            val intent = checkNotNull(sbn.notification.contentIntent) { "Notification has no content intent" }
            remote.sendNotificationIntent(intent, displayId, null)
            return result(intent, displayId)
        }

        fun action(key: String, index: Int, displayId: Int, remote: IUserService,
                   resultKey: String? = null, text: String? = null): String {
            require(index >= 0) { "Action index must be nonnegative" }
            val (_, sbn) = find(key)
            val action = sbn.notification.actions?.getOrNull(index) ?: error("Action no longer exists; refresh notifications_list")
            val intent = checkNotNull(action.actionIntent) { "Action has no pending intent" }
            val fillIn = if (resultKey != null) {
                val input = action.remoteInputs?.firstOrNull { it.resultKey == resultKey }
                    ?: error("Unknown remote input resultKey")
                check(!intent.isImmutable) { "Action does not accept remote input" }
                Intent().also { reply ->
                    RemoteInput.addResultsToIntent(arrayOf(input), reply, Bundle().apply { putCharSequence(resultKey, text) })
                    RemoteInput.setResultsSource(reply, RemoteInput.SOURCE_FREE_FORM_INPUT)
                }
            } else null
            remote.sendNotificationIntent(intent, displayId, fillIn)
            return result(intent, displayId)
        }

        private fun result(intent: PendingIntent, displayId: Int): String = if (intent.isActivity)
            "Activity launch requested on display $displayId; Android and app launch modes determine final placement"
        else "Action sent; displayId does not apply to broadcast or service pending intents"
    }
}
