package com.prash.petcare.data.remote.assistant

import com.google.gson.annotations.SerializedName
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

/**
 * OpenAI's chat completions endpoint, as a Kotlin interface.
 *
 * The app's second HTTP data source after the weather forecast, and written the same way: a Retrofit
 * interface describing the request, called only from a repository. Nothing above the data layer
 * knows this file exists.
 *
 * **Why the key is a parameter rather than baked into a client.** It arrives from BuildConfig at
 * runtime and may legitimately be empty, which the repository has to notice *before* making a
 * request so it can say so. Passing it per call keeps that decision at the call site instead of
 * hiding an unusable client behind a lazy initialiser.
 */
interface OpenAiApi {

    /**
     * Sends a conversation and returns the model's reply.
     *
     * A `suspend` function, which Retrofit supports directly: it performs the call on its own
     * background thread and resumes the coroutine with the parsed body.
     *
     * It **throws** rather than returning a failure — an `IOException` when the device is offline,
     * an `HttpException` for a 4xx/5xx, a `JsonSyntaxException` on an unexpected body. Turning those
     * into something the screen can draw is the repository's job, not this layer's.
     *
     * @param authorization The `Bearer <key>` header, supplied per call.
     */
    @POST("v1/chat/completions")
    suspend fun chat(
        @Header("Authorization") authorization: String,
        @Body request: ChatRequest
    ): ChatResponse

    companion object {

        /** Retrofit requires a base URL ending in `/`; the path lives on the method that uses it. */
        private const val BASE_URL = "https://api.openai.com/"

        /**
         * Builds the one instance the app uses.
         *
         * Creating a Retrofit instance sets up an OkHttp client with its own connection and thread
         * pools, so this is called once from PetCareApplication and the result shared rather than
         * built per request — as WeatherApi.create does for the same reason.
         */
        fun create(): OpenAiApi = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(OpenAiApi::class.java)
    }
}

/**
 * One turn in the request body.
 *
 * `role` is OpenAI's vocabulary — "system" for the instructions that constrain the assistant, "user"
 * for what the person typed, "assistant" for what came back before. Kept as a plain string because
 * it is a wire value; the app's own conversation type is [com.prash.petcare.data.assistant.AssistantTurn].
 */
data class ChatMessage(
    @SerializedName("role") val role: String,
    @SerializedName("content") val content: String
)

/**
 * The request body.
 *
 * @property temperature Low, not zero. This assistant reports facts from the user's own records, so
 *   invention is the failure mode to guard against; a little above zero keeps the phrasing natural
 *   without letting it stray from the figures it was given.
 * @property maxTokens A ceiling on the reply, which is a cost control as much as a formatting one —
 *   answers on this screen are a few sentences in a chat bubble, not essays.
 */
data class ChatRequest(
    @SerializedName("model") val model: String,
    @SerializedName("messages") val messages: List<ChatMessage>,
    @SerializedName("temperature") val temperature: Double = 0.2,
    @SerializedName("max_tokens") val maxTokens: Int = 400
)

/**
 * The reply.
 *
 * **Every field is nullable on purpose.** Gson builds these by reflection and will leave a field
 * null if the key is missing — including one Kotlin declared non-null, which then throws a
 * `NullPointerException` at some unrelated later line. Declaring what the network might genuinely
 * not send as nullable turns that into a value the repository already handles as
 * [com.prash.petcare.data.assistant.AssistantFailure.BAD_RESPONSE].
 */
data class ChatResponse(
    @SerializedName("choices") val choices: List<ChatChoice>?
)

data class ChatChoice(
    @SerializedName("message") val message: ChatMessage?
)
