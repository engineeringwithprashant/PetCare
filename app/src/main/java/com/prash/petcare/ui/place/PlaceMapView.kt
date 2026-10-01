package com.prash.petcare.ui.place

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.core.content.res.use
import androidx.lifecycle.findViewTreeLifecycleOwner
import com.google.android.material.color.MaterialColors
import com.prash.petcare.R
import com.prash.petcare.databinding.ViewPlaceMapBinding
import com.prash.petcare.util.Coordinates
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Point
import kotlin.math.roundToInt

/**
 * The interactive map used everywhere in the Places feature.
 *
 * In MVVM this is part of the **View** layer and nothing more: it is told where to look and what to
 * mark, and it reports where the user moved it to. It reads no state, holds no ViewModel and decides
 * nothing — [PlacesFragment] and [LocationPickerView] do that.
 *
 * ### Why MapLibre Native, after two previous answers
 *
 * The history is worth keeping, because each step was forced by a real limitation of the one before.
 *
 * **First there was Google's keyless `output=embed` map in a WebView iframe.** It looked exactly
 * right — it *was* Google Maps — but it was a cross-origin frame, so the app could tell it where to
 * centre and could never ask it where the user had panned to. "Drag to choose a point" therefore had
 * to be faked by swallowing the touch events, sliding the whole WebView with the finger and
 * converting the measured pixels back into a coordinate. That is what produced the blank strips when
 * panning and the pale border when zooming out: the map was a *picture* being dragged around inside
 * its card, and no new tiles were fetched until the fingers came off.
 *
 * **Then it was Leaflet over raster OpenStreetMap tiles**, still in the WebView. That fixed the
 * behaviour completely — the map panned, zoomed and fetched tiles like a map — but raster tiles are
 * pre-drawn images at whole zoom levels, so the result looked like a printed map from a decade ago:
 * zoom stepped rather than glided, labels were baked into the picture, and the cartography was
 * OpenStreetMap's own utilitarian style.
 *
 * **This is the third and last answer**, and it is the one that needed no compromise. MapLibre Native
 * is a real Android `View` that renders *vector* tiles on the GPU, so the map behaves and looks the
 * way people now expect one to:
 *
 *  - zoom is continuous rather than stepped, and rotation is available, because the renderer draws
 *    every frame from the data rather than assembling it out of fixed images;
 *  - labels stay upright and re-flow as the map moves, and buildings, landmarks and place names are
 *    drawn from the data — which is what makes it legible at the scale this app cares about;
 *  - the camera can simply be *read* ([MapLibreMap.getCameraPosition]), and the SDK reports *why* it
 *    moved, so a fix the app flew to is never mistaken for a point the user chose;
 *  - changing layer is a style swap that leaves the camera untouched;
 *  - dark mode is a real dark cartographic style rather than a light map put through a colour
 *    inversion, which is why the CSS filter the web versions needed is gone.
 *
 * The WebView is gone with it, and with the WebView went the JavaScript bridge: there is no
 * `addJavascriptInterface` in the app any more, and nothing loaded over the network can call into it.
 *
 * See [MapStyles] for where the tiles and the cartography come from, and why the credit line is drawn
 * on the map rather than hidden behind a button.
 *
 * ### Lifecycle
 *
 * A `MapView` holds a GPU surface and a native renderer, and it must be told about every lifecycle
 * transition or it leaks one and stops drawing after the other. Rather than making every screen
 * remember to forward eight callbacks, this view finds its own `LifecycleOwner` from the view tree
 * and observes it — so a fragment only has to put one of these in its layout. [destroy] is still
 * public and still called explicitly by both fragments, because being torn down exactly once is
 * worth being sure of; calling it twice is harmless.
 */
class PlaceMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr), DefaultLifecycleObserver {

    /**
     * Called when the user taps the credit line under the map, which leads to the copyright page for
     * the tiles. The fragment opens it in a browser; there is nowhere inside this small card for a
     * full web page to go.
     */
    var onExternalLink: ((String) -> Unit)? = null

