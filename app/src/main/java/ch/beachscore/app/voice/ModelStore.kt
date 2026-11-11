package ch.beachscore.app.voice

import android.content.Context
import ch.beachscore.app.score.Language
import java.io.File
import java.io.IOException

/**
 * Vosk needs its model as plain files. The models ship as APK assets (`vosk/<lang>/…`, see
 * `prepareVoskModels` in the build) and are copied to internal storage on first use, with
 * progress. A `.complete` marker makes an interrupted copy start over instead of loading a
 * half-written model. Older copies of the same language are deleted after an app update brings
 * a new model version.
 */
class ModelStore(context: Context) {
    private val assets = context.applicationContext.assets
    private val root = File(context.applicationContext.filesDir, "vosk")

    /** Returns the model directory, copying it first if needed. Blocking; call off the main thread. */
    @Throws(IOException::class)
    fun ensure(language: Language, onProgress: (Float) -> Unit = {}): File {
        val base = "vosk/${language.tag}"
        val manifest = assets.open("$base/manifest.txt").bufferedReader().use { it.readLines() }
        val modelName = manifest.first().trim()
        val files = manifest.drop(1).filter { it.isNotBlank() }.map { line ->
            val (path, size) = line.split('\t')
            path to size.toLong()
        }
        val target = File(root, modelName)
        val marker = File(target, ".complete")
        if (marker.exists()) return target

        target.deleteRecursively()
        val total = files.sumOf { it.second }.coerceAtLeast(1)
        var copied = 0L
        onProgress(0f)
        val buffer = ByteArray(1 shl 16)
        for ((path, _) in files) {
            val out = File(target, path)
            out.parentFile?.mkdirs()
            assets.open("$base/$path").use { input ->
                out.outputStream().use { output ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        copied += n
                        onProgress(copied.toFloat() / total)
                    }
                }
            }
        }
        marker.writeText(modelName)
        root.listFiles()?.forEach { other ->
            if (other != target && other.name.startsWith(modelName.substringBeforeLast('-'))) other.deleteRecursively()
        }
        onProgress(1f)
        return target
    }
}
