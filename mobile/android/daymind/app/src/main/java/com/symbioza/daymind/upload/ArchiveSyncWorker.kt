package com.symbioza.daymind.upload

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.symbioza.daymind.DayMindApplication
import com.symbioza.daymind.audio.ArchiveBuilder
import java.io.File
import java.time.Instant
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory

class ArchiveSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as DayMindApplication
        val container = app.container
        val pending = container.chunkRepository.pendingChunks()
        if (pending.isEmpty()) {
            container.syncStatusStore.markSuccess("Nothing to sync")
            return Result.success()
        }

        container.syncStatusStore.markInProgress("Building archive...")
        val archiveDir = container.chunkRepository.archiveDirectory()
        val builder = ArchiveBuilder(applicationContext)
        val buildResult = runCatching { builder.buildArchive(pending, archiveDir) }
            .onFailure { container.syncStatusStore.markError("Archive build failed: ${it.message}") }
            .getOrNull() ?: return Result.failure()

        container.syncStatusStore.setArchivePath(buildResult.shareableFile.absolutePath)

        val baseUrl = container.configRepository.getServerUrl().ensureTrailingSlash()
        val apiKey = container.configRepository.getApiKey()
        if (baseUrl.isBlank() || apiKey.isBlank()) {
            container.syncStatusStore.markError("Missing BASE_URL/API_KEY")
            return Result.failure()
        }

        container.syncStatusStore.markInProgress("Uploading archive...")
        val api = createApi(baseUrl, apiKey)
        val archivePart = MultipartBody.Part.createFormData(
            "archive",
            buildResult.archiveFile.name,
            buildResult.archiveFile.asRequestBody("audio/flac".toMediaType())
        )
        val manifestPart = buildResult.manifestFile.readText()
            .toRequestBody("application/json".toMediaType())

        val response = runCatching {
            api.uploadArchive(
                archive = archivePart,
                manifest = manifestPart
            )
        }.getOrElse {
            container.syncStatusStore.markError(it.message ?: "Upload error")
            return Result.retry()
        }

        return if (response.isSuccessful) {
            container.chunkRepository.markUploaded(pending.map { it.id })
            container.syncStatusStore.markSuccess("Synced ${pending.size} chunks", buildResult.shareableFile.absolutePath)
            Result.success()
        } else {
            container.syncStatusStore.markError("Sync failed ${response.code()}")
            Result.retry()
        }
    }

    private fun createApi(baseUrl: String, apiKey: String): ArchiveTranscriptionApi {
        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(
                okhttp3.OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        val request = chain.request().newBuilder()
                            .addHeader("X-API-Key", apiKey)
                            .build()
                        chain.proceed(request)
                    }
                    .build()
            )
            .addConverterFactory(ScalarsConverterFactory.create())
            .build()
        return retrofit.create(ArchiveTranscriptionApi::class.java)
    }

    companion object {
        private const val UNIQUE_NAME = "daymind-archive-sync"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<ArchiveSyncWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        private fun String.ensureTrailingSlash(): String = if (endsWith('/')) this else "$this/"
    }
}
