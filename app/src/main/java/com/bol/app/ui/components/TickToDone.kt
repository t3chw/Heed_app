package com.bol.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Ticking a task in a list of open tasks (Home's Recent, the Recent page, a bucket):
 * 1. The box ticks and the title strikes through at once, and the task is saved as done right away (nothing is
 *    lost if the app closes).
 * 2. The row stays where it is for [GRACE_MS], so a mistaken tick is undone by tapping the box again: no
 *    message, the task is simply open again.
 * 3. Then the row fades out ([EXIT_MS]) and "Marked done · Undo" shows, for a tick the user didn't watch.
 *
 * [held] keeps the row as it was when ticked, so lists whose query drops done tasks can still draw it
 * ([withHeld]). Timers live in the screen: leaving it during the grace just skips the fade (the task is saved).
 */
@Stable
class TickToDone<R> internal constructor(
    private val scope: CoroutineScope,
    private val snackbar: SnackbarHostState,
    private val setDone: () -> (String, Boolean) -> Unit,
) {
    /** Ticked rows still on screen, by task id, with the place each had in the list when it was ticked. */
    var held by mutableStateOf<Map<String, Held<R>>>(emptyMap())
        private set

    /** A held row and its place. The place is kept, not re-sorted: tasks from one note share a timestamp, and
     *  sorting by time swapped a ticked task with its twin, so a second tap hit the wrong one. */
    data class Held<R>(val row: R, val index: Int)

    /** Held rows now fading out. */
    var leaving by mutableStateOf<Set<String>>(emptySet())
        private set

    private val timers = HashMap<String, Job>()

    fun isHeld(id: String): Boolean = id in held

    /** The box of an open (or held) task was tapped; [index] is its place in the list as shown. */
    fun tick(id: String, row: R, index: Int) {
        val timer = timers.remove(id)
        if (timer != null) {
            // Second tap during the grace: it was a mistake. Open again, no message.
            timer.cancel()
            held = held - id
            leaving = leaving - id
            setDone()(id, false)
            return
        }
        held = held + (id to Held(row, index))
        setDone()(id, true)
        timers[id] = scope.launch {
            delay(GRACE_MS)
            leaving = leaving + id
            delay(EXIT_MS.toLong())
            timers.remove(id)
            held = held - id
            leaving = leaving - id
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar("Marked done", actionLabel = "Undo", duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) setDone()(id, false)
        }
    }

    companion object {
        /** How long a ticked task stays in place before it leaves the list. */
        const val GRACE_MS = 2_500L

        /** The fade-out after the grace. */
        const val EXIT_MS = 250
    }
}

@Composable
fun <R> rememberTickToDone(
    snackbar: SnackbarHostState,
    setDone: (id: String, done: Boolean) -> Unit,
): TickToDone<R> {
    val scope = rememberCoroutineScope()
    val currentSetDone by rememberUpdatedState(setDone)
    return remember(snackbar) { TickToDone<R>(scope, snackbar) { currentSetDone } }
}

/**
 * The open rows of [live] with every held row put back at the place it had when ticked (a held task may or may
 * not still be in [live]: Home's query drops done tasks, a bucket's keeps them in its Done group). At most
 * [limit] rows, so a short list doesn't grow while a ticked row waits. [display] turns a held row into what to
 * draw (a bucket marks its snapshot done).
 */
fun <R> TickToDone<R>.withHeld(
    live: List<R>,
    id: (R) -> String,
    limit: Int = Int.MAX_VALUE,
    display: (R) -> R = { it },
): List<R> = placeHeld(live, held, id, limit, display)

/** [TickToDone.withHeld] without the state, for tests. */
internal fun <R> placeHeld(
    live: List<R>,
    held: Map<String, TickToDone.Held<R>>,
    id: (R) -> String,
    limit: Int = Int.MAX_VALUE,
    display: (R) -> R = { it },
): List<R> {
    if (held.isEmpty()) return live.take(limit)
    val result = live.filterTo(ArrayList()) { id(it) !in held }
    held.values.sortedBy { it.index }.forEach { h ->
        result.add(h.index.coerceIn(0, result.size), display(h.row))
    }
    return result.take(limit)
}

/** Wraps a row so it fades and folds away when its task leaves after the grace. */
@Composable
fun <R> TickToDone<R>.Leaving(id: String, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = id !in leaving,
        exit = fadeOut(tween(TickToDone.EXIT_MS)) + shrinkVertically(tween(TickToDone.EXIT_MS)),
    ) { content() }
}
