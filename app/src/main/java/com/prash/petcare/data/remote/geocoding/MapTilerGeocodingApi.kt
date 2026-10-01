package com.prash.petcare.data.remote.geocoding

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * MapTiler's geocoding endpoint, as a Kotlin interface.
 *
 * This is a **remote data source**, at the same depth in the architecture as a Room DAO: it can fetch
 * one reply and nothing else. No ViewModel or fragment ever names it —
 * [com.prash.petcare.data.location.GeocodingRepository] is its only caller, exactly as the DAOs are
 * only ever reached through their repositories. Deciding *which* of the names in a reply to show is
 * that repository's job, not this layer's.
 *
 * **Why MapTiler rather than the platform geocoder alone.** The app's maps are already MapTiler's
 * (see [com.prash.petcare.ui.place.MapStyles]), so the name under the pin now comes from the same
 * database that drew the label the user is looking at — but the real reason is that `android.location.Geocoder`
 * answers only with an administrative address. Asked about the middle of a hospital it returns the
 * city, because a hospital is not an address; it is a *point of interest*, and the platform API has no
 * way to ask for one. MapTiler does: see [POI_TYPE].
 *
 * **One method, two very different questions**, told apart entirely by their arguments:
 *
 *  - `reverseGeocode(position, key, limit = 5, types = "poi")` — *what named places are near here?*
 *    MapTiler's reverse geocoding switches to a nearest-neighbour search when `limit` is greater than
 *    one, returning that many of the closest features **of a single requested type** within about a
 *    kilometre. That is the whole mechanism this feature rests on, and it has two conditions worth
 *    spelling out: POI results are **not** returned unless `types` asks for them explicitly, and a
 *    `limit` above one is only accepted alongside exactly one `types` value.
 *  - `reverseGeocode(position, key, limit = 1)` — *what is the address here?* No `types`, so MapTiler
 *    answers with the administrative feature containing the point and the broader ones around it in
 *    `context` — street, neighbourhood, city, region, country.
 *
 * Both are needed on every lookup: a kilometre is a long way, so a nearby POI is only the answer when
 * it turns out to be metres away, and the address is what the card shows underneath it either way.
 *
 * ### And the question in the other direction
 *
 * [forwardGeocode] takes text and returns places — the search field above the map. It is the same
 * endpoint with the coordinate replaced by a query string, and the same lesson applies twice over:
 * asked for "Patan Hosp" with its default types, MapTiler returns streets called "Hospital Road" in
 * Bihar, Slovakia and Vermont, and **not** Patan Hospital. Asked with [SEARCH_TYPES], Patan Hospital
 * is the first result. A search field for finding a vet is useless without that one parameter.
 */
interface MapTilerGeocodingApi {

    /**
     * The features at or near one coordinate.
     *
     * A `suspend` function, which Retrofit implements itself: the call is made on its own background
     * thread and the coroutine resumes with the parsed body, so the repository awaits it as ordinary
     * sequential code and never touches a thread.
     *
     * It **throws** rather than returning null — `IOException` when the device is offline,
     * `HttpException` for a 4xx/5xx reply (a missing or rejected key arrives as a 403). Turning those
     * into "this point has no name" is the repository's job.
     *
     * @param position `"longitude,latitude"` — **longitude first**, which is the GeoJSON order and the
     *   opposite of how the rest of this app writes a coordinate. Built by
     *   [com.prash.petcare.data.location.GeocodingRepository] rather than by any caller, so there is
     *   one place for that order to be got right. `encoded = true` keeps Retrofit from percent-encoding
     *   the comma that separates the two numbers, which is part of the path's structure rather than
     *   data inside a segment.
     * @param key The MapTiler key from `local.properties` — see app/build.gradle.kts.
     * @param limit How many features to return, 1 to 10. Above one this becomes a nearest-neighbour
     *   search and requires exactly one [types] value.
     * @param types A single feature type to restrict the answer to, or null for MapTiler's default set
     *   of administrative features. Null is omitted from the query string entirely rather than sent
     *   empty.
     * @param language An ISO 639-1 code, so names come back in the user's own language where MapTiler
     *   has them. Null when the device's locale has no two-letter code to send.
     */
    @GET("geocoding/{position}.json")
    suspend fun reverseGeocode(
        @Path(value = "position", encoded = true) position: String,
        @Query("key") key: String,
        @Query("limit") limit: Int,
        @Query("types") types: String? = null,
        @Query("language") language: String? = null
    ): MapTilerGeocodingResponse

