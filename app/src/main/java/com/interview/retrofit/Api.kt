package com.interview.retrofit

import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Path

const val GITHUB_API: String = "https://api.github.com"

data class Contributor(val login: String, val contributions: Int)

interface GitHub {
    @GET("/repos/{owner}/{repo}/contributors")
    fun contributorsCall(
        @Path("owner") owner: String, // square
        @Path("repo") repo: String, // retrofit
    ): Call<List<Contributor>>

    suspend fun contributors(
        @Path("owner") owner: String, // square
        @Path("repo") repo: String, // retrofit
    ): List<Contributor>
}