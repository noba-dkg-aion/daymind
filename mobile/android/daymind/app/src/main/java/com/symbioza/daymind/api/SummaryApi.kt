package com.symbioza.daymind.api

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

interface SummaryApi {
    @GET("v1/summary")
    suspend fun getSummary(
        @Query("date") date: String,
        @Query("force") force: Boolean = false
    ): Response<ResponseBody>
}
