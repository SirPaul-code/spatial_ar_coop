package com.sirpaul.spatialarcoop.util

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class FileLogger(context: Context) {
    private val directory = File(context.filesDir, "logs").apply { mkdirs() }
    private val lock = ReentrantLock()
    private val maxBytes = 5L * 1024L * 1024L

    fun debug(message: String, fields: Map<String, Any?> = emptyMap()) = write("DEBUG", message, fields)
    fun info(message: String, fields: Map<String, Any?> = emptyMap()) = write("INFO", message, fields)
    fun warn(message: String, fields: Map<String, Any?> = emptyMap()) = write("WARN", message, fields)
    fun error(message: String, throwable: Throwable? = null, fields: Map<String, Any?> = emptyMap()) =
        write("ERROR", message, fields + ("error" to throwable?.stackTraceToString()), throwable)

    private fun write(
        level: String,
        message: String,
        fields: Map<String, Any?>,
        throwable: Throwable? = null
    ) {
        val line = runCatching {
            val entry = JSONObject()
                .put("ts", Instant.now().toString())
                .put("level", level)
                .put("message", message)
            fields.forEach { (key, value) -> entry.put(key, value ?: JSONObject.NULL) }
            entry.toString() + "\n"
        }.getOrElse { serializationError ->
            "{\"ts\":\"${Instant.now()}\",\"level\":\"ERROR\",\"message\":\"log serialization failed: ${serializationError.javaClass.simpleName}\"}\n"
        }
        runCatching {
            lock.withLock {
                rotateIfNeeded()
                FileOutputStream(File(directory, "spatial-current.jsonl"), true)
                    .bufferedWriter()
                    .use { it.write(line) }
            }
        }.onFailure { storageError ->
            Log.e(TAG, "Could not persist structured app log", storageError)
        }
        when (level) {
            "ERROR" -> Log.e(TAG, message, throwable)
            "WARN" -> Log.w(TAG, message)
            "DEBUG" -> Log.d(TAG, message)
            else -> Log.i(TAG, message)
        }
    }

    private fun rotateIfNeeded() {
        val current = File(directory, "spatial-current.jsonl")
        if (!current.exists() || current.length() < maxBytes) return
        for (index in 4 downTo 1) {
            val source = File(directory, "spatial-$index.jsonl")
            if (source.exists()) source.renameTo(File(directory, "spatial-${index + 1}.jsonl"))
        }
        current.renameTo(File(directory, "spatial-1.jsonl"))
        File(directory, "spatial-5.jsonl").takeIf(File::exists)?.delete()
    }

    fun files(): List<File> = runCatching {
        directory.listFiles()?.filter(File::isFile)?.sortedByDescending(File::lastModified) ?: emptyList()
    }.getOrDefault(emptyList())

    companion object { private const val TAG = "SpatialArCoop" }
}
