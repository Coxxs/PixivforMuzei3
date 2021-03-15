package com.antony.muzei.pixiv.util

import okhttp3.OkHttpClient
import okhttp3.Protocol
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

interface CloudFlareDNSService {

    @GET("/dns-query")
    fun query(
            @Query("name") name: String?,
            @Query("ct") ct: String = "application/dns-json",
            @Query("type") type: String = "A"
    ): Call<CloudFlareDNSResponse>

    companion object {
        operator fun invoke(): CloudFlareDNSService {
            return Retrofit.Builder()
                    .baseUrl("https://1.0.0.1/")
                    .addConverterFactory(GsonConverterFactory.create())
                    .client(
                            OkHttpClient.Builder()
                                    .protocols(listOf(Protocol.HTTP_1_1))
                                    .build()
                    )
                    .build()
                    .create(CloudFlareDNSService::class.java)
        }
    }
}
