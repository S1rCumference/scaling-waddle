package com.recorder.app.ui

import android.content.Context
import com.recorder.core.storage.Clocks
import com.recorder.core.storage.DayKey
import com.recorder.core.storage.SegmentCorrection
import com.recorder.core.storage.TranscriptSegment
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

enum class AppTab(val label: String) { LIVE("Live"), LOGS("Logs"), FLAGS("Flags"), SETTINGS("Settings") }

enum class TextMode(val label: String) {
    /** What was heard. The default: it is the only version that is certainly what was said. */
    ORIGINAL("Original"),
    CORRECTED("Corrected"),
    BOTH("Both"),
}

enum class GroupKind { HOUR, DAY, MONTH, RANGE, ALL }

/** A stretch of transcript the Logs tab can open: an hour, a day, a range, or everything. */
data class GroupRef(val kind: GroupKind, val fromTs: Long, val toTs: Long) {

    /** Stable key for this group's conversation and scroll position. */
    val id: String get() = "$kind:$fromTs:$toTs"

    fun title(now: Long = System.currentTimeMillis()): String = when (kind) {
        GroupKind.ALL -> "Everything"
        GroupKind.HOUR -> {
            val day = if (DayKey.of(fromTs) == DayKey.of(now)) "Today" else Clocks.date(fromTs)
            "$day ${Clocks.shortTime(fromTs)}–${Clocks.shortTime(toTs)}"
        }
        GroupKind.DAY -> when (DayKey.of(fromTs)) {
            DayKey.of(now) -> "Today"
            DayKey.previous(DayKey.of(now)) -> "Yesterday · ${Clocks.date(fromTs)}"
            else -> Clocks.date(fromTs)
        }
        GroupKind.MONTH -> Clocks.monthAndYear(fromTs)
        GroupKind.RANGE -> "${Clocks.date(fromTs)} – ${Clocks.date(toTs - 1)}"
    }

    companion object {
        val ALL = GroupRef(GroupKind.ALL, 0, Long.MAX_VALUE)

        fun hour(anyTs: Long): GroupRef {
            val start = DayKey.hourStart(anyTs)
            return GroupRef(GroupKind.HOUR, start, start + 3_600_000L)
        }

        fun day(dayKey: Int): GroupRef = GroupRef(GroupKind.DAY, DayKey.startOf(dayKey), DayKey.endOf(dayKey))

        /** [monthKey] is yyyymm, the key the Logs calendar already groups days by. */
        fun month(monthKey: Int): GroupRef =
            GroupRef(GroupKind.MONTH, DayKey.monthStart(monthKey), DayKey.monthEnd(monthKey))

        fun parse(id: String): GroupRef? = runCatching {
            val (kind, from, to) = id.split(':')
            GroupRef(GroupKind.valueOf(kind), from.toLong(), to.toLong())
        }.getOrNull()
    }
}

/** One transcript line with its newest correction, if any pass has produced one. */
data class LineView(val segment: TranscriptSegment, val correction: SegmentCorrection?) {
    val original: String get() = segment.text
    val corrected: String get() = correction?.text ?: segment.text
    val changed: Boolean get() = correction != null && !correction.unchangedFrom(segment.text)
}

data class ScrollPos(val index: Int, val offset: Int)

/**
 * The one copy of what the screen is showing, shared by both screens.
 *
 * The inner and cover screens are different activities (Motorola moves one, or the recorder
 * launches the other), and each gets its own ViewModel. Anything held in a ViewModel was
 * therefore forgotten on every fold, which is why the cover and the inner screen felt like
 * two apps. Everything the user would expect to "stay where it was" lives here instead —
 * process-wide, so folding and unfolding pick up exactly where they left off — and the
 * parts worth surviving a process restart are written through to preferences.
 */
object AppUiState {

    val tab = MutableStateFlow(AppTab.LIVE)

    /** The last tab that was not Settings — what the inner screen shows under its settings panel. */
    val contentTab = MutableStateFlow(AppTab.LIVE)
    val openGroup = MutableStateFlow<GroupRef?>(null)
    val textMode = MutableStateFlow(TextMode.ORIGINAL)

    /** Segments picked for a partial export, in the open group. */
    val selection = MutableStateFlow<Set<Long>>(emptySet())

    /** Whether the Export screen is over whatever was being looked at. */
    val showExport = MutableStateFlow(false)

    /** Which Settings section is expanded. */
    val settingsSection = MutableStateFlow<String?>(null)

    /** Scroll positions by list key. Written continuously, read when a list is composed. */
    val scroll = ConcurrentHashMap<String, ScrollPos>()

    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        val p = context.getSharedPreferences("ui_state", Context.MODE_PRIVATE)
        prefs = p
        p.getString("tab", null)?.let { name -> AppTab.entries.firstOrNull { it.name == name } }?.let {
            tab.value = it
            if (it != AppTab.SETTINGS) contentTab.value = it
        }
        openGroup.value = p.getString("group", null)?.let(GroupRef::parse)
        p.getString("mode", null)?.let { name -> TextMode.entries.firstOrNull { it.name == name } }?.let { textMode.value = it }
    }

    fun selectTab(value: AppTab) {
        tab.value = value
        if (value != AppTab.SETTINGS) contentTab.value = value
        prefs?.edit()?.putString("tab", value.name)?.apply()
    }

    fun open(group: GroupRef?) {
        if (openGroup.value != group) selection.value = emptySet()
        openGroup.value = group
        prefs?.edit()?.putString("group", group?.id)?.apply()
    }

    fun setMode(mode: TextMode) {
        textMode.value = mode
        prefs?.edit()?.putString("mode", mode.name)?.apply()
    }


    fun toggleSelected(id: Long) = selection.update { if (id in it) it - id else it + id }
}

/** A month of archived days, for the Logs calendar. */
data class MonthSummary(
    val monthKey: Int,
    val days: List<com.recorder.core.storage.DaySummary>,
) {
    val lineCount: Int get() = days.sumOf { it.count }
}