    /**
     * Where the map came to rest **after the user moved it**, reported once per gesture.
     *
     * The distinction is the whole point: a camera move the *app* asked for (see [moveCameraTo]) does
     * not call this. The SDK makes that exact, because it reports the reason a camera movement began
     * — a gesture, or an animation the app started — so unlike the web versions there is no guessing
     * from coordinates and no tolerance to tune. See [PlaceLocationPicker], where which of the two it
     * was decides what happens next.
     */
    var onCentreChangedByUser: ((Coordinates) -> Unit)? = null

    /**
     * The map is up and drawing, or could not be loaded at all.
     *
     * False means either that this build has no MapTiler key ([MapStyles.isConfigured]) or that the
     * style could not be fetched — the device is offline, or the key is not valid. The screen says so
     * rather than leaving an empty card that looks like it is still loading.
     */
    var onReadyStateChanged: ((Boolean) -> Unit)? = null

    /**
     * Which layer the map draws with.
     *
     * Assigning it swaps the whole cartographic style and touches nothing else — the centre, the
     * zoom, the marker and the place the user chose all stay exactly as they were, which is the
     * behaviour the screens depend on. The guard is what keeps a re-render from doing even that much
     * needlessly.
     */
    var mapType: MapType = MapType.NORMAL
        set(value) {
            if (field == value) return
            field = value
            applyStyle()
        }

    /**
     * The two-state view of [mapType], kept for the Places tab's map, whose corner button is a toggle
     * between the drawn map and imagery rather than a three-way menu. Reading and writing the same
     * underlying value means the two screens can never hold different ideas of what the map shows.
     */
    var isSatellite: Boolean
        get() = mapType.isImagery
        set(value) {
            mapType = if (value) MapType.SATELLITE else MapType.NORMAL
        }

    /**
     * A dot drawn on the map at a fixed point, or null for no dot.
     *
     * For screens that *show* a place rather than choose one. The location picker does not use it:
     * there the pin is an Android view held in the middle of the card, because what is being marked
     * is the map's centre rather than a point on the ground. Here it is the opposite — the place
     * stays put while the map moves under it, which only a marker inside the map can do.
     *
     * Drawn as a style layer rather than with an annotation plugin: a layer is part of the map's own
     * rendering, so it is composited with the tiles on the GPU instead of being a second view kept in
     * step with the camera by hand.
     */
    var markerAt: Coordinates? = null
        set(value) {
            if (field == value) return
            field = value
            applyMarker()
        }

    /**
     * Where the map is now, and how far in.
     *
     * Updated from [showLocation], [moveCameraTo] and the camera's own idle report, so it is always
     * the map's actual position rather than the last thing anybody asked for — which is what lets the
     * style be reloaded after a theme change without losing the user's place.
     */
    private var currentCentre: Coordinates? = null
    private var currentZoom: Int = DEFAULT_ZOOM

    /**
     * The last centre and zoom [showLocation] was asked for.
     *
     * Kept apart from [currentCentre] so a re-render cannot undo the user's own panning: the Places
     * tab calls `showLocation` on every state emission — a tick of its "open now" clock is enough —
     * and comparing against where the map *happens* to be would snap it back each time.
     */
    private var requestedCentre: Coordinates? = null
    private var requestedZoom: Int = 0

    /** The map object, available once the SDK has finished building it. */
    private var map: MapLibreMap? = null

    /** True once a style has finished loading, so layers can be added to it. */
    private var isStyleReady = false

    /**
     * Whether the movement that is about to end was made by a finger.
     *
     * Recorded when the camera *starts* moving, because that is the only moment the SDK says why, and
     * read when it comes to rest — which is the moment the answer is needed.
     */
    private var isGestureMove = false

    /** True once [destroy] has run, so a callback arriving late cannot touch a dead renderer. */
    private var isDestroyed = false

    /** The lifecycle being observed, held only so it can be let go of again. */
    private var observedLifecycle: Lifecycle? = null

    private val binding: ViewPlaceMapBinding

