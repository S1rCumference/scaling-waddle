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
import com.recorder.app.work.HeavySyncScheduler
import com.recorder.core.connectors.RefreshTokenGoogleAuth
import com.recorder.core.llm.ProviderIds
import com.recorder.core.llm.TranscriptAssistant
import com.recorder.core.llm.local.DeviceCapabilities
import com.recorder.core.llm.local.LocalModelSelector
import com.recorder.core.llm.local.LocalModelRuntime as Runtime
import com.recorder.core.storage.FlaggedItem
import com.recorder.core.storage.Folder
import com.recorder.core.storage.PendingAction
import com.recorder.core.storage.PendingActionStatus
import com.recorder.core.storage.TranscriptSegment
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import com.recorder.app.correction.CorrectionRunner
import com.recorder.app.export.ExportTarget
import com.recorder.app.export.Exporter
import com.recorder.app.service.MicConflict
import com.recorder.core.llm.GroupAssistant
import com.recorder.core.llm.ScopedLine
import com.recorder.core.storage.DayKey
import com.recorder.core.storage.DaySummary
import com.recorder.core.storage.Diagnostics
import com.recorder.core.storage.DiagnosticEntry
import com.recorder.core.storage.ExportDefaults
import com.recorder.core.storage.HourSummary
import com.recorder.core.storage.ModelChoice
import com.recorder.core.storage.latestBySegment
import java.util.TimeZone
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
import kotlinx.coroutines.flow.map
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

    val drafts: StateFlow<List<PendingAction>> =
        db.pendingActions().byStatus(PendingActionStatus.DRAFT)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recorderState: StateFlow<RecordingService.RecorderState> = RecordingService.state

    val triggerKeywords: StateFlow<Set<String>> =
        settings.triggerKeywords.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val activeProvider: StateFlow<String> =
        settings.activeProvider.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val heavyTierEnabled: StateFlow<Boolean> =
        settings.heavyTierEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Null until read, so the UI does not flash the wizard at a configured phone. */
    val setupComplete: StateFlow<Boolean?> =
        settings.setupComplete.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    // --- Shared screen state (the same object on the cover and the inner screen) ---

    val tab: StateFlow<AppTab> = AppUiState.tab
    val openGroup: StateFlow<GroupRef?> = AppUiState.openGroup
    val textMode: StateFlow<TextMode> = AppUiState.textMode
    val conversations: StateFlow<Map<String, List<ChatTurn>>> = AppUiState.conversations
    val selection: StateFlow<Set<Long>> = AppUiState.selection
    val micSilenced: StateFlow<Boolean> = RecordingService.micSilenced
    val correctionProgress: StateFlow<String?> = CorrectionRunner.progress

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

    // --- Ask, scoped to a group ---

    /** Answers a typed question about [group], or about everything for [GroupRef.ALL]. */
    fun ask(group: GroupRef, question: String) {
        if (question.isBlank()) return
        runAction(group, question) { assistant, lines ->
            if (group.kind == GroupKind.ALL) {
                val answer = TranscriptAssistant(ServiceLocator.providers.askProvider().provider, ServiceLocator.rag)
                    .ask(question)
                ChatTurn(question, answer.text, sourceIds = answer.sources.map { it.id })
            } else {
                val answer = assistant.ask(question, lines, group.fromTs, group.toTs)
                ChatTurn(question, answer.text, sourceIds = answer.sources.map { it.id })
            }
        }
    }

    fun summarize(group: GroupRef) = runAction(group, "Summarise this") { assistant, lines ->
        val answer = assistant.summarize(lines)
        ChatTurn("Summarise this", answer.text, sourceIds = answer.sources.map { it.id })
    }

    fun actionItems(group: GroupRef) = runAction(group, "List action items and follow-ups") { assistant, lines ->
        val answer = assistant.actionItems(lines)
        ChatTurn("List action items and follow-ups", answer.text, sourceIds = answer.sources.map { it.id })
    }

    fun draftFollowUp(group: GroupRef, recipient: String) {
        val label = "Draft a follow-up" + if (recipient.isBlank()) "" else " to $recipient"
        runAction(group, label) { assistant, lines ->
            val answer = assistant.draftFollowUp(lines, recipient)
            ChatTurn(label, answer.text, sourceIds = answer.sources.map { it.id }, isDraft = true)
        }
    }

    /** No model involved: every line that mentions the topic. */
    fun find(group: GroupRef, topic: String) {
        if (topic.isBlank()) return
        val label = "Find: $topic"
        viewModelScope.launch {
            AppUiState.appendTurn(group.id, ChatTurn(label, "", pending = true))
            val hits = if (group.kind == GroupKind.ALL) {
                ServiceLocator.rag.retrieve(topic, limit = 60).sortedBy { it.startTs }
                    .map { ScopedLine(it, it.text) }
            } else {
                GroupAssistant.find(topic, scopedLines(group))
            }
            val text = if (hits.isEmpty()) "Nothing in here mentions \"$topic\"."
            else "${hits.size} lines:\n" + GroupAssistant.render(hits)
            AppUiState.completeTurn(group.id, ChatTurn(label, text, sourceIds = hits.map { it.segment.id }))
        }
    }

    /** Re-runs the correction pass over one group, now. */
    fun recorrect(group: GroupRef) {
        if (group.kind == GroupKind.ALL) return
        val label = "Re-run correction"
        viewModelScope.launch {
            AppUiState.appendTurn(group.id, ChatTurn(label, "", pending = true))
            val count = runCatching { CorrectionRunner.runRange(group.fromTs, group.toTs) }.getOrDefault(0)
            val text = when {
                count > 0 -> "Corrected $count lines. The newest correction is shown; the original is kept."
                else -> "Nothing was corrected. " + (CorrectionRunner.lastError?.let { "Reason: $it" }
                    ?: "The group may be empty.")
            }
            AppUiState.completeTurn(group.id, ChatTurn(label, text))
        }
    }

    /** Flags the lines an answer was drawn from, labelled with the question. */
    fun flagSources(turn: ChatTurn) = viewModelScope.launch {
        if (turn.sourceIds.isEmpty()) return@launch
        val keyword = "asked: " + turn.question.take(60)
        db.flagged().insertAll(turn.sourceIds.take(20).map { FlaggedItem(segmentId = it, keyword = keyword) })
        _status.value = "Flagged ${turn.sourceIds.take(20).size} lines"
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

    fun clearConversation(group: GroupRef) = AppUiState.clearConversation(group.id)

    // --- Diagnostics: what went wrong, readable from the phone alone ---

    val diagnostics: StateFlow<List<DiagnosticEntry>> = Diagnostics.entries

    fun diagnosticsText(): String = Diagnostics.renderText(deviceSummary())

    fun copyDiagnostics() = copy(diagnosticsText())

    fun shareDiagnostics() = shareText(diagnosticsText())

    fun clearDiagnostics() {
        Diagnostics.clear()
        _status.value = "Diagnostics cleared"
    }

    suspend fun segmentsByIds(ids: List<Long>): Map<Long, TranscriptSegment> =
        ids.distinct().chunked(900).flatMap { db.transcripts().byIds(it) }.associateBy { it.id }

    private fun runAction(
        group: GroupRef,
        label: String,
        block: suspend (GroupAssistant, List<ScopedLine>) -> ChatTurn,
    ) {
        viewModelScope.launch {
            AppUiState.appendTurn(group.id, ChatTurn(label, "", pending = true))
            val turn = runCatching {
                val chosen = ServiceLocator.providers.askProvider()
                val assistant = if (chosen.cloud) {
                    GroupAssistant(chosen.provider, db.transcripts(), GroupAssistant.CLOUD_CHUNK_CHARS, GroupAssistant.CLOUD_MAX_CHUNKS)
                } else {
                    GroupAssistant(chosen.provider, db.transcripts())
                }
                block(assistant, if (group.kind == GroupKind.ALL) emptyList() else scopedLines(group))
            }.getOrElse { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                ChatTurn(label, "Something went wrong: ${error.message}")
            }
            AppUiState.completeTurn(group.id, turn)
        }
    }

    /** The group's lines as the assistant should read them: corrected where available. */
    private suspend fun scopedLines(group: GroupRef): List<ScopedLine> =
        Exporter.linesFor(group.fromTs, group.toTs).map { ScopedLine(it.segment, it.corrected) }

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
    val correctionEngine = settings.correctionEngine.stateIn(viewModelScope, SharingStarted.Eagerly, ModelChoice.LOCAL)
    val correctionModel = settings.correctionModel.stateIn(viewModelScope, SharingStarted.Eagerly, ModelChoice.AUTO)
    val askModel = settings.askModel.stateIn(viewModelScope, SharingStarted.Eagerly, ModelChoice.AUTO)

    fun setCorrectionEnabled(on: Boolean) = viewModelScope.launch { settings.setCorrectionEnabled(on) }
    fun setCorrectionIntervals(battery: Int, charging: Int) =
        viewModelScope.launch { settings.setCorrectionIntervals(battery, charging) }
    fun setEndOfDayEnabled(on: Boolean) = viewModelScope.launch { settings.setEndOfDayEnabled(on) }
    fun setCorrectionEngine(engine: String) = viewModelScope.launch { settings.setCorrectionEngine(engine) }
    fun setCorrectionModel(choice: String) = viewModelScope.launch { settings.setCorrectionModel(choice) }
    fun setAskModel(choice: String) = viewModelScope.launch { settings.setAskModel(choice) }
    fun setExportDefaults(content: String, format: String) =
        viewModelScope.launch { settings.setExportDefaults(content, format) }

    /** Installed local models, strongest first, as (file name, label) for the switchers. */
    fun installedModels(): List<Pair<String, String>> =
        LocalModelSelector(getApplication()).allCandidates().filter { it.exists }.map { it.fileName to it.label }

    /** What each model role would use right now, in words. */
    fun modelSummary(): String {
        val context = getApplication<Application>()
        val selector = LocalModelSelector(context)
        val strongest = selector.selectStrongest()?.label ?: "none fits right now"
        val small = selector.selectSmallModel()?.label ?: "none fits right now"
        return "Strongest local model that fits now: $strongest\n" +
            "Chat model that fits now: $small\n" +
            "RAM tier: ${DeviceCapabilities.ramTier(context)} (${DeviceCapabilities.marketedRamGb(context)} GB)"
    }

    fun correctNow() = viewModelScope.launch {
        _status.value = "Correcting new lines…"
        val count = runCatching { CorrectionRunner.runBatch(drain = true) }.getOrDefault(0)
        _status.value = if (count > 0) "Corrected $count lines" else
            "Nothing corrected" + (CorrectionRunner.lastError?.let { ": $it" } ?: " — no new lines.")
    }

    fun setRecording(enabled: Boolean) {
        val context = getApplication<Application>()
        viewModelScope.launch { settings.setRecordingEnabled(enabled) }
        if (enabled) RecordingService.start(context) else RecordingService.stop(context)
    }

    fun dismissFlag(id: Long) = viewModelScope.launch { db.flagged().dismiss(id) }

    fun saveTriggers(keywords: Set<String>) = viewModelScope.launch {
        settings.setTriggerKeywords(keywords)
    }

    fun saveProvider(id: String, endpoint: String, model: String, apiKey: String) =
        viewModelScope.launch {
            settings.setProvider(id, endpoint, model)
            if (apiKey.isNotBlank()) ServiceLocator.apiKeys.setKey(id, apiKey)
            _status.value = "Saved ${ProviderIds.label(id)}"
        }

    fun setHeavyTierEnabled(enabled: Boolean) = viewModelScope.launch {
        settings.setHeavyTierEnabled(enabled)
    }

    fun saveGoogleCredentials(clientId: String, clientSecret: String, refreshToken: String) {
        val keys = ServiceLocator.apiKeys
        if (clientId.isNotBlank()) keys.setKey(RefreshTokenGoogleAuth.KEY_CLIENT_ID, clientId)
        if (clientSecret.isNotBlank()) keys.setKey(RefreshTokenGoogleAuth.KEY_CLIENT_SECRET, clientSecret)
        if (refreshToken.isNotBlank()) keys.setKey(RefreshTokenGoogleAuth.KEY_REFRESH_TOKEN, refreshToken)
        _status.value = "Google credentials stored"
    }

    fun syncNow() {
        HeavySyncScheduler.runNow(getApplication())
        _status.value = "Heavy sync queued"
    }

    fun approveDraft(id: Long) = viewModelScope.launch {
        val result = ServiceLocator.connectorGateway.approve(id)
        _status.value = result.fold(
            onSuccess = { "Sent: $it" },
            onFailure = { "Failed: ${it.message}" },
        )
    }

    fun rejectDraft(id: Long) = viewModelScope.launch {
        ServiceLocator.connectorGateway.reject(id)
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

        viewModelScope.launch {
            val context = getApplication<Application>()
            val selector = LocalModelSelector(context)
            val spec = selector.selectSmallModel() ?: selector.selectHeavyModel()

            _benchmark.value = when {
                spec == null -> "No local model is installed, so there is nothing to measure."
                else -> {
                    val model = Runtime.load(context, spec)
                    when (model) {
                        null -> "Could not load ${spec.fileName}."
                        else -> {
                            val report = model.benchmark()
                            Runtime.unload()
                            buildString {
                                append("Model: ").append(spec.label).append('\n')
                                append("RAM tier: ").append(DeviceCapabilities.ramTier(context))
                                append(" (").append(DeviceCapabilities.marketedRamGb(context))
                                append(" GB)\n\n")
                                append(report ?: "This backend does not expose a benchmark.")
                            }
                        }
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
        /** Enough history to scroll back a little on a 4 inch screen, not enough to cost. */
        const val COVER_SEGMENT_LIMIT = 60
        const val COVER_REFRESH_MS = 1_000L
    }

    suspend fun providerSettings(): Triple<String, String, String> = Triple(
        settings.activeProvider.first(),
        settings.providerEndpoint.first(),
        settings.providerModel.first(),
    )

    /**
     * Reported in Settings so what actually shipped on this phone is visible without a
     * computer: which native runtimes are in the APK, whether their model files arrived,
     * and why a local heavy model is or isn't offered.
     */
    fun deviceSummary(): String {
        val context = getApplication<Application>()
        val selector = LocalModelSelector(context)
        val tier = DeviceCapabilities.ramTier(context)
        val ram = "%.1f".format(DeviceCapabilities.totalRamGb(context))
        val heavy = selector.heavyUnavailableReason()
            ?: "available (${selector.heavyModelCandidate()?.label})"

        val asr = when {
            !AsrEngineFactory.sherpaBundled -> "not bundled in this build"
            !AsrEngineFactory.modelsInstalled(context) -> "runtime ${AsrEngineFactory.sherpaVersion}, model not downloaded"
            else -> "runtime ${AsrEngineFactory.sherpaVersion}, model installed"
        }

        val llm = LocalModelRuntime.unavailableReason
            ?: ("llama.cpp @ ${LocalModelRuntime.commit}" +
                (LocalModelRuntime.current?.let { ", loaded: $it" } ?: ", idle"))

        return """
            Device: ${Build.MANUFACTURER} ${Build.MODEL}
            Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
            RAM ${ram} GB · tier $tier
            App ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})

            Speech recognition: $asr
            Local AI runtime: $llm
            Local heavy model: $heavy

            Displays (read this open, then closed, to learn this phone's cover display):
            ${CoverDisplays.describeAll(context)}
        """.trimIndent()
    }
}
