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

/** The interactive map used everywhere in the Places feature. */
class PlaceMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr), DefaultLifecycleObserver {

    /** Called when the user taps the credit line under the map, which leads to the copyright page for the tiles. */
    var onExternalLink: ((String) -> Unit)? = null

    /** Where the map came to rest after the user moved it, reported once per gesture. */
    var onCentreChangedByUser: ((Coordinates) -> Unit)? = null

    /** The map is up and drawing, or could not be loaded at all. */
    var onReadyStateChanged: ((Boolean) -> Unit)? = null

    /** Which layer the map draws with. */
    var mapType: MapType = MapType.NORMAL
        set(value) {
            if (field == value) return
            field = value
            applyStyle()
        }

    /** The two-state view of [mapType], kept for the Places tab's map. */
    var isSatellite: Boolean
        get() = mapType.isImagery
        set(value) {
            mapType = if (value) MapType.SATELLITE else MapType.NORMAL
        }

    /** A dot drawn on the map at a fixed point, or null for no dot. */
    var markerAt: Coordinates? = null
        set(value) {
            if (field == value) return
            field = value
            applyMarker()
        }

    /** Where the map is now, and how far in. */
    private var currentCentre: Coordinates? = null
    private var currentZoom: Int = DEFAULT_ZOOM

    /** The last centre and zoom [showLocation] was asked for. */
    private var requestedCentre: Coordinates? = null
    private var requestedZoom: Int = 0

    /** The map object, available once the SDK has finished building it. */
    private var map: MapLibreMap? = null

    /** True once a style has finished loading, so layers can be added to it. */
    private var isStyleReady = false

    /** Whether the movement that is about to end was made by a finger. */
    private var isGestureMove = false

    /** True once [destroy] has run, so a callback arriving late cannot touch a dead renderer. */
    private var isDestroyed = false

    /** The lifecycle being observed, held only so it can be let go of again. */
    private var observedLifecycle: Lifecycle? = null

    private val binding: ViewPlaceMapBinding

    init {
        // Loads the native renderer and must happen before a MapView is constructed.
        MapLibre.getInstance(context)
        binding = ViewPlaceMapBinding.inflate(LayoutInflater.from(context), this)

        binding.attributionText.setOnClickListener {
            onExternalLink?.invoke(MapStyles.ATTRIBUTION_URL)
        }
        applyAttributionInset(attrs)

        binding.mapView.onCreate(null)
        binding.mapView.getMapAsync(::onMapReady)
        // Reported before anything is drawn when there is no key.
        binding.mapView.addOnDidFailLoadingMapListener { onReadyStateChanged?.invoke(false) }
    }

    // Set-up.

    /** Lifts the credit line clear of whatever the screen has put in the same corner. */
    private fun applyAttributionInset(attrs: AttributeSet?) {
        val inset = context.obtainStyledAttributes(attrs, R.styleable.PlaceMapView).use {
            it.getDimensionPixelSize(R.styleable.PlaceMapView_attributionBottomInset, 0)
        }
        if (inset == 0) return

        val params = binding.attributionText.layoutParams as LayoutParams
        params.bottomMargin += inset
        binding.attributionText.layoutParams = params
    }

    /** The SDK has built the map: configure it, wire its camera and load a style into it. */
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

        // The camera is placed before the style is asked.
        currentCentre?.let { ready.moveCamera(cameraUpdate(it, currentZoom)) }
        applyStyle()
    }

    /** Loads the style for the current layer and theme. */
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

    // Moving the map.

    /** Centres the map on a point, doing nothing when that is already what was asked for. */
    fun showLocation(at: Coordinates, zoom: Int) {
        if (at == requestedCentre && zoom == requestedZoom) return

        requestedCentre = at
        requestedZoom = zoom
        moveCameraTo(at, zoom, animate = false)
    }

    /** Moves the map to a point, whether or not it has been there before. */
    fun moveCameraTo(at: Coordinates, zoom: Int, animate: Boolean = true) {
        currentCentre = at
        currentZoom = zoom

        // Before the map exists there is nothing to move.
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

    /** The map has come to rest. */
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

    // The marker.

    /** Puts the marker where [markerAt] says, or takes it away when that is null. */
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

    /** The marker's fill, taken from the theme. */
    private fun markerColour(): Int =
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)

    /** True when the app is drawing its dark theme — the same test the rest of the app uses. */
    private fun isNightMode(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES

    // Touch, theme and lifecycle.

    /** Lets the map keep a drag that started on it. */
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> parent?.requestDisallowInterceptTouchEvent(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                parent?.requestDisallowInterceptTouchEvent(false)
        }
        return super.onInterceptTouchEvent(event)
    }

    /** Re-styles after a theme change, keeping the camera where it is. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyStyle()
    }

    /** Starts following the screen's lifecycle. */
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

    /** Releases the renderer. */
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

        /** How long the camera takes to fly to a point the user asked for. */
        const val CAMERA_FLIGHT_MILLIS = 700

        /** Where a map starts before any screen has said otherwise: neighbourhood level. */
        const val DEFAULT_ZOOM = 15
    }
}
