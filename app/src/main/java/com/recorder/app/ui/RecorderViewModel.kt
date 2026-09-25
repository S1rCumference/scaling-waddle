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
import com.recorder.core.llm.local.LocalModelRuntime as Runtime
import com.recorder.core.storage.FlaggedItem
import com.recorder.core.storage.Folder
import com.recorder.core.storage.TranscriptSegment
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import com.recorder.app.correction.CorrectionGate
import com.recorder.app.correction.CorrectionRunner
import com.recorder.app.export.ExportTarget
import com.recorder.app.export.Exporter
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
import com.recorder.app.diag.SelfReport
import com.recorder.app.models.ModelHealth
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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

    /**
     * Corrects one group, now, because the user pressed the button.
     *
     * The only on-demand AI action left in the app. Runs off the main thread and reports
     * through the same progress surface as the overnight pass, so it can be cancelled.
     */
    fun recorrect(group: GroupRef) {
        if (group.kind == GroupKind.ALL) return
        viewModelScope.launch(Dispatchers.IO) {
            val count = runCatching { CorrectionRunner.runRange(group.fromTs, group.toTs) }.getOrDefault(0)
            _status.value = when {
                count > 0 -> "Corrected $count line(s)."
                else -> CorrectionRunner.lastError ?: "Nothing to correct in this group."
            }
        }
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

    fun clearDiagnostics() {
        Diagnostics.clear()
        _status.value = "Diagnostics cleared"
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

    /** Leaves safe mode and starts recording, at the user's request. */
    fun leaveSafeMode() {
        val context = getApplication<Application>()
        StartupGuard.clearSafeMode(context)
        RecordingService.start(context)
        _status.value = "Starting normally"
    }

    suspend fun segmentsByIds(ids: List<Long>): Map<Long, TranscriptSegment> =
        ids.distinct().chunked(900).flatMap { db.transcripts().byIds(it) }.associateBy { it.id }

    // --- Export ---

    val exportContent: StateFlow<String> =
        settings.exportContent.stateIn(viewModelScope, SharingStarted.Eagerly, ExportDefaults.CONTENT_CORRECTED)
    val exportFormat: StateFlow<String> =
        settings.exportFormat.stateIn(viewModelScope, SharingStarted.Eagerly, ExportDefaults.FORMAT_MARKDOWN)

    /** Exports a group, a range, or — when [onlyIds] is given — just those lines. */
    fun export(
        title: String,
        fromTs: Long,
        toTs: Long,
        onlyIds: Set<Long>?,
        content: String,
        format: String,
        target: ExportTarget,
    ) = viewModelScope.launch {
        val lines = if (onlyIds.isNullOrEmpty()) Exporter.linesFor(fromTs, toTs) else Exporter.linesForIds(onlyIds)
        _status.value = Exporter.export(getApplication(), title, lines, content, format, target)
            .fold(onSuccess = { it }, onFailure = { "Export failed: ${it.message}" })
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

    val correctionEnabled = settings.correctionEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val correctionInterval = settings.correctionIntervalMin.stateIn(viewModelScope, SharingStarted.Eagerly, 15)
    val correctionIntervalCharging =
        settings.correctionIntervalChargingMin.stateIn(viewModelScope, SharingStarted.Eagerly, 3)
    val endOfDayEnabled = settings.endOfDayEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setCorrectionEnabled(on: Boolean) = viewModelScope.launch { settings.setCorrectionEnabled(on) }
    fun setCorrectionIntervals(battery: Int, charging: Int) =
        viewModelScope.launch { settings.setCorrectionIntervals(battery, charging) }
    fun setEndOfDayEnabled(on: Boolean) = viewModelScope.launch { settings.setEndOfDayEnabled(on) }
    fun setExportDefaults(content: String, format: String) =
        viewModelScope.launch { settings.setExportDefaults(content, format) }

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
    fun cancelRunning() = RunningTasks.cancelAll()

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

    /** The whole backlog, now, because the user asked rather than because a timer fired. */
    fun processNow() = viewModelScope.launch(Dispatchers.Default) {
        val done = CorrectionRunner.runAllPending(getApplication(), "Process now")
        _status.value = when {
            done > 0 -> "Corrected $done line(s)."
            else -> CorrectionRunner.lastError ?: "Nothing was waiting."
        }
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

    private val _benchmark = MutableStateFlow<String?>(null)
    val benchmark: StateFlow<String?> = _benchmark.asStateFlow()

    private val _benchmarkRunning = MutableStateFlow(false)
    val benchmarkRunning: StateFlow<Boolean> = _benchmarkRunning.asStateFlow()

    /**
     * Measures this phone with the model that is actually installed, using the backend's own
     * benchmark. Numbers for the README come from here, run on the device — there is no way
     * to produce them off it, so none are guessed.
     */
    fun runBenchmark() {
        if (_benchmarkRunning.value) return
        _benchmarkRunning.value = true
        _benchmark.value = "Loading the model and measuring. This takes a minute."

        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val spec = OnDeviceModel.selected(context)
            _benchmark.value = if (spec == null) {
                "Cannot measure: ${OnDeviceModel.problem(context)}"
            } else {
                val model = Runtime.load(context, spec)
                if (model == null) {
                    "Could not load ${spec.fileName}. Settings -> Diagnostics has the detail."
                } else {
                    val report = model.benchmark()
                    Runtime.unload()
                    buildString {
                        append("Model: ").append(spec.label).append('\n')
                        append("RAM: ").append("%.1f".format(DeviceCapabilities.totalRamGb(context)))
                        append(" GB measured, ").append(DeviceCapabilities.availableRamMb(context))
                        append(" MB free now\n\n")
                        append(report ?: "This backend does not expose a benchmark.")
                    }
                }
            }
            _benchmarkRunning.value = false
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
