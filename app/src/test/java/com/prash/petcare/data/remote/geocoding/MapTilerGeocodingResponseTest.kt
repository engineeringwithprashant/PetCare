package com.prash.petcare.data.remote.geocoding

import com.prash.petcare.util.Coordinates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Unit tests for the two decisions a MapTiler reply is read for: is there a named place under the pin? */
class MapTilerGeocodingResponseTest {

    // Patan Hospital's gate, give or take — the coordinate standing in for the centre pin throughout.
    private val pin = Coordinates(latitude = 27.6681, longitude = 85.3157)

    // nearestPoiNameWithin: only a POI that is genuinely there.

    @Test
    fun `a POI on the pin is named`() {
        val response = response(poi("Patan Hospital", pin))

        assertEquals("Patan Hospital", response.nearestPoiNameWithin(pin, maxMetres = 40.0))
    }

    @Test
    fun `a POI a street away is not named`() {
        // MapTiler's nearest-neighbour search reaches about a kilometre, so this is an ordinary reply.
        val response = response(poi("Patan Hospital", pin.movedNorthBy(metres = 300.0)))

        assertNull(response.nearestPoiNameWithin(pin, maxMetres = 40.0))
    }

    @Test
    fun `the closest POI wins rather than the first one returned`() {
        // MapTiler ranks by prominence as well as distance.
        val response = response(
            poi("Patan Hospital", pin.movedNorthBy(metres = 120.0)),
            poi("Lagankhel Pet Supplies", pin.movedNorthBy(metres = 15.0))
        )

        assertEquals(
            "Lagankhel Pet Supplies",
            response.nearestPoiNameWithin(pin, maxMetres = 40.0)
        )
    }

    @Test
    fun `the threshold is the boundary it claims to be`() {
        val justInside = response(poi("Patan Hospital", pin.movedNorthBy(metres = 35.0)))
        val justOutside = response(poi("Patan Hospital", pin.movedNorthBy(metres = 45.0)))

        assertEquals("Patan Hospital", justInside.nearestPoiNameWithin(pin, maxMetres = 40.0))
        assertNull(justOutside.nearestPoiNameWithin(pin, maxMetres = 40.0))
    }

    @Test
    fun `a POI with no coordinates is ignored rather than guessed at`() {
        // Unmeasurable, so it cannot be known to be the place under the pin.
        val response = response(
            GeocodedFeature(id = "poi.1", text = "Patan Hospital", placeTypes = listOf("poi"))
        )

        assertNull(response.nearestPoiNameWithin(pin, maxMetres = 40.0))
    }

    @Test
    fun `the geometry is read when there is no centre`() {
        val response = response(
            GeocodedFeature(
                id = "poi.1",
                text = "Patan Hospital",
                placeTypes = listOf("poi"),
                geometry = GeocodedGeometry(coordinates = listOf(pin.longitude, pin.latitude))
            )
        )

        assertEquals("Patan Hospital", response.nearestPoiNameWithin(pin, maxMetres = 40.0))
    }

    @Test
    fun `an unnamed POI is not treated as a name`() {
        // Observed in real replies: MapTiler returns unnamed features — a bench, a gate, an unlabelled building.
        val response = response(
            poi("-", pin),
            poi("Lagankhel Pet Supplies", pin.movedNorthBy(metres = 20.0))
        )

        assertEquals(
            "Lagankhel Pet Supplies",
            response.nearestPoiNameWithin(pin, maxMetres = 40.0)
        )
    }

    @Test
    fun `a reply of nothing but unnamed POIs is a miss`() {
        val response = response(poi("-", pin), poi("  ", pin))

        assertNull(response.nearestPoiNameWithin(pin, maxMetres = 40.0))
    }

    @Test
    fun `a reply with no features at all is a miss, not a crash`() {
        assertNull(MapTilerGeocodingResponse().nearestPoiNameWithin(pin, maxMetres = 40.0))
        assertNull(response().nearestPoiNameWithin(pin, maxMetres = 40.0))
    }

    @Test
    fun `longitude and latitude are not transposed`() {
        // The GeoJSON order is [longitude, latitude], the reverse of this app's own.
        val response = response(
            GeocodedFeature(
                id = "poi.1",
                text = "Patan Hospital",
                placeTypes = listOf("poi"),
                center = listOf(85.3157, 27.6681)
            )
        )

        assertEquals("Patan Hospital", response.nearestPoiNameWithin(pin, maxMetres = 40.0))
    }

    // placeNamesByScale: the address chain, narrowest first.

