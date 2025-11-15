package com.symbioza.daymind.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

class ExternalChunkStore(private val context: Context) {
    fun save(source: File, createdAt: Long): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveScoped(source, createdAt)
        } else {
            saveLegacy(source, createdAt)
        }
    }

    private fun saveScoped(source: File, createdAt: Long): String? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, source.name)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/DayMind")
            put(MediaStore.Audio.Media.DATE_ADDED, createdAt / 1000)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri: Uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null
        resolver.openOutputStream(uri)?.use { output ->
            source.inputStream().use { input ->
                input.copyTo(output)
            }
        }
        values.clear()
        values.put(MediaStore.Audio.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri.toString()
    }

    private fun saveLegacy(source: File, createdAt: Long): String? {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            "DayMind"
        ).apply { mkdirs() }
        val target = File(dir, source.name)
        source.copyTo(target, overwrite = true)
        return target.absolutePath
    }
}
