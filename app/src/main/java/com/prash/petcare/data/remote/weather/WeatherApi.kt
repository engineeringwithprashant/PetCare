package com.prash.petcare.data.remote.weather

import com.google.gson.annotations.SerializedName
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * The Open-Meteo forecast endpoint, as a Kotlin interface.
 *
 * This is the **remote data source** for the home screen's walk-window temperature, and it sits at
 * the same depth in the architecture as a Room DAO: something that can fetch rows and nothing else.
 * It is never called from a ViewModel or a fragment — [com.prash.petcare.data.repository.WeatherRepository]
 * is the only caller, exactly as the DAOs are only ever reached through their repositories. That is
 * what keeps the "where does this come from" question answerable in one place when the app later
 * caches, retries or falls back.
 *
 * **Why Retrofit writes the request rather than this file.** The annotations below are a description
 * of a URL, not code that builds one: Retrofit generates the implementation at runtime, appending
 * `v1/forecast` to the base URL and encoding each `@Query` into the query string. So a call to
 * [currentWeather] becomes
 * `https://api.open-meteo.com/v1/forecast?latitude=27.7172&longitude=85.324&current=temperature_2m`,
 * and no part of the app ever concatenates a URL by hand.
 *
 * **No API key, and nothing personal sent.** Open-Meteo is free for non-commercial use and needs no
 * key, which is why the app can query it with no credential stored anywhere. The only thing that
 * leaves the device is a coordinate pair, and only to ask what the weather is there.
 */
interface WeatherApi {

    /**
     * The temperature right now at one point on the map.
     *
     * A `suspend` function, which Retrofit supports directly: it performs the call on its own
     * background thread and resumes the coroutine with the parsed body, so the repository can await
     * it as ordinary sequential code and never touches a thread itself.
     *
     * It **throws** rather than returning a null on failure — an `IOException` when the device is
     * offline or the request times out, an `HttpException` for a 4xx/5xx reply. Catching those and
     * turning them into a state the screen can draw is the repository's job, not this layer's.
     *
     * @param latitude Degrees north, negative for south.
     * @param longitude Degrees east, negative for west.
     * @param current Which "right now" measurements to return. Defaulted to the only one this app
     *   asks for; the API can return several, comma-separated, and naming just the one keeps the
     *   reply small.
     */
    @GET("v1/forecast")
    suspend fun currentWeather(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("current") current: String = CURRENT_TEMPERATURE
    ): WeatherResponse

    companion object {

        /**
         * The host only. Retrofit requires a base URL ending in `/`, and appends the path from the
         * [GET] annotation to it — so the path lives with the method that uses it rather than here.
         */
        private const val BASE_URL = "https://api.open-meteo.com/"

        /** Air temperature two metres above ground: the standard height weather is measured at. */
        const val CURRENT_TEMPERATURE = "temperature_2m"

        /**
         * Builds the one instance the app uses.
         *
         * The Gson converter factory is what turns the JSON body into [WeatherResponse]; without a
         * converter Retrofit can only hand back an unparsed response body. Creating a Retrofit
         * instance is not cheap (it sets up an OkHttp client with its own connection pool and
         * thread pool underneath), which is why this is called once from PetCareApplication and the
         * result shared, rather than per request.
         */
        fun create(): WeatherApi = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(WeatherApi::class.java)
    }
}

/**
 * The reply to [WeatherApi.currentWeather].
 *
 * A dedicated class rather than reusing anything from the app's own model, because it describes
 * *Open-Meteo's* JSON and must be free to change with that API without dragging the rest of the app
 * with it. The repository reads the one number it wants out of this and hands on a plain `Double`,
 * so nothing above the data layer ever sees a network shape.
 *
 * **Every field is nullable on purpose.** Gson builds these objects by reflection and will happily
 * leave a field null if the key is missing from the reply — including a field Kotlin has declared
 * non-null, which then throws a `NullPointerException` at some unrelated later line. Declaring what
 * the network genuinely might not send as nullable moves that from a crash to a value the repository
 * already knows how to handle.
 *
 * @property current The "current" block, holding one value per measurement asked for.
 */
data class WeatherResponse(
    @SerializedName("current") val current: CurrentWeather?
)

/**
 * The measurements inside the reply's `current` block.
 *
 * [SerializedName] is doing real work here: the JSON key is `temperature_2m`, which is not a name
 * worth carrying through the app, so the annotation lets the property be called something readable
 * while still matching the wire format.
 *
 * @property temperatureCelsius Degrees Celsius — the API's default unit, so no unit parameter is
 *   sent with the request.
 */
data class CurrentWeather(
    @SerializedName("temperature_2m") val temperatureCelsius: Double?
)
