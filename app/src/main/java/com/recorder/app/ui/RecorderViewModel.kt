package com.recorder.app.ui

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import com.recorder.app.BuildConfig
import com.recorder.app.admin.DeviceOwner
import com.recorder.app.admin.Lockdown
import com.recorder.app.cover.CoverDisplays
import com.recorder.core.asr.AsrEngineFactory
import com.recorder.core.llm.local.LocalModelRuntime
import androidx.lifecycle.viewModelScope
import com.recorder.app.ServiceLocator
import com.recorder.app.service.PowerMetrics
import com.recorder.app.ui.setup.SetupCheck
import com.recorder.app.ui.setup.SetupStatus
import com.recorder.app.update.AvailableUpdate
import com.recorder.app.update.UpdateChecker
import com.recorder.app.service.RecordingService
import com.recorder.core.llm.local.DeviceCapabilities
import com.recorder.core.llm.local.OnDeviceModel
import com.recorder.core.storage.FlaggedItem
import com.recorder.core.storage.Folder
import com.recorder.core.storage.TranscriptSegment
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import com.recorder.app.correction.CorrectionGate
import com.recorder.app.data.Deletions
import com.recorder.app.summary.SummaryRunner
import com.recorder.app.summary.asGroupSummary
import com.recorder.core.llm.GroupSummary
import com.recorder.app.correction.CorrectionRunner
import com.recorder.app.correction.OnDemandAiWorker
import com.recorder.app.export.ExportGrouping
import com.recorder.app.export.ExportQuery
import com.recorder.app.export.ExportRange
import com.recorder.app.export.Exporter
import com.recorder.app.export.TimeWindow
import com.recorder.app.models.InstallProgress
import com.recorder.app.models.ModelCatalog
import com.recorder.app.models.ModelDownloadService
import com.recorder.app.models.ModelEntry
import com.recorder.app.models.ModelInstallStore
import com.recorder.app.service.MicConflict
import com.recorder.app.service.MicLevels
import com.recorder.core.storage.DayKey
import com.recorder.core.storage.CorrectionRunRecord
import com.recorder.core.storage.DaySummary
import com.recorder.app.StartupGuard
import com.recorder.app.diag.DeviceWatch
import com.recorder.app.diag.SelfReport
import com.recorder.app.models.ModelHealth
import com.recorder.core.storage.AiPasses
import com.recorder.core.storage.Diagnostics
import com.recorder.core.storage.DiagnosticEntry
import com.recorder.core.storage.ExportDefaults
import com.recorder.core.storage.FtsQuery
import com.recorder.core.storage.HourSummary
import com.recorder.core.storage.UserCorrection
import com.recorder.core.storage.RunningTasks
import com.recorder.core.storage.latestBySegment
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RecorderViewModel(application: Application) : AndroidViewModel(application) {

    private val db = ServiceLocator.database
    private val settings = ServiceLocator.settings

    val transcripts: StateFlow<List<TranscriptSegment>> =
        db.transcripts().recent().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Transcript for the cover screen: oldest first so the newest lands at the bottom, and
     * sampled rather than streamed. A burst of segments would otherwise recompose the list
     * several times a second on a screen nobody is watching closely, which is battery spent
     * for nothing on an always-on OLED.
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    val coverTranscripts: StateFlow<List<TranscriptSegment>> =
        db.transcripts().recent(COVER_SEGMENT_LIMIT)
            .map { newestFirst -> newestFirst.asReversed() }
            .sample(COVER_REFRESH_MS)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val flagged: StateFlow<List<FlaggedItem>> =
        db.flagged().active().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val folders: StateFlow<List<Folder>> =
        db.folders().all().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recorderState: StateFlow<RecordingService.RecorderState> = RecordingService.state

    val triggerKeywords: StateFlow<Set<String>> =
        settings.triggerKeywords.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** Null until read, so the UI does not flash the wizard at a configured phone. */
    val setupComplete: StateFlow<Boolean?> =
        settings.setupComplete.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    // --- Shared screen state (the same object on the cover and the inner screen) ---

    val tab: StateFlow<AppTab> = AppUiState.tab
    val openGroup: StateFlow<GroupRef?> = AppUiState.openGroup
    val textMode: StateFlow<TextMode> = AppUiState.textMode
    val selection: StateFlow<Set<Long>> = AppUiState.selection
    val micSilenced: StateFlow<Boolean> = RecordingService.micSilenced
    val correctionProgress: StateFlow<String?> = CorrectionRunner.progress

    /**
     * What the app is busy with, or null. Drives the progress bar on both screens: work
     * that takes a minute with no sign of life reads as work that is not happening.
     */
    val busy: StateFlow<String?> = RunningTasks.summary

    // --- Groups: today by hour, earlier days by day ---

    /** Ticks when the local day changes, so "today" rolls over at midnight. */
    private val today: Flow<Int> = flow {
        while (true) {
            emit(DayKey.today())
            delay(30_000)
        }
    }.distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    val todayHours: StateFlow<List<HourSummary>> = today
        .flatMapLatest { day ->
            val offset = TimeZone.getDefault().getOffset(System.currentTimeMillis()).toLong()
            db.transcripts().hourSummaries(DayKey.startOf(day), DayKey.endOf(day), offset)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val days: StateFlow<List<DaySummary>> =
        db.transcripts().daySummaries().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // --- Logs as a calendar ----------------------------------------------------------------
    //
    // Anything from the last day stays a flat list of hours, which is what you actually scroll
    // when you are looking for something you said this morning. Everything older rolls up into
    // months, then days, then hours, because a flat list of every hour ever recorded stops
    // being navigable within about a week.

    /**
     * The moving 24-hour boundary, re-read every few minutes.
     *
     * One value shared by both sides of the split, because they have to agree: if the live
     * list used a boundary that moved only at midnight while the archive used the current
     * time, a day would show up in both at once.
     */
    private val recentCutoff: Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis() - RECENT_WINDOW_MS)
            delay(CUTOFF_REFRESH_MS)
        }
    }

    /** Hours in the last 24 hours, newest first — the live list, regardless of date. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val recentHours: StateFlow<List<HourSummary>> = recentCutoff
        .flatMapLatest { from ->
            val offset = TimeZone.getDefault().getOffset(System.currentTimeMillis()).toLong()
            db.transcripts().hourSummaries(from, Long.MAX_VALUE, offset)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Days older than the recent window, grouped by month, newest month first.
     *
     * A day is only archived once all of it has aged out, so nothing appears in both the
     * live list and the calendar.
     */
    val months: StateFlow<List<MonthSummary>> = combine(days, recentCutoff) { all, cutoff ->
            all.filter { it.lastTs < cutoff }
                .groupBy { it.dayKey / 100 }
                .map { (month, inMonth) -> MonthSummary(month, inMonth.sortedByDescending { it.dayKey }) }
                .sortedByDescending { it.monthKey }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _openDay = MutableStateFlow<Int?>(null)

    /** The day expanded in the calendar, or null. */
    val openDay: StateFlow<Int?> = _openDay.asStateFlow()

    /** The hours of [openDay], loaded only while one is expanded. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val openDayHours: StateFlow<List<HourSummary>> = _openDay
        .flatMapLatest { day ->
            if (day == null) {
                flowOf(emptyList())
            } else {
                val offset = TimeZone.getDefault().getOffset(System.currentTimeMillis()).toLong()
                db.transcripts().hourSummaries(DayKey.startOf(day), DayKey.endOf(day), offset)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun openDay(dayKey: Int?) {
        _openDay.value = if (_openDay.value == dayKey) null else dayKey
    }

    /** Lines of the open group, each with its newest correction. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val groupLines: StateFlow<List<LineView>> = AppUiState.openGroup
        .flatMapLatest { group ->
            if (group == null || group.kind == GroupKind.ALL) {
                flowOf(emptyList())
            } else {
                combine(
                    db.transcripts().inRangeFlow(group.fromTs, group.toTs),
                    db.corrections().inRangeFlow(group.fromTs, group.toTs),
                ) { segments, corrections ->
                    val latest = corrections.latestBySegment()
                    segments.map { LineView(it, latest[it.id]) }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // --- Search across every log ---------------------------------------------------------

    private val _logQuery = MutableStateFlow("")

    /** What is typed in the Logs search box. Empty means the normal grouped list. */
    val logQuery: StateFlow<String> = _logQuery.asStateFlow()

    /**
     * Lines matching [logQuery], newest first. Every word has to appear, so adding a word
     * narrows the list. Debounced because this runs an FTS query per keystroke otherwise.
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class, ExperimentalCoroutinesApi::class)
    val logMatches: StateFlow<List<TranscriptSegment>> = _logQuery
        .debounce(SEARCH_DEBOUNCE_MS)
        .mapLatest { raw ->
            val query = FtsQuery.all(raw)
            if (query.isBlank()) {
                emptyList()
            } else {
                runCatching { db.transcripts().search(query, SEARCH_LIMIT) }
                    .onFailure { Diagnostics.w(TAG, "search failed for \"$raw\"", it) }
                    .getOrDefault(emptyList())
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setLogQuery(query: String) {
        _logQuery.value = query
    }

    /** Opens the hour or day a found line belongs to, so it is read in context. */
    fun openContaining(segment: TranscriptSegment) {
        val today = DayKey.today()
        val group = if (DayKey.of(segment.startTs) == today) {
            GroupRef.hour(segment.startTs)
        } else {
            GroupRef.day(DayKey.of(segment.startTs))
        }
        openGroup(group)
    }

    fun selectTab(tab: AppTab) = AppUiState.selectTab(tab)

    fun openGroup(group: GroupRef?) {
        AppUiState.open(group)
        if (group != null) AppUiState.selectTab(AppTab.LOGS)
    }

    fun setTextMode(mode: TextMode) = AppUiState.setMode(mode)

    fun toggleSelected(id: Long) = AppUiState.toggleSelected(id)

    fun clearSelection() {
        AppUiState.selection.value = emptySet()
    }

    // --- Deleting what should not have been recorded ---------------------------------------
    //
    // The app captures everything it can, so the way out of a recording you did not want is to
    // throw it away afterwards: a swipe on one line in Live, or a selection in Logs. Both go
    // through [Deletions], which is the only thing that writes the three tables involved.

    /** The last delete, while it can still be undone. Drives the Undo offer in the status line. */
    val undoableDelete: StateFlow<Deletions.Undo?> = Deletions.undo

    /** Swiped away in Live, or a single line anywhere. */
    fun deleteSegment(id: Long) = deleteSegments(setOf(id))

    /** "Delete n", pressed on a selection in Logs. */
    fun deleteSelected() {
        val chosen = AppUiState.selection.value
        if (chosen.isEmpty()) return
        clearSelection()
        deleteSegments(chosen)
    }

    private fun deleteSegments(ids: Set<Long>) {
        viewModelScope.launch(Dispatchers.IO) {
            val removed = runCatching { Deletions.delete(ids) }.getOrElse { error ->
                Diagnostics.w(TAG, "delete failed", error)
                _status.value = "Could not delete: ${error.message ?: error.javaClass.simpleName}"
                return@launch
            }
            _status.value = when (removed) {
                0 -> "Nothing to delete."
                1 -> "Deleted 1 line."
                else -> "Deleted $removed lines."
            }
        }
    }

    fun undoDelete() {
        viewModelScope.launch(Dispatchers.IO) {
            val back = runCatching { Deletions.undoLast() }.getOrDefault(0)
            _status.value = if (back > 0) "Put $back line(s) back." else "Nothing left to undo."
        }
    }

    fun forgetUndo() = Deletions.forget()

    /**
     * Deletes a whole group — the hour, day or month that is open, or a row in the calendar.
     *
     * The unit people actually want. Deleting a stretch of the day one line at a time was never
     * a real answer to "it recorded something it should not have", and the calendar already
     * groups the recording into exactly the stretches worth throwing away.
     *
     * Live updates itself: it reads the transcript as a Room Flow, so the hour disappears from
     * the live feed the moment its rows go, with nothing to refresh and nothing to invalidate.
     */
    fun deleteGroup(group: GroupRef) {
        viewModelScope.launch(Dispatchers.IO) {
            val removed = runCatching { Deletions.deleteRange(group.fromTs, group.toTs) }
                .getOrElse { error ->
                    Diagnostics.w(TAG, "group delete failed", error)
                    _status.value = "Could not delete: ${error.message ?: error.javaClass.simpleName}"
                    return@launch
                }
            if (removed > 0 && AppUiState.openGroup.value == group) AppUiState.open(null)
            _status.value = if (removed > 0) "Deleted $removed line(s)." else "Nothing in that group."
        }
    }

    /** How many lines a group holds, for the confirmation that names the number. */
    suspend fun groupSize(group: GroupRef): Int =
        runCatching { Deletions.countIn(group.fromTs, group.toTs) }.getOrDefault(0)

    // --- What each group was about ---------------------------------------------------------
    //
    // Hours are the topics; a day is its hours rolled up; a month is its days. The name of each
    // group is what makes a month of recordings scannable, so it is on the calendar row itself
    // rather than only inside the group.

    /**
     * Every summary, indexed by the span it covers, so a calendar row can look up its own name
     * without a query per row. One flow for the whole list: the calendar shows dozens of rows
     * and re-queries them on every scroll.
     */
    val summaries: StateFlow<Map<String, GroupSummary>> =
        db.review().recent(SUMMARY_INDEX_LIMIT)
            .map { rows ->
                rows.asSequence()
                    // Newest first out of the query, so the first row for a span wins.
                    .distinctBy { it.fromTs to it.toTs }
                    .associate { spanKey(it.fromTs, it.toTs) to it.asGroupSummary() }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** The summary of the group that is open, or null when it has none yet. */
    val openGroupSummary: StateFlow<GroupSummary?> =
        combine(AppUiState.openGroup, summaries) { group, index ->
            group?.let { index[spanKey(it.fromTs, it.toTs)] }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** What is being summarised, for the group screen. Null when nothing is. */
    val summaryProgress: StateFlow<String?> = SummaryRunner.progress

    /** Whether [group] is a span that can be summarised at all. */
    fun canSummarise(group: GroupRef): Boolean = SummaryRunner.levelOf(group) != null

    /**
     * "Summarise", pressed on a group. Replaces whatever summary it had.
     *
     * Runs on IO because the work is a model blocking in native code, and reports through the
     * same progress surface as correction so it can be cancelled from the bar on either screen.
     */
    fun summarise(group: GroupRef) {
        if (!canSummarise(group)) return
        OnDemandAiWorker.enqueue(getApplication(), OnDemandAiWorker.JOB_SUMMARISE, group)
        _status.value = "Summarising ${group.title()}…"
    }

    fun spanKey(fromTs: Long, toTs: Long): String = "$fromTs:$toTs"

    /**
     * Corrects one group, now, because the user pressed the button.
     *
     * The only on-demand AI action left in the app. Runs off the main thread and reports
     * through the same progress surface as the overnight pass, so it can be cancelled.
     */
    fun recorrect(group: GroupRef) {
        if (group.kind == GroupKind.ALL) return
        OnDemandAiWorker.enqueue(getApplication(), OnDemandAiWorker.JOB_CORRECT, group)
        _status.value = "Correcting ${group.title()}…"
    }

    fun copy(text: String) {
        val context = getApplication<Application>()
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("Recorder", text))
        _status.value = "Copied"
    }

    /** Hands text to the share sheet. The user picks the app and presses send themselves. */
    fun shareText(text: String) {
        val context = getApplication<Application>()
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }


    // --- Diagnostics: what went wrong, readable from the phone alone ---

    val diagnostics: StateFlow<List<DiagnosticEntry>> = Diagnostics.entries

    fun diagnosticsText(): String = Diagnostics.renderText(deviceSummary())

    fun copyDiagnostics() = copy(diagnosticsText())

    fun shareDiagnostics() = shareText(diagnosticsText())

    /**
     * Clears both reports, for real.
     *
     * "Clear" used to empty the log list and nothing else, so the next self-diagnostic report
     * came back with the same model passes, the same battery transitions and the same totals as
     * the one before it — which makes the report useless for answering "did that fix it?", the
     * only question anybody generates one to answer.
     *
     * Everything a report reads and that this process owns is reset: the log entries, the
     * recorded model passes and the battery and thermal transitions. The transcription totals
     * belong to the running recorder and reset when it restarts, which the report says.
     */
    fun clearDiagnostics() {
        Diagnostics.clear()
        AiPasses.clear()
        DeviceWatch.clear()
        _selfReport.value = null
        _status.value = "Diagnostics and reports cleared"
    }

    private val _selfReport = MutableStateFlow<String?>(null)

    /**
     * The last self-diagnostic report, or null until one is asked for.
     *
     * Held rather than copied straight to the clipboard so it can be read on the phone as
     * well as pasted elsewhere — and so a report taken at a particular moment stays that
     * report while it is being looked at.
     */
    val selfReport: StateFlow<String?> = _selfReport.asStateFlow()

    fun generateSelfReport() = viewModelScope.launch(Dispatchers.Default) {
        _status.value = "Building report…"
        val text = runCatching { SelfReport.build(getApplication<Application>()) }
            .getOrElse { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                "The report could not be built: ${error.message ?: error.javaClass.simpleName}"
            }
        _selfReport.value = text
        _status.value = "Report ready"
    }

    fun clearSelfReport() {
        _selfReport.value = null
    }

    // --- models: is what is on disk actually complete ------------------------------------

    private val _repairReport = MutableStateFlow<String?>(null)

    /** What the last repair removed, or null before one has been asked for. */
    val repairReport: StateFlow<String?> = _repairReport.asStateFlow()

    /** Every model and what is wrong with it, recomputed on demand rather than cached. */
    fun modelSurvey(): List<Pair<String, String?>> =
        ModelHealth.survey(getApplication<Application>()).map { (entry, problem) -> entry.displayName to problem }

    /**
     * Removes what is left of every unfinished install. An interrupted download cannot be
     * turned into a working model, and leaving it on disk is what makes the app look ready
     * and behave broken.
     */
    fun repairModels() = viewModelScope.launch(Dispatchers.Default) {
        val removed = ModelHealth.discardUnfinished(getApplication<Application>())
        _repairReport.value = if (removed.isEmpty()) {
            "Nothing to remove: every model on this phone is complete."
        } else {
            "Removed ${removed.size} unfinished install(s):\n" + removed.joinToString("\n")
        }
        _status.value = if (removed.isEmpty()) {
            "Every model is complete"
        } else {
            "Removed ${removed.size} unfinished install(s)"
        }
    }

    /** Models from an earlier version still on disk, as one line, or null when there are none. */
    fun strayModels(): String? {
        val stray = ModelHealth.strayModelFiles(getApplication<Application>())
        if (stray.isEmpty()) return null
        val mb = stray.sumOf { it.length() } / (1024 * 1024)
        return "${stray.size} model(s) from an earlier version are still here, using about $mb MB."
    }

    fun removeStrayModels() = viewModelScope.launch(Dispatchers.IO) {
        _repairReport.value = ModelHealth.removeStrayModelFiles(getApplication<Application>())
    }

    /** Leaves safe mode and starts recording, at the user's request. */
    fun leaveSafeMode() {
        val context = getApplication<Application>()
        StartupGuard.clearSafeMode(context)
        RecordingService.start(context)
        _status.value = "Starting normally"
    }

    suspend fun segmentsByIds(ids: List<Long>): Map<Long, TranscriptSegment> =
        ids.distinct().chunked(900).flatMap { db.transcripts().byIds(it) }.associateBy { it.id }

    // --- Export ------------------------------------------------------------------------

    private val _exportQuery = MutableStateFlow(ExportQuery())

    /** Everything the export screen is showing. One object, so nothing can disagree. */
    val exportQuery: StateFlow<ExportQuery> = _exportQuery.asStateFlow()

    private val _exportPreview = MutableStateFlow<Exporter.Preview?>(null)

    /** The live match count and estimated size, or null while it is being counted. */
    val exportPreview: StateFlow<Exporter.Preview?> = _exportPreview.asStateFlow()

    /**
     * Restores the remembered settings once, so opening Export shows what was used last.
     *
     * Read rather than collected: after this the screen's own state is the truth, and a late
     * DataStore emission overwriting what someone has just typed is a bug, not a feature.
     */
    private fun loadExportDefaults() = viewModelScope.launch {
        val start = settings.exportWindowStart.first()
        val end = settings.exportWindowEnd.first()
        _exportQuery.update { current ->
            current.copy(
                range = settings.exportRange.first(),
                customFromDay = settings.exportCustomFrom.first(),
                customToDay = settings.exportCustomTo.first(),
                timeWindow = if (start >= 0 && end >= 0) TimeWindow(start, end) else null,
                includeField = settings.exportInclude.first(),
                excludeField = settings.exportExclude.first(),
                includeAll = settings.exportIncludeAll.first(),
                content = settings.exportContent.first(),
                format = settings.exportFormat.first(),
                grouping = settings.exportGrouping.first(),
                destination = settings.exportDestination.first(),
            )
        }
    }

    /**
     * Opens the screen. [onlyIds] comes from a long-press selection; a group passes its own
     * range as a custom range, so what is being exported is visible and adjustable rather
     * than implied by which screen it was opened from.
     */
    fun openExport(group: GroupRef? = null, onlyIds: Set<Long> = emptySet()) {
        loadExportDefaults()
        if (group != null || onlyIds.isNotEmpty()) {
            _exportQuery.update { current ->
                current.copy(
                    onlyIds = onlyIds,
                    range = if (onlyIds.isEmpty() && group != null) ExportRange.CUSTOM else current.range,
                    customFromDay = group?.let { DayKey.of(it.fromTs) } ?: current.customFromDay,
                    // toTs is exclusive — the next midnight for a day or a month, and the next
                    // day for the 23:00 hour — while customToDay is inclusive. Without the
                    // step back, exporting one day exported two, and a month 32 days.
                    customToDay = group?.let { DayKey.of(it.toTs - 1) } ?: current.customToDay,
                )
            }
        }
        AppUiState.showExport.value = true
        recountExport()
    }

    fun closeExport() {
        AppUiState.showExport.value = false
    }

    fun clearExportSelection() = editExport { it.copy(onlyIds = emptySet()) }

    fun setExportRange(value: String) = editExport { it.copy(range = value) }
    fun setExportCustomFrom(day: Int) = editExport { it.copy(customFromDay = day) }
    fun setExportCustomTo(day: Int) = editExport { it.copy(customToDay = day) }
    fun setExportInclude(value: String) = editExport { it.copy(includeField = value) }
    fun setExportExclude(value: String) = editExport { it.copy(excludeField = value) }
    fun setExportIncludeAll(value: Boolean) = editExport { it.copy(includeAll = value) }
    fun setExportContent(value: String) = editExport { it.copy(content = value) }
    fun setExportFormat(value: String) = editExport { it.copy(format = value) }
    fun setExportGrouping(value: String) = editExport { it.copy(grouping = value) }
    fun setExportDestination(value: String) = editExport { it.copy(destination = value) }

    fun setExportWindowEnabled(on: Boolean) = editExport { query ->
        query.copy(timeWindow = if (on) query.timeWindow ?: DEFAULT_WINDOW else null)
    }

    fun setExportWindowStart(minute: Int) = editExport { query ->
        query.copy(timeWindow = (query.timeWindow ?: DEFAULT_WINDOW).copy(startMinute = minute))
    }

    fun setExportWindowEnd(minute: Int) = editExport { query ->
        query.copy(timeWindow = (query.timeWindow ?: DEFAULT_WINDOW).copy(endMinute = minute))
    }

    /** Back to the shipped defaults, on disk and on screen. */
    fun resetExport() {
        viewModelScope.launch { settings.resetExport() }
        _exportQuery.value = ExportQuery()
        recountExport()
    }

    private fun editExport(change: (ExportQuery) -> ExportQuery) {
        _exportQuery.update(change)
        recountExport()
    }

    private var counting: Job? = null

    /**
     * Recounts after a pause in typing rather than on every keystroke: the count is a scan over
     * the range, and running it per character on a day with thousands of lines is how a text
     * field starts dropping input.
     */
    private fun recountExport() {
        counting?.cancel()
        _exportPreview.value = null
        counting = viewModelScope.launch(Dispatchers.IO) {
            delay(COUNT_DEBOUNCE_MS)
            val query = _exportQuery.value
            _exportPreview.value = runCatching { Exporter.preview(query) }.getOrNull()
        }
    }

    /** Writes the export and remembers the settings as the next export's defaults. */
    fun runExport(onDone: () -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        val query = _exportQuery.value
        val title = exportTitle(query)
        _status.value = Exporter.export(getApplication(), title, query, query.destination)
            .fold(onSuccess = { it }, onFailure = { "Export failed: ${it.message}" })
        settings.saveExport(
            range = query.range,
            customFromDay = query.customFromDay,
            customToDay = query.customToDay,
            windowStart = query.timeWindow?.startMinute ?: -1,
            windowEnd = query.timeWindow?.endMinute ?: -1,
            include = query.includeField,
            exclude = query.excludeField,
            includeAll = query.includeAll,
            content = query.content,
            format = query.format,
            grouping = query.grouping,
            destination = query.destination,
        )
        withContext(Dispatchers.Main) {
            AppUiState.showExport.value = false
            onDone()
        }
    }

    private fun exportTitle(query: ExportQuery): String = when {
        query.isSelection -> "Recorder — ${query.onlyIds.size} selected lines"
        query.range == ExportRange.CUSTOM -> "Recorder — ${query.customFromDay} to ${query.customToDay}"
        else -> "Recorder — ${ExportRange.label(query.range).lowercase()}"
    }

    // --- Two versions side by side ---

    /**
     * Opens the other installed version of Recorder — reached from the silencing banner
     * ([RecorderApp]'s SilencedBanner), which shows when this app's own recording has
     * actually gone silent, rather than from any guess made before starting.
     */
    fun openOtherRecorder() {
        if (!MicConflict.openOther(getApplication())) _status.value = "Could not open the other Recorder."
    }

    // --- Model and correction settings ---

    val endOfDayEnabled = settings.endOfDayEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setEndOfDayEnabled(on: Boolean) = viewModelScope.launch { settings.setEndOfDayEnabled(on) }

    /** The one model, and whether it can be loaded right now. */
    fun modelStatus(): String {
        val context = getApplication<Application>()
        return OnDeviceModel.problem(context)
            ?.let { "${OnDeviceModel.LABEL} — $it" }
            ?: "${OnDeviceModel.LABEL} — ready"
    }

    /** Per-model download state, so Settings shows the same truth as the wizard. */
    val modelStates: StateFlow<Map<String, InstallProgress>> = ModelInstallStore.states

    /** Every catalogue entry, for the Settings model list. */
    fun catalogue(): List<ModelEntry> =
        runCatching { ModelCatalog.load(getApplication()) }.getOrDefault(emptyList())

    fun isModelInstalled(entry: ModelEntry): Boolean = entry.isInstalled(getApplication())

    /** Queues a model from Settings, using the same service the wizard uses. */
    fun downloadModel(id: String) {
        ModelDownloadService.start(getApplication(), listOf(id))
        _status.value = "Queued. Progress is in the notification and in Settings → Models."
    }

    fun setRecording(enabled: Boolean) {
        val context = getApplication<Application>()
        viewModelScope.launch { settings.setRecordingEnabled(enabled) }
        if (enabled) RecordingService.start(context) else RecordingService.stop(context)
    }

    // --- Microphone sensitivity -----------------------------------------------------------

    /** The detector's live score, for the meter in Settings. */
    val micScore: StateFlow<Float> = MicLevels.score

    /** The live frame peak, so a dead microphone looks different from a quiet room. */
    val micPeak: StateFlow<Float> = MicLevels.peak

    /** The highest score since the meter was last cleared, so a brief spike is not missed. */
    val micHighest: StateFlow<Float> = MicLevels.highest

    val vadThreshold: StateFlow<Float> = settings.vadThreshold
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0.3f)

    fun setVadThreshold(value: Float) = viewModelScope.launch {
        settings.setVadThreshold(value)
    }

    fun resetMicHighest() = MicLevels.resetHighest()

    /** 24-hour or 12-hour, applied to every time the app shows. */
    val use24HourClock: StateFlow<Boolean> = settings.use24HourClock
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setUse24HourClock(use24: Boolean) = viewModelScope.launch {
        settings.setUse24HourClock(use24)
    }

    // --- Running work ----------------------------------------------------------------------

    /** Everything in flight, for the progress bar's elapsed time and Cancel button. */
    val runningTasks: StateFlow<List<RunningTasks.Task>> = RunningTasks.tasks

    /** Stops whatever is running. Actually stops it; it does not just hide the bar. */
    /**
     * Cancel, pressed on the progress bar.
     *
     * Two things now, because the work no longer lives in a ViewModel: the in-flight generation
     * is asked to unwind through its registered cancel, and the WorkManager job hosting it is
     * cancelled too. Cancelling only the first would leave the worker alive to start the next
     * batch; cancelling only the second would leave the model generating until it noticed.
     */
    fun cancelRunning() {
        RunningTasks.cancelAll()
        OnDemandAiWorker.cancel(getApplication())
    }

    // --- AI scheduling ---------------------------------------------------------------------

    /** Lines waiting for a pass. Drives the "process now" control and the Settings figure. */
    val pendingCorrections: StateFlow<Int> = CorrectionRunner.pendingCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** The last pass, persisted, so this does not read "never" after every restart. */
    val lastCorrectionRun: StateFlow<CorrectionRunRecord?> = settings.lastCorrectionRun
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Why automatic passes are or are not running right now, in a sentence. */
    fun schedulingStatus(): String = CorrectionGate.describe(getApplication())

    // --- The reviewable summary ------------------------------------------------------------

    /** Everything the user has taught it, newest first, for Settings. */
    val taughtCorrections: StateFlow<List<UserCorrection>> = db.review().corrections()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun forgetCorrection(id: Long) = viewModelScope.launch(Dispatchers.Default) {
        db.review().forget(id)
    }

    fun forgetAllCorrections() = viewModelScope.launch(Dispatchers.Default) {
        db.review().forgetAll()
        _status.value = "Cleared what the AI had been taught."
    }

    /** When the current pause ends, or 0. Shown on both the inner and the cover screen. */
    val pausedUntil: StateFlow<Long> = RecordingService.pausedUntil

    /**
     * Stops writing text for [minutes] without closing the microphone or the service.
     *
     * Not the same as switching recording off. A stopped recorder cannot start itself again
     * on Android 14+ — a microphone service launched from the background is refused — so a
     * timed pause that actually comes back has to keep the service alive.
     */
    fun pauseFor(minutes: Int) = RecordingService.pauseFor(minutes)

    fun resumeNow() = RecordingService.resumeNow()

    fun dismissFlag(id: Long) = viewModelScope.launch { db.flagged().dismiss(id) }

    fun saveTriggers(keywords: Set<String>) = viewModelScope.launch {
        settings.setTriggerKeywords(keywords)
    }

    private val deviceOwner by lazy { DeviceOwner(getApplication<Application>()) }

    fun deviceOwnerActive(): Boolean = deviceOwner.isActive

    fun deviceOwnerStatus(): String = deviceOwner.statusText()

    /** The shell command that grants device owner; shown so it can be run via Shizuku or adb. */
    fun deviceOwnerCommand(): String =
        DeviceOwner.setupCommand(getApplication<Application>().packageName)

    fun removeDeviceOwner() {
        _status.value = deviceOwner.clear().fold(
            onSuccess = { "Device owner removed. Recording will need a tap after a reboot." },
            onFailure = { "Could not remove device owner: ${it.message}" },
        )
    }

    /** Live setup checks, re-read each time rather than remembered from the wizard. */
    fun setupChecks(): List<SetupCheck> = SetupStatus.check(getApplication())

    /** One line for the Settings row: the battery, and how many checks are not passing. */
    fun batterySummary(): String {
        val context = getApplication<Application>()
        val battery = DeviceWatch.read(context)
        val failing = runCatching { setupChecks().count { !it.ok } }.getOrDefault(0)
        return battery.describe().substringBefore(", thermal") +
            if (failing > 0) " · $failing check(s) to fix" else " · all checks pass"
    }

    private val updateChecker by lazy { UpdateChecker(getApplication<Application>()) }

    private val _update = MutableStateFlow<String?>(null)
    val update: StateFlow<String?> = _update.asStateFlow()

    private var pendingUpdate: AvailableUpdate? = null

    fun checkForUpdate() {
        _update.value = "Checking…"
        viewModelScope.launch {
            updateChecker.check().fold(
                onSuccess = { available ->
                    pendingUpdate = available
                    _update.value = when (available) {
                        null -> "You are on the latest version."
                        else -> "Version ${available.versionName} is available " +
                            "(${available.sizeBytes / (1024 * 1024)} MB). Tap download to install it."
                    }
                },
                onFailure = { _update.value = "Could not check: ${it.message}" },
            )
        }
    }

    val updateAvailable: Boolean get() = pendingUpdate != null

    fun downloadUpdate() {
        val target = pendingUpdate ?: return
        _update.value = "Downloading ${target.versionName}…"
        viewModelScope.launch {
            updateChecker.download(target) { written, total ->
                if (total > 0) {
                    _update.value = "Downloading ${target.versionName}: " +
                        "${written / (1024 * 1024)} / ${total / (1024 * 1024)} MB"
                }
            }.fold(
                onSuccess = { file ->
                    _update.value = "Downloaded. Confirm the install when Android asks."
                    updateChecker.install(file)
                },
                onFailure = { _update.value = "Download failed: ${it.message}" },
            )
        }
    }

    private val lockdown by lazy { Lockdown(getApplication<Application>()) }

    fun lockdownAvailable(): Boolean = lockdown.available

    fun lockdownStatus(): String {
        lockdown.unavailableReason?.let { return it }
        return lockdown.candidates().summary()
    }

    fun applyLockdown() = viewModelScope.launch {
        val plan = lockdown.candidates()
        if (plan.isEmpty) {
            _status.value = "Nothing to suspend."
            return@launch
        }
        val applied = lockdown.apply(plan)
        settings.setSuspendedPackages(applied.toSet())
        _status.value = "Suspended ${applied.size} of ${plan.all().size} apps. Reversible."
    }

    fun undoLockdown() = viewModelScope.launch {
        val recorded = settings.suspendedPackages.first()
        if (recorded.isEmpty()) {
            _status.value = "Nothing was locked down by this app."
            return@launch
        }
        val restored = lockdown.undo(recorded)
        settings.setSuspendedPackages(emptySet())
        _status.value = "Restored ${restored.size} apps."
    }

    /**
     * The numbers that decide whether a full day of listening fits in the battery. Reset
     * when the process does, which the screen states rather than hides.
     */
    fun powerReport(): String {
        val s = PowerMetrics.snapshot()
        val hours = s.micUptimeMs / 3_600_000.0

        fun duration(ms: Long): String {
            val totalMinutes = ms / 60_000
            return "%dh %02dm".format(totalMinutes / 60, totalMinutes % 60)
        }

        return """
            Microphone uptime: ${duration(s.micUptimeMs)}
            Speech transcribed: ${duration(s.audioMsTranscribed)} (${"%.1f".format(s.speechSharePercent)}% of uptime)
            Segments decoded: ${s.transcriptions}
            Decoder CPU time: ${s.asrCpuMs / 1000}s (${"%.2f".format(s.asrDutyCyclePercent)}% duty cycle)
            Decode per hour: ${if (hours > 0) "%.0fs".format(s.asrCpuMs / 1000.0 / hours) else "n/a"}
            Model loads / unloads: ${LocalModelRuntime.loadCount} / ${LocalModelRuntime.unloadCount}
            Model resident now: ${LocalModelRuntime.current ?: "no"}

            No wakelocks are taken by this app. The foreground service and the audio
            recorder keep the CPU available while recording; nothing else holds one.
            Counters reset when the app's process restarts.
        """.trimIndent()
    }

    fun clearStatus() {
        _status.value = null
    }

    private companion object {
        const val TAG = "RecorderViewModel"

        /** Enough history to scroll back a little on a 4 inch screen, not enough to cost. */
        const val COVER_SEGMENT_LIMIT = 60
        const val COVER_REFRESH_MS = 1_000L

        /** How long entries stay in the flat live list before rolling up into the calendar. */
        const val RECENT_WINDOW_MS = 24 * 60 * 60 * 1000L

        /** How often the boundary is re-read. Fine-grained enough; it moves by the hour. */
        const val CUTOFF_REFRESH_MS = 5 * 60 * 1000L

        /** One query per pause in typing, not one per keystroke. */
        const val SEARCH_DEBOUNCE_MS = 250L
        const val SEARCH_LIMIT = 80

        /**
         * How many group summaries are held for the calendar's row names.
         *
         * At most 24 hours plus a day plus a month per day recorded, so this covers roughly
         * three months of continuous use. Older rows are still in the database and still
         * appear when their group is opened; they just do not get a name on the row.
         */
        const val SUMMARY_INDEX_LIMIT = 2_000

        /** One recount per pause in typing, not one per keystroke. */
        const val COUNT_DEBOUNCE_MS = 250L

        /** The evening, as a starting point when the window is switched on. */
        private val DEFAULT_WINDOW = TimeWindow(startMinute = 18 * 60, endMinute = 23 * 60 + 59)
    }

    /**
     * Reported in Settings so what actually shipped on this phone is visible without a
     * computer: which native runtimes are in the APK, whether their model files arrived,
     * and whether the one model it runs can be loaded.
     */
    fun deviceSummary(): String {
        val context = getApplication<Application>()
        val ram = "%.1f".format(DeviceCapabilities.totalRamGb(context))
        val model = OnDeviceModel.problem(context)?.let { "${OnDeviceModel.LABEL}: $it" }
            ?: "${OnDeviceModel.LABEL}: ready"

        val asr = when {
            !AsrEngineFactory.sherpaBundled -> "not bundled in this build"
            !AsrEngineFactory.modelsInstalled(context) -> "runtime ${AsrEngineFactory.sherpaVersion}, model not downloaded"
            else -> "runtime ${AsrEngineFactory.sherpaVersion}, model installed"
        }

        val llm = LocalModelRuntime.unavailableReason
            ?: ("llama.cpp @ ${LocalModelRuntime.commit}" +
                (LocalModelRuntime.current?.let { ", loaded: $it" } ?: ", idle") +
                (LocalModelRuntime.lastError?.let { "\n  last failure: $it" }.orEmpty()))

        // The kernels llama.cpp dlopen()s at start-up. An empty list here is the whole
        // reason every model "failed to load" while looking perfectly installed.
        val backends = LocalModelRuntime.backendProblem(context)
            ?: LocalModelRuntime.nativeLibrarySummary(context)

        return """
            Device: ${Build.MANUFACTURER} ${Build.MODEL}
            Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
            RAM ${ram} GB
            App ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})

            Speech recognition: $asr
            Local AI runtime: $llm
            Correction model: $model
            Native libraries: $backends

            Displays (read this open, then closed, to learn this phone's cover display):
            ${CoverDisplays.describeAll(context)}
        """.trimIndent()
    }
}
