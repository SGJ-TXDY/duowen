package cn.embeddedquiz

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * One place for the app's page chrome: which pages are first-level (tab bar visible), which are
 * focused sub-pages (own top bar, no tab bar), and where back leads from each of them.
 */
internal object PagePolicy {
    /** Opened from 更多 (the notes list also from a question): each has a top bar and no tab bar. */
    val subPages = setOf(Page.REVIEW, Page.NOTES, Page.WORKBENCH, Page.STATISTICS, Page.AI_DISCUSSIONS, Page.COSTS, Page.SETTINGS, Page.ERROR_LOG)

    fun isSubPage(page: Page) = page in subPages

    /**
     * 速记 overlays whatever page lies beneath it, so its own state decides first: the library is a
     * first-level entry, the reader is a focused page.
     */
    fun showsTabBar(page: Page, quickNotesVisible: Boolean, readerOpen: Boolean, answering: Boolean): Boolean =
        when {
            answering -> false
            quickNotesVisible -> !readerOpen
            else -> !isSubPage(page)
        }

    fun title(page: Page): String =
        when (page) {
            Page.NOTES -> "全局标签"
            Page.STATISTICS -> "学习统计与历史"
            else -> page.label
        }

    /**
     * Where back leads from [page] when the page has no handler of its own. The notes list returns
     * to its recorded source through the ViewModel, so it is not covered here.
     */
    fun parent(page: Page, costsFrom: Page): Page =
        when (page) {
            Page.HOME,
            Page.QUESTION,
            Page.MISTAKES,
            Page.MORE,
            Page.EXAM,
            // Opened from the home screen's practice modes, so back returns there.
            Page.BROWSE,
            Page.PROGRAMMING -> Page.HOME
            Page.ERROR_LOG -> Page.SETTINGS
            Page.COSTS -> if (costsFrom == Page.SETTINGS) Page.SETTINGS else Page.MORE
            Page.REVIEW,
            Page.WORKBENCH,
            Page.STATISTICS,
            Page.AI_DISCUSSIONS,
            Page.SETTINGS,
            Page.NOTES -> Page.MORE
        }
}

/**
 * Remembers where 费用 was opened from (更多 or 设置) without touching the ViewModel. Plain fields:
 * they are only read by the back handler, never drawn.
 */
internal class PageTrail(var last: Page, var costsFrom: Page = Page.MORE) {
    fun observe(page: Page) {
        if (page == last) return
        if (page == Page.COSTS) costsFrom = last
        last = page
    }

    companion object {
        val saver =
            Saver<PageTrail, String>(
                save = { "${it.last.name},${it.costsFrom.name}" },
                restore = {
                    val (last, from) = it.split(',')
                    PageTrail(Page.valueOf(last), Page.valueOf(from))
                },
            )
    }
}

/**
 * Top bar of a sub-page. Its back arrow goes through the same dispatcher as the system back
 * gesture, so a page's inner levels (an open editor, a dialog) close first either way.
 */
@Composable
internal fun SubPageTopBar(title: String) {
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 4.dp, end = 16.dp).testTag("sub-page-bar"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { dispatcher?.onBackPressed() }, modifier = Modifier.size(48.dp)) {
            Icon(AppIcons.Back, contentDescription = "返回")
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 4.dp).semantics { heading() },
        )
    }
}
