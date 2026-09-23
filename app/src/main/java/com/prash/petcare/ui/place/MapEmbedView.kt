package com.prash.petcare.ui.place

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.os.Message
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.prash.petcare.util.Coordinates
import com.prash.petcare.util.MapLinks

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

    /** What the page currently shows, so re-rendering the same place does not reload it. */
    private var shownAt: Coordinates? = null
    private var shownZoom: Int = 0

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

            /** The page is up: reveal it. See the class comment for why it was hidden. */
            override fun onPageFinished(view: WebView, url: String) {
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
        if (at == shownAt && zoom == shownZoom) return

        shownAt = at
        shownZoom = zoom
        loadPage()
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

        webView.visibility = View.INVISIBLE
        webView.loadDataWithBaseURL(
            BASE_URL,
            buildPage(MapLinks.embedUrl(at, shownZoom)),
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
     *    screen reader would announce for the frame.
     */
    private fun buildPage(embedUrl: String): String {
        val filter = if (isNightMode()) DARK_FILTER else LIGHT_FILTER

        return """
            <!doctype html>
            <html>
            <head>
            <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=5">
            <style>
              html, body { margin: 0; padding: 0; height: 100%; overflow: hidden; background: transparent; }
              iframe { position: absolute; top: 0; left: 0; width: 100%; height: 100%; border: 0; filter: $filter; }
            </style>
            </head>
            <body>
            <iframe src="$embedUrl" loading="lazy" referrerpolicy="no-referrer-when-downgrade"
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
        return super.onInterceptTouchEvent(event)
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
