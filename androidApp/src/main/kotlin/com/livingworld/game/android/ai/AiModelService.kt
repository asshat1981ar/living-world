package com.livingworld.game.android.ai

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.os.SystemClock
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig

/**
 * Runs the on-device model in its own process (`:ai` in the manifest), so an out-of-memory kill or a native crash in
 * LiteRT-LM costs one AI line, never the game. Requests are handled one at a time on a background thread; the engine
 * is loaded on the first request and released when the game unbinds (it does so whenever it leaves the screen).
 */
class AiModelService : Service() {
    private lateinit var worker: HandlerThread
    private lateinit var messenger: Messenger
    private var engine: Engine? = null
    private var loadedPath: String? = null

    override fun onCreate() {
        super.onCreate()
        worker = HandlerThread("ai-model").apply { start() }
        messenger = Messenger(object : Handler(worker.looper) {
            override fun handleMessage(msg: Message) {
                if (msg.what == MSG_GENERATE) generate(msg)
            }
        })
    }

    override fun onBind(intent: Intent): IBinder = messenger.binder

    override fun onDestroy() {
        engine?.close()
        engine = null
        worker.quitSafely()
        super.onDestroy()
    }

    private fun generate(msg: Message) {
        val replyTo = msg.replyTo ?: return
        val request = msg.data
        val out = Bundle()
        try {
            val path = requireNotNull(request.getString(KEY_MODEL_PATH)) { "no model path" }
            var loadMs = 0L
            if (engine == null || loadedPath != path) {
                engine?.close()
                val start = SystemClock.elapsedRealtime()
                // CPU on purpose: GPU delegates on budget chips are a common source of hard crashes and garbage output.
                engine = Engine(EngineConfig(modelPath = path, backend = Backend.CPU(), cacheDir = cacheDir.path)).also { it.initialize() }
                loadedPath = path
                loadMs = SystemClock.elapsedRealtime() - start
            }
            val config = ConversationConfig(
                systemInstruction = request.getString(KEY_SYSTEM)?.let { Contents.of(it) },
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = request.getFloat(KEY_TEMPERATURE).toDouble()),
                maxOutputToken = request.getInt(KEY_MAX_TOKENS),
                // Qwen3 would otherwise spend the whole token budget thinking before it says anything.
                thinkingConfig = ThinkingConfig(enableThinking = false)
            )
            val start = SystemClock.elapsedRealtime()
            val text = engine!!.createConversation(config).use { it.sendMessage(requireNotNull(request.getString(KEY_USER))).toString() }
            out.putString(KEY_TEXT, text)
            out.putLong(KEY_LOAD_MS, loadMs)
            out.putLong(KEY_GEN_MS, SystemClock.elapsedRealtime() - start)
        } catch (e: Throwable) {
            out.putString(KEY_ERROR, e.javaClass.simpleName + ": " + (e.message ?: ""))
        }
        try {
            replyTo.send(Message.obtain(null, MSG_RESULT, msg.arg1, 0).apply { data = out })
        } catch (_: RemoteException) {
            // The game went away; nothing to tell.
        }
    }

    companion object {
        const val MSG_GENERATE = 1
        const val MSG_RESULT = 2
        const val KEY_MODEL_PATH = "modelPath"
        const val KEY_SYSTEM = "system"
        const val KEY_USER = "user"
        const val KEY_TEMPERATURE = "temperature"
        const val KEY_MAX_TOKENS = "maxTokens"
        const val KEY_TEXT = "text"
        const val KEY_ERROR = "error"
        const val KEY_LOAD_MS = "loadMs"
        const val KEY_GEN_MS = "genMs"
    }
}