    init {
        // Loads the native renderer and must happen before a MapView is constructed — including one
        // constructed by the layout inflater on the next line, which is why it comes first.
        MapLibre.getInstance(context)
        binding = ViewPlaceMapBinding.inflate(LayoutInflater.from(context), this)

        binding.attributionText.setOnClickListener {
            onExternalLink?.invoke(MapStyles.ATTRIBUTION_URL)
        }
        applyAttributionInset(attrs)

        binding.mapView.onCreate(null)
        binding.mapView.getMapAsync(::onMapReady)
        // Reported before anything is drawn when there is no key, so the screen can say why the card
        // is empty instead of waiting for tiles that will never be asked for.
        binding.mapView.addOnDidFailLoadingMapListener { onReadyStateChanged?.invoke(false) }
    }

    // ---------------------------------------------------------------------------------------
    // Set-up
    // ---------------------------------------------------------------------------------------

    /**
     * Lifts the credit line clear of whatever the screen has put in the same corner.
     *
     * Zero on the location picker, whose bottom-start corner is empty. The Places tab sets it,
     * because its two hand-off buttons span the foot of the card and would otherwise be drawn over
     * the credit — which would leave the map technically attributed and actually unreadable. See the
     * styleable in attrs.xml.
     */
    private fun applyAttributionInset(attrs: AttributeSet?) {
        val inset = context.obtainStyledAttributes(attrs, R.styleable.PlaceMapView).use {
            it.getDimensionPixelSize(R.styleable.PlaceMapView_attributionBottomInset, 0)
        }
        if (inset == 0) return

        val params = binding.attributionText.layoutParams as LayoutParams
        params.bottomMargin += inset
        binding.attributionText.layoutParams = params
    }

    /**
     * The SDK has built the map: configure it, wire its camera and load a style into it.
     *
     * **Which gestures are allowed, and why.** Panning and zooming are what the screens are for.
     * Rotation is left on because people expect a map to turn under two fingers, and the compass the
     * SDK shows once it is off north puts it back in one tap. Tilt is switched off: a tilted map
     * makes "the point under the centre pin" harder to judge, which is the one question this map
     * exists to answer, and it buys nothing at the scale of a doorway.
     *
     * **The SDK's own attribution button and logo are switched off** and replaced by a line drawn on
     * the map — see view_place_map.xml for why that is a requirement rather than a preference.
     */
    private fun onMapReady(ready: MapLibreMap) {
        if (isDestroyed) return
        map = ready

        ready.uiSettings.apply {
            isAttributionEnabled = false
            isLogoEnabled = false
            isTiltGesturesEnabled = false
            isRotateGesturesEnabled = true
            isCompassEnabled = true
            // Under the map-type pill rather than in the corner it would otherwise share with it.
            compassGravity = Gravity.TOP or Gravity.END
            setCompassMargins(
                0,
                resources.getDimensionPixelSize(R.dimen.map_compass_margin_top),
                resources.getDimensionPixelSize(R.dimen.gps_map_chip_inset),
                0
            )
        }

        ready.addOnCameraMoveStartedListener { reason ->
            isGestureMove = reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE
        }
        ready.addOnCameraIdleListener(::onCameraIdle)

        // The camera is placed before the style is asked for, so the first tiles fetched are the ones
        // around the place being shown rather than the ones around the map's default position.
        currentCentre?.let { ready.moveCamera(cameraUpdate(it, currentZoom)) }
        applyStyle()
    }

