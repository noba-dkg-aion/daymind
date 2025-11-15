package com.symbioza.daymind.data

import com.symbioza.daymind.api.SummaryApi
import com.symbioza.daymind.config.ConfigRepository
import okhttp3.OkHttpClient
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory

data class SummaryPayload(
    val date: String,
    val markdown: String
)

class SummaryRepository(private val configRepository: ConfigRepository) {
    private var cachedBaseUrl: String? = null
    private var cachedApiKey: String? = null
    private var summaryApi: SummaryApi? = null

    suspend fun fetchSummary(date: String, force: Boolean): SummaryPayload {
        val baseUrl = configRepository.getServerUrl().ensureTrailingSlash()
        val apiKey = configRepository.getApiKey()
        if (baseUrl.isBlank() || apiKey.isBlank()) {
            throw IllegalStateException("Configure BASE_URL and API_KEY in settings")
        }
        val api = getApi(baseUrl, apiKey)
        val response = api.getSummary(date = date, force = force)
        if (!response.isSuccessful) {
            val code = response.code()
            val message = when (code) {
                404 -> "No summary entries for $date yet"
                else -> "Summary request failed ($code)"
            }
            throw IllegalStateException(message)
        }
        val body = response.body()?.string().orEmpty()
        if (body.isBlank()) {
            throw IllegalStateException("Summary response was empty")
        }
        val json = JSONObject(body)
        val markdown = json.optString("summary_md").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Server returned no summary text")
        val payloadDate = json.optString("date", date)
        return SummaryPayload(
            date = payloadDate,
            markdown = markdown.trim()
        )
    }

    private fun getApi(baseUrl: String, apiKey: String): SummaryApi {
        if (summaryApi != null && baseUrl == cachedBaseUrl && apiKey == cachedApiKey) {
            return summaryApi!!
        }
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("X-API-Key", apiKey)
                    .build()
                chain.proceed(request)
            }
            .build()
        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(ScalarsConverterFactory.create())
            .build()
        cachedBaseUrl = baseUrl
        cachedApiKey = apiKey
        summaryApi = retrofit.create(SummaryApi::class.java)
        return summaryApi!!
    }

    private fun String.ensureTrailingSlash(): String = if (endsWith('/')) this else "$this/"
}
