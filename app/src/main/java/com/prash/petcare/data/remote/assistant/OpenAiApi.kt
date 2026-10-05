package com.prash.petcare.data.remote.assistant

import com.google.gson.annotations.SerializedName
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

/** OpenAI's chat completions endpoint, as a Kotlin interface. */
interface OpenAiApi {

    /** Sends a conversation and returns the model's reply. */
    @POST("v1/chat/completions")
    suspend fun chat(
        @Header("Authorization") authorization: String,
        @Body request: ChatRequest
    ): ChatResponse

    companion object {

        /** Retrofit requires a base URL ending in `/`; the path lives on the method that uses it. */
        private const val BASE_URL = "https://api.openai.com/"

        /** Builds the one instance the app uses. */
        fun create(): OpenAiApi = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(OpenAiApi::class.java)
    }
}

/** One turn in the request body. */
data class ChatMessage(
    @SerializedName("role") val role: String,
    @SerializedName("content") val content: String
)

/** The request body. */
data class ChatRequest(
    @SerializedName("model") val model: String,
    @SerializedName("messages") val messages: List<ChatMessage>,
    @SerializedName("temperature") val temperature: Double = 0.2,
    @SerializedName("max_tokens") val maxTokens: Int = 400
)

/** The reply. */
data class ChatResponse(
    @SerializedName("choices") val choices: List<ChatChoice>?
)

data class ChatChoice(
    @SerializedName("message") val message: ChatMessage?
)