    /**
     * Loads the style for the current layer and theme.
     *
     * Every change of layer comes through here, and the camera is deliberately not touched: MapLibre
     * keeps its position across a style change, which is what makes switching to satellite to look at
     * a rooftop safe for a pin that has already been placed on it.
     *
     * The marker is re-added afterwards because a new style is a new set of sources and layers — the
     * old ones go with the style they were added to.
     */
    private fun applyStyle() {
        val current = map ?: return

        if (!MapStyles.isConfigured) {
            isStyleReady = false
            onReadyStateChanged?.invoke(false)
            return
        }

        isStyleReady = false
        current.setStyle(Style.Builder().fromUri(MapStyles.urlFor(mapType, isNightMode()))) {
            if (isDestroyed) return@setStyle
            isStyleReady = true
            applyMarker()
            onReadyStateChanged?.invoke(true)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Moving the map
    // ---------------------------------------------------------------------------------------

    /**
     * Centres the map on a point, doing nothing when that is already what was asked for.
     *
     * For screens that *show* a place rather than choose one — the Places tab, and the first camera
     * position a picker is given. The guard matters because those screens re-render on every state
     * emission, and moving the map each time would fight the user's own panning.
     *
     * @param zoom The slippy-map zoom scale: 16 shows a neighbourhood, 17 a street.
     */
    fun showLocation(at: Coordinates, zoom: Int) {
        if (at == requestedCentre && zoom == requestedZoom) return

        requestedCentre = at
        requestedZoom = zoom
        moveCameraTo(at, zoom, animate = false)
    }

    /**
     * Moves the map to a point, whether or not it has been there before.
     *
     * Unguarded, unlike [showLocation], because every call is one the user asked for — the opening
     * position, "I'm here", or the recentre button. A user who has panned away and presses recentre
     * is asking to be taken back to a position they were already given once, and a guard would make
     * that button do nothing.
     *
     * The move does **not** come back through [onCentreChangedByUser] — see that property.
     *
     * @param animate True to fly there, which is right when the user asked to be moved and shows them
     *   the map travelling rather than teleporting. False to arrive instantly, for the first position
     *   a screen is given, where there is nowhere to travel from.
     */
    fun moveCameraTo(at: Coordinates, zoom: Int, animate: Boolean = true) {
        currentCentre = at
        currentZoom = zoom

        // Before the map exists there is nothing to move; onMapReady places the camera from these
        // same two fields the moment it can, so nothing asked for while the SDK was starting is lost.
        val current = map ?: return

        if (animate) {
            current.animateCamera(cameraUpdate(at, zoom), CAMERA_FLIGHT_MILLIS)
        } else {
            current.moveCamera(cameraUpdate(at, zoom))
        }
    }

    private fun cameraUpdate(at: Coordinates, zoom: Int) = CameraUpdateFactory.newLatLngZoom(
        LatLng(at.latitude, at.longitude),
        zoom.toDouble()
    )

    /**
     * The map has come to rest.
     *
     * The centre is read straight off the camera rather than reconstructed, so the coordinate the app
     * saves is exactly the one the map is showing. Whether to report it outwards was decided when the
     * movement began — see [isGestureMove].
     */
    private fun onCameraIdle() {
        val position = map?.cameraPosition ?: return
        val target = position.target ?: return

        val at = Coordinates(target.latitude, target.longitude)
        currentCentre = at
        currentZoom = position.zoom.roundToInt()

        if (!isGestureMove) return
        isGestureMove = false
        onCentreChangedByUser?.invoke(at)
    }

    // ---------------------------------------------------------------------------------------
    // The marker
    // ---------------------------------------------------------------------------------------

    /**
     * Puts the marker where [markerAt] says, or takes it away when that is null.
     *
     * A ringed dot rather than a pin drawable: the ring is what keeps it visible over dark satellite
     * imagery as well as over a pale drawn map, and a circle is drawn by the renderer itself so there
     * is no image that can fail to load.
     *
     * Moving an existing marker rewrites its source's geometry rather than removing and re-adding the
     * layer, which is both cheaper and free of the flicker that rebuilding would cause.
     */
    private fun applyMarker() {
        val style = map?.style ?: return
        if (!isStyleReady) return

        val existing = style.getSourceAs<GeoJsonSource>(MARKER_SOURCE)
        val at = markerAt

        if (at == null) {
            if (existing != null) {
                style.removeLayer(MARKER_LAYER)
                style.removeSource(MARKER_SOURCE)
            }
            return
        }

        val point = Point.fromLngLat(at.longitude, at.latitude)
        if (existing != null) {
            existing.setGeoJson(point)
            return
        }

        style.addSource(GeoJsonSource(MARKER_SOURCE, point))
        style.addLayer(
            CircleLayer(MARKER_LAYER, MARKER_SOURCE).withProperties(
                PropertyFactory.circleRadius(MARKER_RADIUS),
                PropertyFactory.circleColor(markerColour()),
                PropertyFactory.circleStrokeWidth(MARKER_RING_WIDTH),
                PropertyFactory.circleStrokeColor(Color.WHITE)
            )
        )
    }

    /**
     * The marker's fill, taken from the theme.
     *
     * The map draws itself from a style document that knows nothing about the app's palette, so
     * anything the app puts *on* it has to be handed across explicitly — otherwise the dot would be
     * the one mark on the screen that did not change with the theme.
     */
    private fun markerColour(): Int =
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)

    /** True when the app is drawing its dark theme — the same test the rest of the app uses. */
    private fun isNightMode(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES

    // ---------------------------------------------------------------------------------------
    // Touch, theme and lifecycle
    // ---------------------------------------------------------------------------------------

    /**
     * Lets the map keep a drag that started on it.
     *
     * Both map cards sit inside a `NestedScrollView`, which claims any vertical drag for the page as
     * soon as it crosses the touch slop — so without this, panning the map north or south would
     * scroll the screen instead and the map would be usable in one direction only. Asking the parent
     * not to intercept for the duration of the gesture hands the whole drag to the map; the flag is
     * released on UP and CANCEL so ordinary page scrolling elsewhere is untouched.
     *
     * The event itself is not consumed — `super` is returned — because the map is what should receive
     * it.
     */
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> parent?.requestDisallowInterceptTouchEvent(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                parent?.requestDisallowInterceptTouchEvent(false)
        }
        return super.onInterceptTouchEvent(event)
    }

    /**
     * Re-styles after a theme change, keeping the camera where it is.
     *
     * Switching theme normally recreates the Activity, which builds a fresh view with the right
     * style — but a device-level change can deliver a new configuration to a view that is already
     * alive, and a daylight map left over on a dark page would be unmistakable. Unlike the web
     * versions this costs no reload of anything: the camera stays put and only the cartography
     * changes.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyStyle()
    }

    /**
     * Starts following the screen's lifecycle.
     *
     * The owner is found from the view tree rather than passed in, so a screen only has to put one of
     * these in its layout — see the class comment. Adding the observer while the owner is already
     * started replays the transitions already passed, so the map is brought up to date immediately.
     */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (observedLifecycle != null || isDestroyed) return
        observedLifecycle = findViewTreeLifecycleOwner()?.lifecycle?.also { it.addObserver(this) }
    }