    /**
     * The places matching some typed text, best match first.
     *
     * The other direction of the same endpoint: a query string where [reverseGeocode] puts a
     * coordinate. Throws on failure for the same reasons and leaves the handling to the repository.
     *
     * @param query What the user typed. Retrofit percent-encodes it into the path — spaces, accents
     *   and even a slash — so nothing here has to escape anything. It must not be blank, which would
     *   ask for `geocoding/.json`; the repository is what declines to call for an empty field.
     * @param key The MapTiler key from `local.properties`.
     * @param limit How many suggestions to offer, 1 to 10.
     * @param types Which kinds of place may be returned. Always [SEARCH_TYPES] in this app, and the
     *   single parameter the search field lives or dies by — see the class comment.
     * @param autocomplete True to match on partial words, so results appear while the user is still
     *   typing rather than only once a word is finished. "Patan Hosp" finding Patan Hospital *is*
     *   this flag.
     * @param proximity `"longitude,latitude"` to rank nearby results higher, or null for no
     *   preference. The world has a great many animal hospitals and the user wants the one down the
     *   road, so this is what makes a short query useful.
     * @param language An ISO 639-1 code, or null to let MapTiler choose.
     */
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

        /**
         * The host only. Retrofit requires a base URL ending in `/` and appends the path from the
         * [GET] annotation to it, so the path lives with the method that uses it.
         */
        private const val BASE_URL = "https://api.maptiler.com/"

        /**
         * The `types` value that asks for points of interest — hospitals, clinics, shops, parks.
         *
         * Named here because it is the single most important string in this feature: without it
         * MapTiler returns only administrative features, which is exactly why a pin dropped on Patan
         * Hospital used to be reported as "Lalitpur".
         */
        const val POI_TYPE = "poi"

        /**
         * The feature types the search field asks for, with `poi` first and deliberately.
         *
         * **Why the list is explicit rather than left to the API's default.** Measured against the
         * live service: `"Patan Hosp"` with no `types` returns five address features — "Hospital Road,
         * Bihar", "Hospodárska, Slovakia", "Hospital Drive, Vermont" — and Patan Hospital is not among
         * them at any limit. With this list it is the first result. Searching for a vet by name is the
         * whole purpose of the field, so the default configuration is not merely suboptimal here, it
         * is unusable.
         *
         * **Why the other six are here too.** A person searching this field is not always after a
         * business: "Jawalakhel" and "Pulchowk" are neighbourhoods, and someone saving a park they
         * walk in may well look for the area rather than a name on a sign. Each type earns its place
         * by answering a query a user would really type — and the list stays short, because `region`
         * and `country` are too coarse to be a place a pet is taken to.
         *
         * Unlike a reverse lookup, a forward one puts no restriction on combining several types with a
         * limit above one; that condition applies only to the nearest-neighbour search.
         */
        const val SEARCH_TYPES =
            "poi,address,street,neighbourhood,locality,place,municipality"

        /**
         * Builds the one instance the app uses.
         *
         * The Gson converter is what turns the GeoJSON body into [MapTilerGeocodingResponse]. A
         * Retrofit instance carries an OkHttp client with its own connection and thread pools, so this
         * is called once from `PetCareApplication` and shared rather than built per request — the same
         * arrangement as [com.prash.petcare.data.remote.weather.WeatherApi].
         */
        fun create(): MapTilerGeocodingApi = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(MapTilerGeocodingApi::class.java)
    }
}