    @Test
    fun `names come back narrowest first whatever order they arrived in`() {
        val response = response(
            GeocodedFeature(
                id = "municipality.1",
                text = "Lalitpur",
                placeTypes = listOf("municipality"),
                context = listOf(
                    GeocodedContext(id = "country.1", text = "Nepal"),
                    GeocodedContext(id = "region.1", text = "Bagmati"),
                    GeocodedContext(id = "neighbourhood.1", text = "Lagankhel")
                )
            )
        )

        assertEquals(
            listOf("Lagankhel", "Lalitpur", "Bagmati", "Nepal"),
            response.placeNamesByScale()
        )
    }

    @Test
    fun `a street address outranks the street it is on`() {
        val response = response(
            GeocodedFeature(
                id = "address.1",
                text = "Pulchowk Road 12",
                placeTypes = listOf("address"),
                context = listOf(
                    GeocodedContext(id = "street.1", text = "Pulchowk Road"),
                    GeocodedContext(id = "locality.1", text = "Pulchowk")
                )
            )
        )

        assertEquals(
            listOf("Pulchowk Road 12", "Pulchowk Road", "Pulchowk"),
            response.placeNamesByScale()
        )
    }

    @Test
    fun `a postcode is never a name for a place`() {
        val response = response(
            GeocodedFeature(
                id = "postal_code.1",
                text = "44700",
                placeTypes = listOf("postal_code"),
                context = listOf(GeocodedContext(id = "municipality.1", text = "Lalitpur"))
            )
        )

        assertEquals(listOf("Lalitpur"), response.placeNamesByScale())
    }

    @Test
    fun `the POI headline is not repeated underneath itself`() {
        // The address reply can carry the same word as the matched POI — a clinic that is also an addressed building.
        val response = response(
            GeocodedFeature(
                id = "address.1",
                text = "Patan Hospital",
                placeTypes = listOf("address"),
                context = listOf(GeocodedContext(id = "municipality.1", text = "Lalitpur"))
            )
        )

        assertEquals(
            listOf("Lalitpur"),
            response.placeNamesByScale(excluding = "patan hospital")
        )
    }

    @Test
    fun `the same word arriving twice is shown once`() {
        // A city that is also its own district, which would otherwise read "Lalitpur, Lalitpur".
        val response = response(
            GeocodedFeature(
                id = "municipality.1",
                text = "Lalitpur",
                placeTypes = listOf("municipality"),
                context = listOf(GeocodedContext(id = "district.1", text = "Lalitpur"))
            )
        )

        assertEquals(listOf("Lalitpur"), response.placeNamesByScale())
    }

    @Test
    fun `a real Lalitpur reply reads as an address a person would recognise`() {
        // Copied field for field from a live reverse lookup, which is the point of the test.
        val response = response(
            GeocodedFeature(
                id = "address.12101683",
                text = "Man bhawan Marga",
                placeName = "Man bhawan Marga, 44600 Lalitpur Metropolitan City, Nepal",
                placeTypes = listOf("address"),
                context = listOf(
                    GeocodedContext(id = "postal_code.486214", text = "44600"),
                    GeocodedContext(id = "place.4573321", text = "Jawalakhel Chowk Lalitpur"),
                    GeocodedContext(id = "municipal_district.122413", text = "Lalitpur-05"),
                    GeocodedContext(id = "municipality.161342", text = "Lalitpur Metropolitan City"),
                    GeocodedContext(id = "county.7691", text = "Lalitpur"),
                    GeocodedContext(id = "region.1999", text = "Bagamati Province"),
                    GeocodedContext(id = "country.84", text = "Nepal"),
                    GeocodedContext(id = "major_landform.4162304", text = "Himalayas"),
                    GeocodedContext(id = "continental_marine.5", text = "Asia")
                )
            )
        )

        val names = response.placeNamesByScale()

        // Narrowest first, the postcode and the continent-scale features gone.
        assertEquals(
            listOf(
                "Man bhawan Marga",
                "Jawalakhel Chowk Lalitpur",
                "Lalitpur Metropolitan City",
                "Lalitpur-05",
                "Lalitpur",
                "Bagamati Province",
                "Nepal"
            ),
            names
        )
        // Which is what the card ends up showing: the street, then the two lines of context under it.
        assertEquals("Man bhawan Marga", names.first())
        assertEquals(
            "Jawalakhel Chowk Lalitpur, Lalitpur Metropolitan City",
            names.drop(1).take(2).joinToString(", ")
        )
    }

    @Test
    fun `an unrecognised kind is kept but sorts last`() {
        // Forward compatibility.
        val response = response(
            GeocodedFeature(
                id = "something_new.1",
                text = "Zone 4",
                placeTypes = listOf("something_new"),
                context = listOf(GeocodedContext(id = "neighbourhood.1", text = "Lagankhel"))
            )
        )

        assertEquals(listOf("Lagankhel", "Zone 4"), response.placeNamesByScale())
    }

