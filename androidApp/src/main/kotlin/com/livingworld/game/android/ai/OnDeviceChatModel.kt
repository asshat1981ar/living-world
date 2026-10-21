package com.livingworld.game.android.ai

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.livingworld.ai.dialogue.ChatModel
import java.io.File

/** How long the on-device model took: loading it (once per session) and the most recent reply. */
data class ModelTimings(val loadMillis: Long? = null, val lastReplyMillis: Long? = null)

/**
 * A [ChatModel] that runs in [AiModelService]'s separate process. If that process dies (out of memory, native crash),
 * the waiting line fails and [onCrash] is told, so the game can turn the AI off instead of crashing again.
 */
class OnDeviceChatModel(
    private val context: Context,
    private val modelFile: File,
    private val onCrash: () -> Unit
) : ChatModel {
    private val mutex = Mutex()
    private var service: Messenger? = null
    private var connecting: CompletableDeferred<Messenger>? = null
    private var pending: CompletableDeferred<Bundle>? = null
    private var nextId = 0

    private val _timings = MutableStateFlow(ModelTimings())
    val timings: StateFlow<ModelTimings> = _timings.asStateFlow()

    private val replies = Messenger(Handler(Looper.getMainLooper()) { msg ->
        if (msg.what == AiModelService.MSG_RESULT && msg.arg1 == nextId) pending?.complete(msg.data)
        true
    })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val messenger = Messenger(binder)
            service = messenger
            connecting?.complete(messenger)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            // Only called when the AI process died, not on a normal unbind.
            service = null
            connecting = null
            pending?.completeExceptionally(ModelProcessDied())
            onCrash()
        }
    }

    override suspend fun complete(system: String?, user: String, temperature: Float, maxTokens: Int): Result<String> =
        mutex.withLock {
            try {
                if (!modelFile.isFile) return@withLock Result.failure(ModelMissing())
                val messenger = withTimeoutOrNull(BIND_TIMEOUT_MS) { bound() } ?: return@withLock Result.failure(ModelProcessDied())
                val reply = CompletableDeferred<Bundle>().also { pending = it }
                val request = Bundle().apply {
                    putString(AiModelService.KEY_MODEL_PATH, modelFile.absolutePath)
                    putString(AiModelService.KEY_SYSTEM, system)
                    putString(AiModelService.KEY_USER, user)
                    putFloat(AiModelService.KEY_TEMPERATURE, temperature)
                    putInt(AiModelService.KEY_MAX_TOKENS, maxTokens)
                }
                messenger.send(Message.obtain(null, AiModelService.MSG_GENERATE, ++nextId, 0).apply {
                    data = request
                    replyTo = replies
                })
                val data = withTimeoutOrNull(REPLY_TIMEOUT_MS) { reply.await() } ?: return@withLock Result.failure(ModelTooSlow())
                data.getString(AiModelService.KEY_ERROR)?.let { return@withLock Result.failure(ModelError(it)) }
                val load = data.getLong(AiModelService.KEY_LOAD_MS)
                _timings.value = ModelTimings(
                    loadMillis = if (load > 0) load else _timings.value.loadMillis,
                    lastReplyMillis = data.getLong(AiModelService.KEY_GEN_MS)
                )
                Result.success(data.getString(AiModelService.KEY_TEXT).orEmpty())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                pending = null
            }
        }

    override fun describeFailure(error: Throwable): String = when (error) {
        is ModelMissing -> "The on-device model is not downloaded yet."
        is ModelProcessDied -> "The on-device AI stopped unexpectedly."
        is ModelTooSlow -> "The on-device AI took too long to answer."
        is ModelError -> "The on-device model could not run on this phone."
        else -> "The on-device AI failed."
    }

    /** Frees the model's memory: the service (and its process) ends once nothing is bound to it. */
    fun release() {
        if (service != null || connecting != null) {
            runCatching { context.unbindService(connection) }
        }
        service = null
        connecting = null
    }

    private suspend fun bound(): Messenger {
        service?.let { return it }
        val waiting = connecting ?: CompletableDeferred<Messenger>().also {
            connecting = it
            context.bindService(Intent(context, AiModelService::class.java), connection, Context.BIND_AUTO_CREATE)
        }
        return waiting.await()
    }

    private class ModelMissing : Exception("model file missing")
    private class ModelProcessDied : Exception("AI process died")
    private class ModelTooSlow : Exception("no reply in time")
    private class ModelError(message: String) : Exception(message)

    private companion object {
        const val BIND_TIMEOUT_MS = 10_000L
        /** Prepared lines are never waited on by the player, so this only bounds a stuck model. */
        const val REPLY_TIMEOUT_MS = 120_000L
    }
}
