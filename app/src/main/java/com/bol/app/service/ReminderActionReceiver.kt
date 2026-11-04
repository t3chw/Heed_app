package com.bol.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.bol.app.data.repository.ItemRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Handles button taps on reminder notifications. Two actions:
 *  - [ACTION_DONE]       — mark the item completed, cancel the scheduled alarm.
 *  - [ACTION_SNOOZE_1H]  — reschedule the alarm for +1 hour from now.
 *
 * Both dismiss the live notification afterwards. The notification body tap uses a
 * separate content intent that deep-links into MainActivity, not this receiver.
 */
class ReminderActionReceiver : BroadcastReceiver(), KoinComponent {

    private val itemRepository: ItemRepository by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val itemId = intent.getStringExtra(EXTRA_ITEM_ID) ?: return
        val action = intent.action ?: return

        val pending = goAsync()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            try {
                when (action) {
                    ACTION_DONE -> {
                        val item = itemRepository.getById(itemId)
                        if (item != null && !item.isCompleted) {
                            itemRepository.toggleCompleted(itemId)
                        }
                        ReminderScheduler.cancel(context, itemId)
                    }
                    ACTION_SNOOZE_1H -> {
                        // No early return on a missing item — the coroutine still
                        // needs to fall through to the notification-dismiss call
                        // below so the stale entry leaves the shade.
                        // A task completed in the app while its notification was still up gets no new reminder.
                        itemRepository.getById(itemId)?.takeIf { !it.isCompleted }?.let { item ->
                            val newTime = System.currentTimeMillis() + SNOOZE_INTERVAL_MS
                            itemRepository.update(
                                item.copy(
                                    reminderTime = newTime,
                                    updatedAt = System.currentTimeMillis(),
                                )
                            )
                            ReminderScheduler.schedule(context, itemId, newTime)
                        }
                    }
                }
                NotificationManagerCompat.from(context).cancel(itemId.hashCode())
            } catch (e: Exception) {
                Log.e(TAG, "Reminder action '$action' failed for $itemId", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "ReminderActionReceiver"
        const val ACTION_DONE = "com.bol.app.REMINDER_DONE"
        const val ACTION_SNOOZE_1H = "com.bol.app.REMINDER_SNOOZE_1H"
        const val EXTRA_ITEM_ID = "item_id"
        private const val SNOOZE_INTERVAL_MS = 60L * 60L * 1000L
    }
}
