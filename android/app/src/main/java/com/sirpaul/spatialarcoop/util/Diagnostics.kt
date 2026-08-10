package com.sirpaul.spatialarcoop.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object Diagnostics {
    fun shareLogs(context: Context, logger: FileLogger) {
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val zip = File(exportDir, "spatial-ar-coop-logs-${System.currentTimeMillis()}.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { output ->
            logger.files().forEach { file ->
                output.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { it.copyTo(output) }
                output.closeEntry()
            }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", zip)
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Share diagnostic logs"))
    }
}
