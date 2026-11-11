package ch.beachscore.app.ui.records

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import ch.beachscore.app.R
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Hands a CSV to the Android share sheet: written to cache/export (replacing older exports) and
 * shared read-only through the app's FileProvider. No storage permission needed.
 */
object CsvShare {
    fun share(context: Context, csv: String) {
        val dir = File(context.cacheDir, "export").apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }
        val name = "beachscore-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".csv"
        val file = File(dir, name).apply { writeText(csv, Charsets.UTF_8) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/csv")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(name, uri)
        context.startActivity(Intent.createChooser(send, context.getString(R.string.share_csv_title)))
    }
}
