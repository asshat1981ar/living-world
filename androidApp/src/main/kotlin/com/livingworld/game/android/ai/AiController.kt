package com.livingworld.game.android.ai

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.livingworld.game.android.ui.dialogue.DialogueViewModel
import com.livingworld.game.android.ui.dialogue.LineUsage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.livingworld.ai.dialogue.DialogueStats
import org.livingworld.ai.dialogue.GroundedDialogueVoice
import org.livingworld.ai.dialogue.PregeneratedLines
import org.livingworld.ai.ondevice.ModelAssetCatalog
import org.livingworld.ai.ondevice.ModelAssetDescriptor
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Everything the AI tab shows. */
data class AiUiState(
    val eligibility: AiEligibility,
    val enabled: Boolean,
    val model: ModelAssetDescriptor,
    val installed: Boolean,
    val downloadProgress: Float? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val autoDisabledReason: String? = null,
    val stats: DialogueStats? = null,
    val timings: ModelTimings = ModelTimings(),
    val usage: LineUsage = LineUsage()
)

/**
 * The on-device AI's switchboard: off until the player opts in, only on phones [AiEligibility] allows, with a model that
 * is downloaded once and verified. The model runs in its own process; a crash there turns the AI off with a notice.
 */
class AiController(context: Context, private val dialogue: DialogueViewModel, private val scope: CoroutineScope) {
    private val app = context.applicationContext
    private val settings = AiSettingsRepository(SharedPreferencesStore(app.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)))
    private val downloader = ModelDownloader(File(app.filesDir, "models"), open = ::openHttps)
    private val eligibility: AiEligibility = (app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).let { am ->
        val memory = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        AiEligibility.decide(Build.SUPPORTED_64_BIT_ABIS.isNotEmpty(), am.isLowRamDevice, memory.totalMem)
    }
    private var model: OnDeviceChatModel? = null
    private var lines: PregeneratedLines? = null
    private var download: Job? = null

    private val _state = MutableStateFlow(snapshot())
    val state: StateFlow<AiUiState> = _state.asStateFlow()

    /** Models this phone may use: the large one only where [AiEligibility] allows it. */
    val choices: List<ModelAssetDescriptor>
        get() = if ((eligibility as? AiEligibility.Eligible)?.highQualityAllowed == true) ModelAssetCatalog.ALL_ASSETS
        else listOf(ModelAssetCatalog.DEFAULT)

    init {
        if (settings.enabled) attach()
        scope.launch { dialogue.voiceStats.collect { refresh() } }
        scope.launch { dialogue.lineUsage.collect { refresh() } }
    }

    fun setEnabled(on: Boolean) {
        if (on) {
            settings.enable()
            attach()
        } else {
            settings.disable()
            detach()
        }
        refresh()
    }

    fun chooseModel(choice: ModelAssetDescriptor) {
        if (choice !in choices) return
        detach()
        settings.chooseModel(choice)
        if (settings.enabled) attach()
        refresh()
    }

    fun download() {
        if (download?.isActive == true) return
        val wanted = settings.model
        download = scope.launch {
            refresh(downloadProgress = 0f)
            val result = downloader.download(wanted) { done, total -> refresh(downloadProgress = done.toFloat() / total) }
            if (settings.enabled) attach()
            refresh(message = result.fold({ "Downloaded and verified." }, { "Download failed: ${it.message}" }))
        }
    }

    fun cancelDownload() {
        download?.cancel()
        refresh(message = "Download cancelled.")
    }

    fun deleteModel() {
        detach()
        downloader.delete(settings.model)
        refresh(message = "Model deleted.")
    }

    /** One short reply, timed, so the player can see whether this phone is fast enough before relying on it. */
    fun runSpeedTest() {
        if (!downloader.isInstalled(settings.model) || eligibility !is AiEligibility.Eligible) return
        scope.launch {
            refresh(busy = true, message = "Testing speed... the first run also loads the model.")
            val tester = model ?: OnDeviceChatModel(app, downloader.fileFor(settings.model)) { onCrash() }
            val reply = tester.complete(
                system = "You are a village herbalist in a fantasy game. Answer in one short sentence.",
                user = "Greet a traveler who walks into your shop.",
                temperature = 0.7f,
                maxTokens = 40
            )
            if (tester !== model) tester.release()
            val timings = tester.timings.value
            val message = reply.fold(
                {
                    val replyMs = timings.lastReplyMillis ?: 0L
                    val load = timings.loadMillis?.let { " Loading the model took ${seconds(it)}." } ?: ""
                    when (SpeedVerdict.of(replyMs)) {
                        SpeedVerdict.GOOD -> "A reply took ${seconds(replyMs)}: good.$load"
                        SpeedVerdict.SLOW -> "A reply took ${seconds(replyMs)}: usable, but many lines will not be ready in time.$load"
                        SpeedVerdict.TOO_SLOW -> "A reply took ${seconds(replyMs)}: too slow on this phone; keeping the AI off is better.$load"
                    }
                },
                { tester.describeFailure(it) }
            )
            refresh(busy = false, message = message)
        }
    }

    /** The game left the screen: free the model's memory (it reloads on the next line). */
    fun onStop() {
        model?.release()
    }

    private fun attach() {
        if (model != null || eligibility !is AiEligibility.Eligible || !downloader.isInstalled(settings.model)) return
        val m = OnDeviceChatModel(app, downloader.fileFor(settings.model)) { onCrash() }
        model = m
        lines = PregeneratedLines(GroundedDialogueVoice(m)).also(dialogue::setLines)
        scope.launch { m.timings.collect { refresh() } }
    }

    private fun detach() {
        model?.release()
        model = null
        lines = null
        dialogue.setLines(null)
    }

    private fun onCrash() {
        settings.recordCrash()
        detach()
        refresh()
    }

    private fun snapshot(downloadProgress: Float? = null, busy: Boolean = false, message: String? = null) = AiUiState(
        eligibility = eligibility,
        enabled = settings.enabled,
        model = settings.model,
        installed = downloader.isInstalled(settings.model),
        downloadProgress = downloadProgress,
        busy = busy,
        message = message,
        autoDisabledReason = settings.autoDisabledReason,
        stats = lines?.stats,
        timings = model?.timings?.value ?: ModelTimings(),
        usage = dialogue.lineUsage.value
    )

    private fun refresh(
        downloadProgress: Float? = _state.value.downloadProgress.takeIf { download?.isActive == true },
        busy: Boolean = _state.value.busy,
        message: String? = _state.value.message
    ) {
        _state.value = snapshot(downloadProgress, busy, message)
    }

    private fun seconds(ms: Long) = "%.1f s".format(ms / 1000.0)

    private fun openHttps(url: String): InputStream {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 60_000
        connection.instanceFollowRedirects = true
        val status = connection.responseCode
        if (status !in 200..299) throw IllegalStateException("Hugging Face returned HTTP $status")
        return connection.inputStream
    }
}

private class SharedPreferencesStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
}
