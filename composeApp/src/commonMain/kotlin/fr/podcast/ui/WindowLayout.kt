package fr.podcast.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Layout classes computed from the available space, not from the platform.
 * See the responsive table in CLAUDE.md.
 */
enum class WindowLayout {
    /** Width < 600 dp: bottom bar + mini player, one column. */
    Compact,

    /** Height < 480 dp (phone in landscape): rail, two columns, one-line mini player. */
    CompactHeight,

    /** 600–839 dp: rail, one wide column. */
    Medium,

    /** 840–1199 dp: rail, content + queue. */
    Expanded,

    /** ≥ 1200 dp: permanent drawer with subscriptions, content + side panel, player at the bottom. */
    Large;

    val usesRail: Boolean get() = this == CompactHeight || this == Medium || this == Expanded

    companion object {
        fun from(width: Dp, height: Dp): WindowLayout = when {
            width < 600.dp -> Compact
            height < 480.dp -> CompactHeight
            width < 840.dp -> Medium
            width < 1200.dp -> Expanded
            else -> Large
        }
    }
}