    override fun onStart(owner: LifecycleOwner) {
        if (!isDestroyed) binding.mapView.onStart()
    }

    override fun onResume(owner: LifecycleOwner) {
        if (!isDestroyed) binding.mapView.onResume()
    }

    override fun onPause(owner: LifecycleOwner) {
        if (!isDestroyed) binding.mapView.onPause()
    }

    override fun onStop(owner: LifecycleOwner) {
        if (!isDestroyed) binding.mapView.onStop()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        destroy()
    }

    /**
     * Releases the renderer.
     *
     * Called from the lifecycle above *and* explicitly by both fragments' `onDestroyView`, which is
     * belt and braces on purpose: a `MapView` that is never destroyed keeps a GPU surface and a
     * native renderer alive for the life of the process. The guard makes the second call a no-op.
     */
    fun destroy() {
        if (isDestroyed) return
        isDestroyed = true

        onExternalLink = null
        onCentreChangedByUser = null
        onReadyStateChanged = null

        observedLifecycle?.removeObserver(this)
        observedLifecycle = null
        map = null
        binding.mapView.onDestroy()
    }

    private companion object {

        /** The id the marker's data and its drawing rule are registered under in the style. */
        const val MARKER_SOURCE = "petcare-place-marker-source"
        const val MARKER_LAYER = "petcare-place-marker-layer"

        /** The dot and the white ring around it, in density-independent units the renderer scales. */
        const val MARKER_RADIUS = 8f
        const val MARKER_RING_WIDTH = 3f

        /**
         * How long the camera takes to fly to a point the user asked for. Long enough to read as
         * travel — which is what tells them the map moved rather than changed — and short enough not
         * to delay the answer.
         */
        const val CAMERA_FLIGHT_MILLIS = 700

        /**
         * Where a map starts before any screen has said otherwise: neighbourhood level, which the
         * first camera move overwrites a moment later in practice.
         */
        const val DEFAULT_ZOOM = 15
    }
}
