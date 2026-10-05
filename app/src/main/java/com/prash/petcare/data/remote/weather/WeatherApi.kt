package com.prash.petcare.data.remote.weather

import com.google.gson.annotations.SerializedName
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

/** The Open-Meteo forecast endpoint, as a Kotlin interface. */
interface WeatherApi {

    /** The temperature right now at one point on the map. */
    @GET("v1/forecast")
    suspend fun currentWeather(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("current") current: String = CURRENT_TEMPERATURE
    ): WeatherResponse

    companion object {

        /** The host only. Retrofit requires a base URL ending in `/`. */
        private const val BASE_URL = "https://api.open-meteo.com/"

        /** Air temperature two metres above ground: the standard height weather is measured at. */
        const val CURRENT_TEMPERATURE = "temperature_2m"

        /** Builds the one instance the app uses. */
        fun create(): WeatherApi = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(WeatherApi::class.java)
    }
}

/** The reply to [WeatherApi.currentWeather]. */
data class WeatherResponse(
    @SerializedName("current") val current: CurrentWeather?
)

/** The measurements inside the reply's `current` block. */
data class CurrentWeather(
    @SerializedName("temperature_2m") val temperatureCelsius: Double?
)