    @Test
    fun `blank names are dropped`() {
        val response = response(
            GeocodedFeature(
                id = "neighbourhood.1",
                text = "   ",
                placeTypes = listOf("neighbourhood"),
                context = listOf(GeocodedContext(id = "municipality.1", text = "Lalitpur"))
            )
        )

        assertEquals(listOf("Lalitpur"), response.placeNamesByScale())
    }

    // GeocodedFeature.namesByScale: one search result's own address.

    @Test
    fun `a search result is described from its own context`() {
        // Copied from a live forward search for "Patan Hosp".
        val feature = GeocodedFeature(
            id = "poi.9372216",
            text = "Patan Hospital",
            placeName = "Patan Hospital, Nepal",
            placeTypes = listOf("poi"),
            center = listOf(85.32033866115523, 27.668521328348483),
            context = listOf(
                GeocodedContext(id = "address.1741882", text = "Kumaripati First lane Marga"),
                GeocodedContext(id = "postal_code.486214", text = "44600"),
                GeocodedContext(id = "place.1257600", text = "Kumaripati"),
                GeocodedContext(id = "municipal_district.122413", text = "Lalitpur-05"),
                GeocodedContext(id = "municipality.161342", text = "Lalitpur Metropolitan City"),
                GeocodedContext(id = "country.84", text = "Nepal"),
                GeocodedContext(id = "continental_marine.5", text = "Asia")
            )
        )

        val names = feature.namesByScale(excluding = "Patan Hospital")

        // The POI's own name is not in the address chain, the postcode and the continent are gone.
        assertEquals(
            listOf(
                "Kumaripati First lane Marga",
                "Kumaripati",
                "Lalitpur Metropolitan City",
                "Lalitpur-05",
                "Nepal"
            ),
            names
        )
    }

    @Test
    fun `a search result that is its own narrowest name does not repeat it`() {
        // "Jawalakhel" searched for and returned as a neighbourhood.
        val feature = GeocodedFeature(
            id = "place.4573321",
            text = "Jawalakhel",
            placeTypes = listOf("place"),
            center = listOf(85.3122, 27.6727),
            context = listOf(
                GeocodedContext(id = "municipality.161342", text = "Lalitpur Metropolitan City"),
                GeocodedContext(id = "country.84", text = "Nepal")
            )
        )

        assertEquals(
            listOf("Lalitpur Metropolitan City", "Nepal"),
            feature.namesByScale(excluding = "Jawalakhel")
        )
    }

    @Test
    fun `one result's context cannot leak into another's`() {
        // The guard against describing search rows from the whole reply: these two features are unrelated places.
        val nepal = GeocodedFeature(
            id = "poi.1",
            text = "Animal Hospital",
            placeTypes = listOf("poi"),
            center = listOf(85.31, 27.67),
            context = listOf(GeocodedContext(id = "municipality.1", text = "Lalitpur"))
        )
        val thailand = GeocodedFeature(
            id = "poi.2",
            text = "animal hospital",
            placeTypes = listOf("poi"),
            center = listOf(100.5, 13.75),
            context = listOf(GeocodedContext(id = "municipality.2", text = "Bangkok"))
        )

        assertEquals(listOf("Lalitpur"), nepal.namesByScale(excluding = nepal.text))
        assertEquals(listOf("Bangkok"), thailand.namesByScale(excluding = thailand.text))
    }

    @Test
    fun `a result with no context has no second line`() {
        val feature = GeocodedFeature(
            id = "poi.1",
            text = "Central Zoo",
            placeTypes = listOf("poi"),
            center = listOf(85.3109, 27.6725)
        )

        assertEquals(emptyList<String>(), feature.namesByScale(excluding = "Central Zoo"))
    }

    // fullPlaceName: the last resort.

    @Test
    fun `the full place name is available when nothing could be classified`() {
        val response = response(
            GeocodedFeature(id = null, text = null, placeName = "Lagankhel, Lalitpur, Nepal")
        )

        assertEquals("Lagankhel, Lalitpur, Nepal", response.fullPlaceName())
    }

    // Builders.

    private fun response(vararg features: GeocodedFeature) =
        MapTilerGeocodingResponse(features = features.toList())

    /** A POI feature as MapTiler sends one: `place_type` of "poi" and a `center` in GeoJSON order. */
    private fun poi(name: String, at: Coordinates) = GeocodedFeature(
        id = "poi.${name.hashCode()}",
        text = name,
        placeName = name,
        placeTypes = listOf("poi"),
        center = listOf(at.longitude, at.latitude)
    )

    /** A point a given number of metres due north of this one. */
    private fun Coordinates.movedNorthBy(metres: Double) =
        copy(latitude = latitude + metres / METRES_PER_DEGREE_LATITUDE)

    private companion object {

        /** 2πR / 360 for the Earth's mean radius: about 111.2 km per degree of latitude. */
        const val METRES_PER_DEGREE_LATITUDE = 111_195.0
    }
}
