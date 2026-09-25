package com.prash.petcare.ui.place

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.os.Message
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.prash.petcare.util.Coordinates
import com.prash.petcare.util.MapLinks
import com.prash.petcare.util.WebMercator
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.log2
import kotlin.math.roundToInt

/**
 * A real, interactive Google map, embedded in a card.
 *
 * In MVVM this is part of the **View** layer and nothing more: it is told a coordinate and draws the
 * map there. It reads no state, holds no ViewModel and makes no decision about *which* place is
 * shown — [PlacesFragment] and [AddPlaceFragment] do that.
 *
 * ### Why a WebView rather than the Maps SDK
 *
 * `MapView` from the Maps SDK for Android needs an API key, a billing account behind it and a
 * `meta-data` entry in the manifest, and it adds several megabytes to the APK. The keyless
 * `output=embed` map that [MapLinks.embedUrl] builds is an ordinary web page showing the same Google
 * tiles, fully pannable and zoomable, and a `WebView` can display it with nothing to configure. For
 * coursework that keeps the map real and the project buildable by anyone who clones it. See
 * [MapLinks] for the production note on swapping it for the keyed Embed API or the SDK.
 *
 * ### Why the map is wrapped in a page of our own, rather than loaded directly
 *
 * This is the one non-obvious thing in the file, and it was found by trying the obvious thing first.
 * Pointing the WebView straight at the embed URL renders a grey panel reading **"The Google Maps
 * Embed API must be used in an iframe."** — Google serves the map only to a *framed* document and
 * refuses to be the top-level page. So [buildPage] writes a one-element HTML document whose only
 * content is an `<iframe>` filling the viewport, and the WebView loads that; the map then sits inside
 * a frame exactly as it does on a web page, and behaves.
 *
 * The wrapper earns its keep twice over, because it is also where the theme filter goes.
 *
 * ### Why the map is tinted with a CSS filter
 *
 * Google's tiles are served pre-drawn and cannot be restyled without a keyed Maps *style*, so the
 * app's dark theme is approximated by putting a CSS `filter` over the map — exactly as the design
 * specifies. The filter is written into the wrapper's own stylesheet and applied to the `<iframe>`
 * element, which is a page *we* own styling an element *we* declared; reaching inside the frame to
 * Google's document would be a cross-origin access the browser forbids.
 *
 * In dark mode the filter inverts the map and rotates the hue back 180°, which is the standard trick
 * for turning a light map dark without also turning its water orange. It is an approximation: a
 * keyed map would supply a genuine dark style, at which point [DARK_FILTER] can be deleted.
 *
 * ### Why the page is hidden until it has loaded
 *
 * A WebView paints as soon as it has content, so without [webView] starting invisible the user would
 * see a flash of white before the tiles arrived. The card's own background — the loading ground —
 * shows through in the meantime.
 *
 * ### Pick mode: choosing a point by dragging the map
 *
 * Switched on with [isPickEnabled], and used by [AddPlaceFragment] so a place can be saved somewhere
 * other than where the phone happens to be standing. Off by default, so the Places tab's map keeps
 * behaving exactly as Google's own map does.
 *
 * The obvious implementation — let the user pan Google's map and then read off where they stopped — is
 * impossible here: the map is a cross-origin iframe, so the app can tell it where to centre but can
 * never ask it where it ended up. Reading inside the frame is forbidden by the browser, and the
 * keyless embed offers no message channel back.
 *
 * So in pick mode this view **takes the gesture for itself** rather than passing it to the WebView:
 *
 *  1. [onInterceptTouchEvent] claims the whole gesture, so the page inside never sees it;
 *  2. a drag slides the WebView bodily with the finger ([View.setTranslationX]), which is what makes
 *     the map feel dragged rather than merely re-centred;
 *  3. on lift, the pixels the map moved are converted into a new centre by [WebMercator] — the app
 *     therefore *knows* the coordinate, because the app is what computed it — and the embed is
 *     re-pointed there through [onPickedCentreChanged] and [recentre].
 *
 * A pinch is handled the same way, because Google's own pinch-zoom is inside the frame this view has
 * just stopped feeding events to. It is answered in two halves: while the fingers are moving the tiles
 * already on screen are scaled and slid to follow them, and when the hand lifts the embed is asked for
 * the nearest whole zoom level. The preview half is what matters — `z=` takes a level rather than a
 * scale, so a map that only redrew on whole levels would sit perfectly still through most of a pinch
 * and look broken.
 *
 * The one thing pick mode gives up is the links inside the map — the Google logo, "View larger map" —
 * since the page receives no taps at all. On the picker that is the right trade: the screen's job is to
 * choose a point, and the two buttons over the card already lead out to the full Maps app.
 */
