package com.bol.app.service

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.bol.app.data.repository.ItemRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Fires when an alarm scheduled via [ReminderScheduler] triggers.
 *
 * We re-read the item from the database here (rather than passing all its fields
 * through the intent) so the notification reflects any edits made after the
 * alarm was scheduled. If the item has been deleted or already completed we
 * bail out silently — the alarm was just stale.
 */
class ReminderReceiver : BroadcastReceiver(), KoinComponent {

    private val itemRepository: ItemRepository by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val itemId = intent.getStringExtra(EXTRA_ITEM_ID) ?: return

        // Notifications permission is runtime-granted on Android 13+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "POST_NOTIFICATIONS not granted, suppressing reminder for $itemId")
            return
        }

        // goAsync() grants us up to 10s for DB I/O — more than enough to fetch + post.
        val pending = goAsync()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            try {
                val item = itemRepository.getById(itemId)
                if (item == null) {
                    Log.i(TAG, "Item $itemId no longer exists — skipping stale reminder")
                    return@launch
                }
                if (item.isCompleted) {
                    Log.i(TAG, "Item $itemId already completed — skipping stale reminder")
                    return@launch
                }

                val notification = NotificationHelper.buildReminderNotification(
                    context = context,
                    itemId = item.id,
                    itemText = item.text,
                    categoryId = item.categoryId,
                ).build()
                NotificationManagerCompat.from(context)
                    .notify(item.id.hashCode(), notification)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to post reminder notification", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "ReminderReceiver"
        const val EXTRA_ITEM_ID = "item_id"

        // Legacy extras kept for back-compat with any persisted intents; unused going forward.
        const val EXTRA_ITEM_TEXT = "item_text"
        const val EXTRA_CATEGORY_ICON = "category_icon"
    }
}
