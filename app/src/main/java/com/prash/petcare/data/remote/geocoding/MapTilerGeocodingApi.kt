package com.prash.petcare.data.remote.geocoding

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/** MapTiler's geocoding endpoint, as a Kotlin interface. */
interface MapTilerGeocodingApi {

    /** The features at or near one coordinate. */
    @GET("geocoding/{position}.json")
    suspend fun reverseGeocode(
        @Path(value = "position", encoded = true) position: String,
        @Query("key") key: String,
        @Query("limit") limit: Int,
        @Query("types") types: String? = null,
        @Query("language") language: String? = null
    ): MapTilerGeocodingResponse

    /** The places matching some typed text, best match first. */
    @GET("geocoding/{query}.json")
    suspend fun forwardGeocode(
        @Path("query") query: String,
        @Query("key") key: String,
        @Query("limit") limit: Int,
        @Query("types") types: String,
        @Query("autocomplete") autocomplete: Boolean,
        @Query("proximity") proximity: String? = null,
        @Query("language") language: String? = null
    ): MapTilerGeocodingResponse

    companion object {

        /** The host only. Retrofit requires a base URL ending in `/` and appends the path from the [GET] annotation. */
        private const val BASE_URL = "https://api.maptiler.com/"

        /** The `types` value that asks for points of interest — hospitals, clinics, shops, parks. */
        const val POI_TYPE = "poi"

        /** The feature types the search field asks for, with `poi` first and deliberately. */
        const val SEARCH_TYPES =
            "poi,address,street,neighbourhood,locality,place,municipality"

        /** Builds the one instance the app uses. */
        fun create(): MapTilerGeocodingApi = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(MapTilerGeocodingApi::class.java)
    }
}