class MapEmbedView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /**
     * Called when the user taps a link inside the map that would navigate away from it — the Google
     * logo, "View larger map", the terms of service. The fragment opens those in a browser or the
     * Maps app; navigating this small card to a full Google page would strand the user inside it with
     * no way back.
     */
    var onExternalLink: ((String) -> Unit)? = null

    /**
     * Lets the user choose a point by dragging the map, instead of only panning Google's own.
     *
     * Off by default: the Places tab shows a map of a place that is already saved, where dragging
     * should do what dragging a map does. [AddPlaceFragment] turns it on, because there the position
     * is the thing being decided. See the class comment for why this cannot be read back out of the
     * embed and has to be measured here.
     */
    var isPickEnabled: Boolean = false

    /**
     * Where the user dropped the pin, reported once per drag or pinch — never per pixel.
     *
     * On lift rather than continuously, for two reasons: a place's coordinate is not "wherever the
     * finger is right now", and the embed has to be re-pointed at each new centre, which is a load.
     */
    var onPickedCentreChanged: ((Coordinates) -> Unit)? = null

    /**
     * True while a drag is actually under way, so the screen can show the centre crosshair only while
     * it means something — see AddPlaceFragment.renderMap. At rest the embed's own pin marks the spot,
     * and a second marker over it would read as two different answers.
     */
    var onDragStateChanged: ((Boolean) -> Unit)? = null

    /**
     * Whether the map shows Google's aerial imagery instead of its drawn map.
     *
     * A property with a setter rather than a `setSatellite()` call so the screens can simply assign
     * the state they hold and let this decide whether anything needs doing — the guard is what keeps a
     * re-render from reloading the frame on every keystroke.
     *
     * Imagery is genuinely useful on these two screens rather than decoration: a vet's entrance, a
     * park's gate and a shop front are all things a person recognises from a photograph of the roof and
     * the yard around it, where a drawn map shows only a grey block. See [MapLinks.embedUrl] for why the
     * layer chosen is *hybrid* imagery, with the street names kept.
     */
    var isSatellite: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            recentre()
        }

    /**
     * What the page currently shows. Drifts away from [requestedAt] / [requestedZoom] as the user pans
     * and pinches, which is exactly the point: those are what the *screen* asked for, these are where
     * the user has moved to since.
     */
    private var shownAt: Coordinates? = null
    private var shownZoom: Int = 0

    /**
     * The last centre and zoom [showLocation] was called with.
     *
     * Kept apart from [shownAt] / [shownZoom] so a re-render cannot undo the user's own panning. Both
     * screens call `showLocation` on every state emission — a keystroke in the name field is enough —
     * and comparing against what the page happens to show would snap a map the user had just zoomed in
     * on straight back to the zoom the fragment asks for.
     */
    private var requestedAt: Coordinates? = null
    private var requestedZoom: Int = 0

    /** True once the wrapper page has finished loading, so [recentre] can move the frame rather than
     *  reload the document. */
    private var isPageLoaded = false

    private val webView = WebView(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        // Transparent, so the card's loading ground is what shows before the tiles arrive rather
        // than the WebView's default white — which would flash bright in dark mode.
        setBackgroundColor(0x00000000)
        // The map has its own scrollbars and edge effects on the page inside it; the app's would be
        // drawn on top of them.
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        visibility = View.INVISIBLE
    }

    /**
     * How far a finger may travel before the movement counts as a drag rather than a tap.
     *
     * The platform's own figure rather than a constant of our own, so the map agrees with every other
     * scrollable thing on the device about what a deliberate drag feels like.
     */
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    /**
     * The previous position of the gesture's focus — the average of every finger on the map.
     *
     * The focus rather than one finger's coordinate, because fingers arrive and leave mid-gesture.
     * [MotionEvent.getX] reports whichever pointer happens to be at index 0, and when that is the
     * finger lifted out of a pinch the index silently moves to a finger somewhere else on the screen;
     * measured naively that shift is one enormous drag. An average of what is actually down moves
     * smoothly through the same event, and it is also what makes a two-finger pan work at all.
     */
    private var lastFocusX = 0f
    private var lastFocusY = 0f

    /** How far the map has been dragged in this gesture, in device pixels. */
    private var dragX = 0f
    private var dragY = 0f

    /**
     * How much the fingers have spread since this gesture began, as a running product.
     *
     * Accumulated rather than read per event, so a slow pinch of many small steps adds up to the same
     * thing a single fast one does — `scaleFactor` only ever reports the last increment.
     */
    private var pinchScale = 1f

    /** True once this gesture has moved or scaled enough to count as deliberate rather than a tap. */
    private var isGesturing = false

    /**
     * Follows the pinch.
     *
     * It only *accumulates*; nothing is decided here. The map is redrawn at a new zoom when the
     * fingers come off — see [commitGesture] — because the embed's `z=` takes a whole level and
     * reloading the frame on each event of a pinch would give a map that spent the gesture blank.
     * What the user sees in the meantime is [applyGesturePreview] scaling the tiles already on screen,
     * which is how a map should answer a pinch: immediately.
     */
    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {

            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean = true

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                pinchScale *= detector.scaleFactor
                // Clamped to the span a single gesture may cover, so one violent pinch cannot ask for
                // a zoom twenty levels away — and so the preview stays a recognisable map rather than
                // a handful of enormous pixels.
                pinchScale = pinchScale.coerceIn(MIN_PINCH_SCALE, MAX_PINCH_SCALE)

                if (!isGesturing && abs(log2(pinchScale)) > PINCH_SLOP_LEVELS) markGestureStarted()
                if (isGesturing) applyGesturePreview()
                return true
            }
        }
    )

    init {
        configureWebView()
        addView(webView)
    }

    /**
     * Turns on the few settings the embedded map needs, and nothing else.
     *
     * JavaScript is required: the embed *is* a JavaScript map, and without it the frame renders
     * blank. That is safe here for the reason that normally makes it unsafe to enable — the WebView
     * only ever loads a page this class wrote, framing a URL [MapLinks] built from coordinates this
     * app chose. Nothing from the page can reach the app: no JavaScript interface is registered, so
     * there is no bridge for it to call.
     *
     * File and content access are switched off explicitly rather than left at their defaults, so a
     * page that did manage to run something cannot read the device's storage through `file://`.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            // The map stores its tile cache and user preferences in DOM storage; without this it
            // re-downloads tiles on every pan.
            domStorageEnabled = true
            // Pinch-to-zoom, without Android's old floating +/- buttons over the map.
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            allowFileAccess = false
            allowContentAccess = false
            // Google's own links inside the map carry target="_blank". Without this a tap on one is
            // silently dropped; with it the request arrives at onCreateWindow below, which sends it
            // out of the app instead of opening a second WebView inside this small card.
            setSupportMultipleWindows(true)
        }

        webView.webViewClient = object : WebViewClient() {

            /**
             * Keeps the card showing the map and sends everything else outwards.
             *
             * Only *main-frame* navigations are considered: the map lives in an iframe, so every
             * request it makes to draw itself is a sub-frame request and passes straight through.
             * `hasGesture()` then separates the rest — a navigation the user caused by tapping a link
             * belongs in a full browser or the Maps app, whereas one the page performs by itself is
             * the wrapper loading and stays here.
             */
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                if (!request.isForMainFrame || !request.hasGesture()) return false
                onExternalLink?.invoke(request.url.toString())
                return true
            }

            /**
             * The page is up: reveal it. See the class comment for why it was hidden.
             *
             * This is also what lets [recentre] stop reloading the whole document and start moving
             * the frame instead — there is now a frame to move.
             */
            override fun onPageFinished(view: WebView, url: String) {
                isPageLoaded = true
                view.visibility = View.VISIBLE
            }
        }

        webView.webChromeClient = object : WebChromeClient() {

            /**
             * A link inside the map asked for a new window (`target="_blank"`).
             *
             * Answered without ever creating one. The platform hands over a [Message] whose target is
             * waiting for a WebView to load the link into; a throwaway one is supplied whose only job
             * is to report the URL it is asked for and then stop. That URL goes to [onExternalLink],
             * so the tap ends up in the browser or the Maps app — which is where a link out of a map
             * belongs, and where the two buttons over the card already lead.
             *
             * There is no simpler route: the destination is not known until the page asks for it, and
             * it is not passed to this callback.
             */
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message
            ): Boolean {
                val catcher = WebView(view.context)
                catcher.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        child: WebView,
                        request: WebResourceRequest
                    ): Boolean {
                        onExternalLink?.invoke(request.url.toString())
                        // Nothing further is loaded, and the catcher is released here rather than
                        // kept: it exists only to be told the address.
                        child.destroy()
                        return true
                    }
                }

                (resultMsg.obj as? WebView.WebViewTransport)?.webView = catcher
                resultMsg.sendToTarget()
                return true
            }
        }
    }

    /**
     * Shows the map centred on a point, reloading only when the point has actually changed.
     *
     * The guard matters because both screens re-render on every state emission — a keystroke in the
     * name field, a tick of the "open now" clock — and reloading the page each time would leave the
     * map permanently blank, and throw away any panning or zooming the user had done.
     *
     * @param zoom Google's zoom scale: 16 for a saved place in its neighbourhood, 17 for a GPS fix,
     *   which is a point precise enough to show at street level.
     */
    fun showLocation(at: Coordinates, zoom: Int) {
        if (at == requestedAt && zoom == requestedZoom) return

        requestedAt = at
        requestedZoom = zoom
        shownAt = at
        shownZoom = zoom
        recentre()
    }

    /**
     * Points the map at [shownAt] / [shownZoom], the cheapest way available.
     *
     * Once the wrapper page is up, only the frame's `src` is rewritten: the document, its stylesheet
     * and the theme filter all stay, so re-centring after a drag costs one iframe load rather than a
     * full page load — which the picker does on every drop and would otherwise blink the card empty
     * each time.
     *
     * Before that it falls back to loading the wrapper, which is also the path [showLocation] takes
     * the first time it is called.
     *
     * The URL needs no escaping: [MapLinks.embedUrl] builds it out of digits, dots, commas and the
     * URL's own punctuation, none of which can close the JavaScript string literal it is placed in.
     */
    private fun recentre() {
        val at = shownAt ?: return

        if (!isPageLoaded) {
            loadPage()
            return
        }

        val url = MapLinks.embedUrl(at, shownZoom, isSatellite)
        webView.evaluateJavascript(
            // The class on <html> is what switches the theme tint off over imagery — see buildPage.
            // Set here rather than in a second call so the layer and its treatment can never be one
            // frame out of step with each other.
            """
            document.documentElement.className = '${if (isSatellite) PLAIN_CLASS else ""}';
            var f = document.getElementById('$MAP_FRAME_ID'); if (f) f.src = '$url';
            """.trimIndent(),
            null
        )
    }

    /**
     * Loads the wrapper page around the current coordinates.
     *
     * A base URL is given rather than null, and it is Google's own host. The document then has a real
     * https origin instead of `about:blank`, which is what lets it frame an https page at all — a
     * document loaded from nowhere is treated as opaque, and its frames would be blocked as
     * mixed/insecure content.
     */
    private fun loadPage() {
        val at = shownAt ?: return

        isPageLoaded = false
        webView.visibility = View.INVISIBLE
        webView.loadDataWithBaseURL(
            BASE_URL,
            buildPage(MapLinks.embedUrl(at, shownZoom, isSatellite)),
            "text/html",
            "utf-8",
            null
        )
    }

    /**
     * The one-element page that frames the map.
     *
     * Everything in it is doing a job:
     *  - the viewport meta stops the WebView laying the page out at a notional 980px-wide desktop and
     *    then shrinking it, which would render the map at a fraction of its real size;
     *  - `margin: 0` and `height: 100%` on `html, body` let the frame fill the card exactly, since a
     *    percentage height is measured against a parent that must have one itself;
     *  - `overflow: hidden` keeps the page itself still, so a drag pans the map rather than scrolling
     *    the document the map sits in;
     *  - the frame is positioned absolutely at all four edges rather than sized with `inset`, which
     *    older WebViews on the app's minimum API do not understand;
     *  - `filter` is the theme tint, applied to the element this page owns — see the class comment;
     *  - `loading="lazy"` and the referrer policy are what the design asks for, and `title` is what a
     *    screen reader would announce for the frame;
     *  - the `id` is how [recentre] finds the frame again, so a new centre costs one `src` change
     *    rather than a fresh document;
     *  - the `.$PLAIN_CLASS` rule takes the filter *off*, and [recentre] puts that class on `<html>`
     *    whenever the satellite layer is showing.
     *
     * **Why imagery is left untinted.** The filter exists to approximate a dark map by inverting a
     * light one, and that trick works because a drawn map is flat colour: invert it, rotate the hue
     * back, and it reads as the same map at night. Aerial photography is not flat colour — inverted,
     * vegetation goes magenta, water goes orange and rooftops glow. There is no dark version of a
     * photograph of the ground, so the honest answer in dark mode is to show the photograph.
     */
    private fun buildPage(embedUrl: String): String {
        val filter = if (isNightMode()) DARK_FILTER else LIGHT_FILTER
        val startingClass = if (isSatellite) PLAIN_CLASS else ""

        return """
            <!doctype html>
            <html class="$startingClass">
            <head>
            <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=5">
            <style>
              html, body { margin: 0; padding: 0; height: 100%; overflow: hidden; background: transparent; }
              iframe { position: absolute; top: 0; left: 0; width: 100%; height: 100%; border: 0; filter: $filter; }
              html.$PLAIN_CLASS iframe { filter: none; }
            </style>
            </head>
            <body>
            <iframe id="$MAP_FRAME_ID" src="$embedUrl" loading="lazy"
                    referrerpolicy="no-referrer-when-downgrade"
                    title="Map of selected place" allowfullscreen></iframe>
            </body>
            </html>
        """.trimIndent()
    }

    /**
     * Lets the map keep a drag that started on it.
     *
     * Both map cards sit inside a `NestedScrollView`, which claims any vertical drag for the page as
     * soon as it crosses the touch slop — so without this, panning the map north or south would
     * scroll the screen instead and the map would be usable in one direction only. Asking the parent
     * not to intercept for the duration of the gesture hands the whole drag to the map; the flag is
     * released on UP and CANCEL so ordinary page scrolling elsewhere is untouched.
     */
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> parent?.requestDisallowInterceptTouchEvent(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                parent?.requestDisallowInterceptTouchEvent(false)
        }

        // In pick mode the gesture is this view's own: returning true keeps it out of the WebView
        // entirely, so the map moves under the app's control and the coordinate it lands on is known.
        // See the class comment for why the page cannot simply be asked where the user panned to.
        if (isPickEnabled) return true

        return super.onInterceptTouchEvent(event)
    }

    /**
     * The picker's gesture: fingers drag the map, two of them also zoom it.
     *
     * Only reached in pick mode, because that is the only mode in which [onInterceptTouchEvent] claims
     * the gesture — otherwise the WebView has it and this is never called.
     *
     * Panning is measured from the **focus** — the average of every finger down — for the reason given
     * on [lastFocusX]. On the two events where the set of fingers changes, the focus is simply re-read
     * with no movement recorded: the focus genuinely jumps when a finger arrives or leaves, and that
     * jump is a fact about the hand rather than about the map.
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isPickEnabled) return super.onTouchEvent(event)

        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragX = 0f
                dragY = 0f
                pinchScale = 1f
                readFocus(event)
            }

            // A finger arriving or leaving moves the focus without the hand having moved the map.
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> readFocus(event)

            MotionEvent.ACTION_MOVE -> onGestureMove(event)

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (isGesturing) commitGesture()
            }
        }

        return true
    }

    /**
     * Records where the fingers now are, without counting the move.
     *
     * On [MotionEvent.ACTION_POINTER_UP] the lifting pointer is still part of the event but is about to
     * stop existing, so it is left out: including it would put the focus where the hand no longer is,
     * and the next move would begin with that error baked in.
     */
    private fun readFocus(event: MotionEvent) {
        val lifting = if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
            event.actionIndex
        } else {
            -1
        }

        var sumX = 0f
        var sumY = 0f
        var count = 0
        for (index in 0 until event.pointerCount) {
            if (index == lifting) continue
            sumX += event.getX(index)
            sumY += event.getY(index)
            count++
        }

        if (count == 0) return
        lastFocusX = sumX / count
        lastFocusY = sumY / count
    }

    /**
     * One step of the gesture: slide the map with the hand.
     *
     * The pan is counted whatever the finger count, so a two-finger pinch that also travels moves the
     * map as well as zooming it — which is what a map does everywhere else, and what makes it possible
     * to zoom in on something that is not already in the middle.
     *
     * The tiles only start moving once the hand has passed [touchSlop], so a tap does not shift the map
     * by the pixel or two a finger rolls while pressing — but the movement before that point is still
     * counted, or the map would lag the hand by exactly the slop for the rest of the gesture.
     */
    private fun onGestureMove(event: MotionEvent) {
        val previousX = lastFocusX
        val previousY = lastFocusY
        readFocus(event)

        dragX += lastFocusX - previousX
        dragY += lastFocusY - previousY

        if (!isGesturing && hypot(dragX, dragY) > touchSlop) markGestureStarted()
        if (isGesturing) applyGesturePreview()
    }

    /** The gesture has become deliberate: show the crosshair and start moving the tiles. */
    private fun markGestureStarted() {
        isGesturing = true
        onDragStateChanged?.invoke(true)
    }

    /**
     * Draws the gesture as it happens, by moving and scaling the tiles already on screen.
     *
     * This is a *preview*, not the answer. The embed can only be asked for whole zoom levels, and
     * reloading the frame on every event of a pinch would leave the map blank for the whole gesture —
     * so the page is left alone until the fingers lift and what the user sees in the meantime is the
     * WebView itself being transformed. It is the same trick a photo viewer uses while a pinch is under
     * way, and the reason the map now answers a pinch at all rather than sitting still until some
     * threshold was crossed.
     *
     * The scale pivots on the view's centre because the centre is the point being chosen: zooming about
     * anywhere else would move the coordinate under the crosshair while the user was only trying to see
     * it more closely.
     */
    private fun applyGesturePreview() {
        webView.pivotX = width / 2f
        webView.pivotY = height / 2f
        webView.scaleX = pinchScale
        webView.scaleY = pinchScale
        webView.translationX = dragX
        webView.translationY = dragY
    }

    /**
     * The hand is off: work out where the map now sits and at what zoom, and make that real.
     *
     * **The zoom** is the nearest whole level to the pinch — `log2` of the spread, rounded — because
     * whole levels are the only thing `z=` can express. A pinch too small to reach the next level
     * therefore springs back, which is honest: the map cannot draw a half level.
     *
     * **The centre** has to account for the scale as well as the drag, because the preview applied both.
     * A point sits at `centre + scale × (point − centre) + drag` on screen, so the point that ended up
     * in the middle is `centre − drag / scale` — which is why the drag is divided by [pinchScale] before
     * being handed to [WebMercator]. Dividing by the display density as well converts device pixels into
     * the CSS pixels the framed page is laid out in; see [buildPage], which sets `initial-scale=1`.
     *
     * The transform is undone and the embed re-pointed in the same pass, so the tiles never appear to
     * snap back before moving forward again.
     */
    private fun commitGesture() {
        isGesturing = false
        onDragStateChanged?.invoke(false)

        webView.scaleX = 1f
        webView.scaleY = 1f
        webView.translationX = 0f
        webView.translationY = 0f

        val from = shownAt
        val movedX = dragX
        val movedY = dragY
        val scale = pinchScale
        dragX = 0f
        dragY = 0f
        pinchScale = 1f

        if (from == null) return

        val zoom = (shownZoom + log2(scale).roundToInt()).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val density = resources.displayMetrics.density
        val centre = WebMercator.centreAfterDrag(
            from = from,
            dragX = (movedX / scale / density).toDouble(),
            dragY = (movedY / scale / density).toDouble(),
            zoom = shownZoom
        )

        if (centre == from && zoom == shownZoom) return

        shownAt = centre
        shownZoom = zoom
        // The centre is also recorded as what the screen asked for, so the state change this is about
        // to cause — the fragment re-rendering with the new coordinate — is recognised as already drawn
        // and does not reload the map a second time. requestedZoom is deliberately left alone: it is
        // the zoom the *screen* asked for, and the user has just overridden it.
        requestedAt = centre

        recentre()
        onPickedCentreChanged?.invoke(centre)
    }

    /**
     * Re-tints after a theme change, by rebuilding the page around the same point.
     *
     * Switching theme normally recreates the Activity, which builds a fresh view with the right
     * filter — but a device-level change (the system's own day/night switch) can deliver a new
     * configuration to a view that is already alive, and an inverted map left over from dark mode on
     * a light page would be unmistakable. The filter is baked into the page's stylesheet, so
     * changing it means writing the page again; that is one reload on an event the user just caused,
     * which is when a reload costs least.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        loadPage()
    }

    /** True when the app is drawing its dark theme — the same test the rest of the app uses. */
    private fun isNightMode(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES

    /**
     * Tears the WebView down with the fragment's view.
     *
     * A WebView keeps a rendering thread and a native page alive until it is told otherwise, so one
     * left behind by a destroyed fragment goes on consuming memory and can still call back into a
     * view hierarchy that no longer exists. Removing it from its parent first is required: destroying
     * an attached WebView is documented as unsupported and crashes on some versions.
     */
    fun destroy() {
        onExternalLink = null
        onPickedCentreChanged = null
        onDragStateChanged = null
        removeView(webView)
        webView.destroy()
    }

    private companion object {

        /**
         * The origin the wrapper page claims. Google's own host, so the document and the map it
         * frames are same-origin — which keeps the browser from treating the frame as third-party
         * content and blocking the cookies the embed uses to remember map preferences.
         */
        const val BASE_URL = "https://maps.google.com/"

        /**
         * The id given to the `<iframe>` in the wrapper page, so [recentre] can find it again and
         * move it rather than rebuild the document around it. Named once here because the page that
         * writes it and the script that looks it up must agree.
         */
        const val MAP_FRAME_ID = "map"

        /**
         * How far a pinch in pick mode may zoom.
         *
         * 20 is the deepest level Google serves tiles for in most places — beyond it the map stops
         * getting more detailed and only gets blurrier. 3 shows most of a continent, which is as far
         * out as a screen for saving "the vet round the corner" has any use for; zooming to the whole
         * globe would make a one-pixel drag worth tens of kilometres.
         */
        const val MIN_ZOOM = 3
        const val MAX_ZOOM = 20

        /**
         * How far one pinch may take the map: four levels in, four levels out.
         *
         * A ceiling is needed because the preview scales the tiles that are already on screen, and a
         * gesture allowed to run to thirty-two times would be previewing a map out of a handful of
         * pixels. Four levels is more than any single comfortable pinch reaches anyway, and a second
         * pinch carries on from where the first finished.
         */
        const val MAX_PINCH_SCALE = 16f
        const val MIN_PINCH_SCALE = 1f / 16f

        /**
         * How much of a zoom level a pinch must cover before it counts as deliberate.
         *
         * A fifth of a level: small enough that the map starts responding almost at once — which is the
         * whole point of the preview — and large enough that two fingers resting on the card do not set
         * it drifting.
         */
        const val PINCH_SLOP_LEVELS = 0.2f

        /**
         * The class put on `<html>` while imagery is showing, which switches the theme's colour filter
         * off. See [buildPage] for why a photograph must not be inverted.
         */
        const val PLAIN_CLASS = "plain"

        /**
         * Light mode: the map's colours pulled back a little, so a full-strength Google map does not
         * shout over the app's muted cream page. No inversion — the map is already light.
         */
        const val LIGHT_FILTER = "saturate(.85)"

        /**
         * Dark mode: invert almost fully, rotate the hue 180° to undo the colour reversal inversion
         * causes (without it, green parks come out magenta and blue water orange), then desaturate
         * and dim slightly so the result sits at the same weight as the app's other dark cards.
         */
        const val DARK_FILTER = "invert(.9) hue-rotate(180deg) saturate(.6) brightness(.95)"
    }
}
