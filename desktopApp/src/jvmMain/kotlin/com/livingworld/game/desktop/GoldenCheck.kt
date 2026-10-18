package com.livingworld.game.desktop

import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Compares a run's canonical dump with a checked-in golden (a .txt.gz file under tests/golden). The Python kernel mirror is held to the same
 * goldens, so a Kotlin behaviour change that is not re-recorded here is caught before the mirror can silently fall behind.
 */
object GoldenCheck {
    fun compare(path: String, actual: List<String>): String {
        val file = listOf(File(path), File("..", path)).firstOrNull { it.isFile }
            ?: return "MISSING golden file $path"
        val expected = GZIPInputStream(file.inputStream()).bufferedReader().readLines()
        for (i in 0 until maxOf(expected.size, actual.size)) {
            val e = expected.getOrNull(i) ?: "<missing>"
            val a = actual.getOrNull(i) ?: "<missing>"
            if (e != a) return "MISMATCH at line ${i + 1}: expected [$e] actual [$a]"
        }
        return "OK (${expected.size} lines)"
    }
}
