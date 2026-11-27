package io.github.sebbejohansson.yggdrasil.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow

/**
 * A chat list that follows new text only while you are at its bottom, as a
 * terminal does: scrolled up to read, new lines don't pull you away, and
 * [FollowBottom.jump] (a "Latest" button) takes you back and follows again.
 */
class FollowBottom internal constructor(private val list: LazyListState) {
    /** Following: at the bottom when the list last settled. */
    var following by mutableStateOf(true)
        internal set

    /** Something new arrived while you were scrolled up. */
    var missed by mutableStateOf(false)
        internal set

    suspend fun jump() {
        following = true
        missed = false
        toEnd()
    }

    internal suspend fun toEnd() {
        val n = list.layoutInfo.totalItemsCount
        // A large offset lands on the end of the last item, however tall it is; the list clamps it.
        if (n > 0) list.scrollToItem(n - 1, END_OFFSET)
    }

    companion object {
        private const val END_OFFSET = 1_000_000

        /** Whether the list shows its very end (pure, for tests: the last item's index, its bottom edge, the viewport's). */
        fun atEnd(lastVisibleIndex: Int?, total: Int, lastItemBottom: Int, viewportEnd: Int): Boolean =
            lastVisibleIndex == null || (lastVisibleIndex >= total - 1 && lastItemBottom <= viewportEnd + 4)
    }
}

/**
 * Follows [list]'s bottom as [content] changes (a count, or the text of the
 * last entry while it streams). Whether you are at the bottom is taken from
 * the list while its length stays the same, so the moment new items make it
 * longer does not count as "scrolled up".
 */
@Composable
fun rememberFollowBottom(list: LazyListState, content: Any): FollowBottom {
    val follow = remember(list) { FollowBottom(list) }
    var lastTotal by remember(list) { mutableIntStateOf(-1) }
    LaunchedEffect(list) {
        snapshotFlow {
            val info = list.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            info.totalItemsCount to FollowBottom.atEnd(last?.index, info.totalItemsCount, last?.let { it.offset + it.size } ?: 0, info.viewportEndOffset)
        }.collect { (total, atEnd) ->
            when {
                // Same length: you moved, or it settled. That says whether to follow.
                total == lastTotal -> {
                    follow.following = atEnd
                    if (atEnd) follow.missed = false
                }
                // Longer (or the first layout): new entries.
                follow.following -> follow.toEnd()
                lastTotal >= 0 -> follow.missed = true
            }
            lastTotal = total
        }
    }
    // The last entry growing in place (same length, new text).
    LaunchedEffect(content) {
        if (follow.following) follow.toEnd() else if (lastTotal >= 0) follow.missed = true
    }
    return follow
}
