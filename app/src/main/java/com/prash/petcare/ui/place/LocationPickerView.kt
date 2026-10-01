package com.prash.petcare.ui.place

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.Menu
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.prash.petcare.R
import com.prash.petcare.data.location.PlaceSuggestion
import com.prash.petcare.databinding.ViewLocationPickerBinding
import com.prash.petcare.util.Coordinates

/**
 * The search field and map card both Places forms choose a location with.
 *
 * In MVVM this is the **View** and only the View: it draws a [LocationPickerState], and every gesture
 * on it is forwarded out through one of the callbacks below without being interpreted. It holds no
 * coordinate of its own — deliberately, because the moment this class remembered where the pin was
 * there would be two answers to that question on screen and they could differ. The same goes for the
 * search field: the text it shows comes from the state, and the typing it reports goes straight out
 * without being timed, filtered or sent anywhere.
 *
 * **Why a custom view rather than two copies of the same XML.** "Add a place" and the full place form
 * need identical behaviour from the map, and requirement 15 asks for exactly that: one picker, reused,
 * rather than a second implementation that starts out the same and drifts. The layout is included once
 * here (`view_location_picker.xml`) and each screen puts one of these in its page.
 *
 * Its counterpart on the other side of the ViewModel is [PlaceLocationPicker], which owns the state
 * this draws. Between them the two hold the whole feature, and neither knows which screen it is on.
 *
 * Remember to call [destroy] from the fragment's `onDestroyView`: the map inside holds a GPU surface
 * and a native renderer, which do not go away by themselves.
 */
class LocationPickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val binding =
        ViewLocationPickerBinding.inflate(LayoutInflater.from(context), this, true)

    /**
     * "I'm here" or the corner crosshair was pressed.
     *
     * One callback for both, because they are one action — see
     * [PlaceLocationPicker.useCurrentLocation]. A view that told the two apart would be inviting the
     * screen above it to make them behave differently.
     */
    var onUseCurrentLocation: (() -> Unit)? = null

    /**
     * The text in the search field changed.
     *
     * Reported raw, on every keystroke — the waiting is not done here. Debouncing is a decision about
     * when to spend a network request, which belongs with the thing that spends it; see
     * [PlaceLocationPicker.onSearchQueryChanged]. A view that held the timer would also be holding the
     * answer to "is a search running?", which the state it draws already knows.
     */
    var onSearchQueryChanged: ((String) -> Unit)? = null

    /** A suggestion row was tapped. */
    var onSearchResultSelected: ((PlaceSuggestion) -> Unit)? = null

    /** The field gained or lost focus, which opens and closes the suggestions panel. */
    var onSearchFocusChanged: ((Boolean) -> Unit)? = null

    /** A layer was chosen from the map-type menu. */
    var onMapTypeSelected: ((MapType) -> Unit)? = null

    /** The user moved the map and it has come to rest under the centre pin. */
    var onCentreChangedByUser: ((Coordinates) -> Unit)? = null

    /** The map reported whether it could be loaded at all. */
    var onMapAvailabilityChanged: ((Boolean) -> Unit)? = null

    /** The credit line under the map was tapped — see [MapStyles.ATTRIBUTION_URL]. */
    var onExternalLink: ((String) -> Unit)? = null

    /** What the menu is currently offering to change, so a re-render cannot tick the wrong row. */
    private var mapType: MapType = MapType.NORMAL

    /** Whether this view has already been pointed at the selection it was given — see [render]. */
    private var hasCentredOnSelection = false

    /**
     * The suggestions list's adapter.
     *
     * Created once and kept, rather than rebuilt per render: a `ListAdapter` is what makes the rows
     * diff against their predecessors instead of flashing, and an adapter replaced on every emission
     * would have nothing to diff against.
     */
    private val searchAdapter = PlaceSearchAdapter { suggestion ->
        // The keyboard goes before anything else: the map is about to fly somewhere, and arriving
        // behind a keyboard would hide half of what the user asked to see.
        hideKeyboard()
        onSearchResultSelected?.invoke(suggestion)
    }

    init {
        binding.imHereButton.setOnClickListener { onUseCurrentLocation?.invoke() }
        binding.recentreButton.setOnClickListener { onUseCurrentLocation?.invoke() }
        binding.mapTypeButton.setOnClickListener { showMapTypeMenu() }

        binding.map.onCentreChangedByUser = { onCentreChangedByUser?.invoke(it) }
        binding.map.onReadyStateChanged = { onMapAvailabilityChanged?.invoke(it) }
        binding.map.onExternalLink = { onExternalLink?.invoke(it) }

        setUpSearch()
    }

    /**
     * Wires the search field and its suggestions panel.
     *
     * **The text watcher reports what was typed and nothing else.** [render] writes the state's query
     * back into the box when the two differ — which is how choosing a result puts that result's name
     * in the field — and that write comes back through here as if it were a keystroke. The guard in
     * [PlaceLocationPicker.onSearchQueryChanged] is what absorbs it, the same arrangement the forms
     * use for their own fields.
     *
     * The list is given `isNestedScrollingEnabled = false`: it lives inside the page's own
     * `NestedScrollView`, and with nested scrolling on, a drag inside a short list would be handed up
     * to the page and scroll the whole form instead of the suggestions.
     */
    private fun setUpSearch() {
        binding.searchResultsList.adapter = searchAdapter
        binding.searchResultsList.layoutManager = LinearLayoutManager(context)
        binding.searchResultsList.isNestedScrollingEnabled = false
        binding.searchResultsList.setHasFixedSize(false)

        binding.searchInput.doOnTextChanged { text, _, _, _ ->
            onSearchQueryChanged?.invoke(text?.toString().orEmpty())
        }

        binding.searchInput.setOnFocusChangeListener { _, hasFocus ->
            onSearchFocusChanged?.invoke(hasFocus)
        }

        // The keyboard's search key. There is nothing to submit — the suggestions are already there,
        // and a place is chosen by tapping one — so it closes the keyboard to uncover them rather
        // than firing a request the debounce has already made.
        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard()
                true
            } else {
                false
            }
        }
    }

    /**
     * Draws one picker state.
     *
     * Safe to call on every emission: the map's own setters are guarded, so a state change that has
     * nothing to do with the map — a keystroke in the name field — costs nothing here.
     */
    fun render(state: LocationPickerState) {
        mapType = state.mapType
        binding.map.mapType = state.mapType

        renderSearch(state.search)

        // Puts the map back on the chosen point when a *new view* meets an *existing* selection —
        // which is what a rotation is. The camera lives in the map and dies with it, while the
        // selection lives in the ViewModel and survives, so without this the map would come back
        // showing its default position under a card naming somewhere else.
        //
        // Once only, and that is the whole subtlety: the picker's own MoveCamera events drive every
        // deliberate move, and re-centring on each emission would drag the map back to the selection
        // the instant the user panned away from it — turning every drag into a fight.
        //
        // **The chance is spent on the first render whether or not there was anything to restore**,
        // and that detail matters now that a result can be searched for. A fresh "Add a place" screen
        // renders first with nothing selected; if the flag survived that, the first *search* selection
        // would be read as "a new view meeting an existing selection" and this would jump the camera
        // there instantly — beating the animated flight the picker sends for it, and turning a result
        // the user chose into a teleport. Consuming the chance here means a view that had nothing to
        // restore never tries to, and the events are left to do the moving.
        if (!hasCentredOnSelection) {
            hasCentredOnSelection = true
            state.selected?.let {
                binding.map.moveCameraTo(it.coordinates, RESTORED_ZOOM, animate = false)
            }
        }

        // The label carries the progress rather than a spinner beside it, which is what the rest of
        // the app's buttons do. Both controls are disabled together, because they are one action and
        // a second request while the first is in flight would only make the user wait longer.
        binding.imHereButton.setText(
            if (state.isLocating) R.string.map_picker_locating else R.string.map_picker_im_here
        )
        binding.imHereButton.isEnabled = !state.isLocating
        binding.recentreButton.isEnabled = !state.isLocating

        // The pin is hidden along with the map: with no tiles under it, it would be pointing at
        // nothing while still looking like an answer.
        binding.mapUnavailableText.isVisible = !state.isMapAvailable
        // Two different reasons a map can be missing, and only one of them is the user's to fix.
        // "Check your connection" said to somebody whose build simply has no MapTiler key would send
        // them to their Wi-Fi settings for a problem that is in local.properties — so the build-time
        // cause is named as itself. MapStyles.isConfigured is a fact about the build rather than app
        // state, which is why the view may read it directly.
        binding.mapUnavailableText.setText(
            if (MapStyles.isConfigured) {
                R.string.map_picker_unavailable
            } else {
                R.string.map_picker_no_key
            }
        )
        binding.centrePin.isVisible = state.isMapAvailable
        binding.mapTypeButton.isVisible = state.isMapAvailable
    }

    /**
     * Draws the search field and its suggestions panel.
     *
     * **The field's text is written back only when it differs**, and that guard is not an
     * optimisation: setting an `EditText`'s text to what it already contains moves the cursor to the
     * end, and this state is re-emitted on every keystroke — so without it the field would be unusable
     * for anyone who corrected a letter mid-word. The same rule the Add a place form's boxes follow.
     *
     * **The panel has exactly three things it can show**, and they are mutually exclusive: the rows,
     * one line saying a search is running, or one line saying nothing was found. Which of them is
     * right is a question about the state, already answered by
     * [PlaceSearchState.hasNoResults] — so this method only has to draw the answer.
     *
     * The panel is hidden rather than emptied when the list closes, so reopening it costs no rebind
     * and the rows it was showing are still there to diff against.
     */
    private fun renderSearch(search: PlaceSearchState) {
        if (binding.searchInput.text?.toString() != search.query) {
            binding.searchInput.setText(search.query)
            binding.searchInput.setSelection(search.query.length)
        }

        searchAdapter.submitList(search.results)

        val hasRows = search.results.isNotEmpty()
        val isStatusShowing = search.isSearching || search.hasNoResults
        // Rows win when there are any: a fresh search keeps the previous suggestions on screen while
        // the next ones are fetched, which is far steadier than replacing a usable list with
        // "Searching…" on every pause in the typing.
        binding.searchResultsCard.isVisible = search.isExpanded && (hasRows || isStatusShowing)
        binding.searchResultsList.isVisible = hasRows
        binding.searchStatusText.isVisible = !hasRows && isStatusShowing
        binding.searchStatusText.setText(
            if (search.isSearching) R.string.place_search_searching else R.string.place_search_empty
        )
    }

    /**
     * Puts the soft keyboard away.
     *
     * Needed at two moments, both of which end the typing: a result was chosen, and the keyboard's
     * own search key was pressed. The map is the thing to look at in both cases, and it is behind the
     * keyboard.
     */
    private fun hideKeyboard() {
        val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        manager?.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
        // Dropped as well as hidden, so the panel does not reopen the moment the keyboard is gone —
        // the field's focus is what holds it open.
        binding.searchInput.clearFocus()
    }

    /**
     * Takes the map to a point.
     *
     * Always obeyed rather than guarded, because every call is one the user asked for — the opening
     * position, "I'm here", or the recentre button. A move made this way is not reported back through
     * [onCentreChangedByUser]; see [PlaceMapView.moveCameraTo].
     */
    fun moveCameraTo(at: Coordinates, zoom: Int, animate: Boolean) {
        binding.map.moveCameraTo(at, zoom, animate)
    }

    /**
     * Offers the three layers, with the current one ticked.
     *
     * Built from [MapType.entries] rather than from a menu resource, so adding a layer is a change in
     * one file instead of two that have to agree about the order of their rows.
     *
     * The item ids are the enum's ordinals *offset by one*, because zero is [Menu.NONE] — an id of
     * zero means "no id", and `findItem(0)` would not reliably find the row it was meant to tick.
     */
    private fun showMapTypeMenu() {
        val popup = PopupMenu(context, binding.mapTypeButton)

        MapType.entries.forEachIndexed { index, type ->
            popup.menu.add(MAP_TYPE_GROUP, index + 1, index, context.getString(type.labelRes))
        }
        // A ticked radio row, so the menu says which layer is showing as well as offering the others.
        popup.menu.setGroupCheckable(MAP_TYPE_GROUP, true, true)
        popup.menu.findItem(mapType.ordinal + 1)?.isChecked = true

        popup.setOnMenuItemClickListener { item ->
            MapType.entries.getOrNull(item.itemId - 1)?.let { onMapTypeSelected?.invoke(it) }
            true
        }
        popup.show()
    }

    /**
     * Releases the map with the fragment's view.
     *
     * The map inside holds a GPU surface and a native renderer until it is told otherwise — see
     * [PlaceMapView.destroy]. The callbacks are dropped here too, so nothing left in flight can reach
     * a fragment whose views have gone.
     */
    fun destroy() {
        onUseCurrentLocation = null
        onSearchQueryChanged = null
        onSearchResultSelected = null
        onSearchFocusChanged = null
        onMapTypeSelected = null
        onCentreChangedByUser = null
        onMapAvailabilityChanged = null
        onExternalLink = null
        // The adapter holds the row click lambda, which reaches the fragment's ViewModel. Detached
        // with the rest so a tap landing after the view is gone has nothing to call.
        binding.searchResultsList.adapter = null
        binding.map.destroy()
    }

    private companion object {

        /** The menu group the three layers share, which is what makes the tick exclusive. */
        const val MAP_TYPE_GROUP = Menu.FIRST

        /**
         * Street level, for the one camera move this view makes on its own: putting a recreated map
         * back on the selection it was handed. The same zoom the picker chooses a point at, so a
         * rotation looks like the map staying still rather than jumping in or out.
         */
        const val RESTORED_ZOOM = 17
    }
}
