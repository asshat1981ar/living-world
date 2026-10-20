package com.livingworld.game.android.ai

import java.io.File

/**
 * The player's Hugging Face token, entered in the app at run time and never built into the APK. It lives in the app's
 * no-backup directory (pass `context.noBackupFilesDir`), which other apps cannot read and Android does not copy into
 * cloud backups. The token is never logged.
 */
class HfTokenStore(directory: File) {
    private val file = File(directory, "hf_token")

    fun load(): String? = file.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }

    fun save(token: String) {
        file.writeText(token.trim())
    }

    fun clear() {
        file.delete()
    }
}
