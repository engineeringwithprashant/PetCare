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

/** The search field and map card both Places forms choose a location with. */
class LocationPickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val binding =
        ViewLocationPickerBinding.inflate(LayoutInflater.from(context), this, true)

    /** "I'm here" or the corner crosshair was pressed. */
    var onUseCurrentLocation: (() -> Unit)? = null

    /** The text in the search field changed. */
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

    /** The suggestions list's adapter. */
    private val searchAdapter = PlaceSearchAdapter { suggestion ->
        // The keyboard goes before anything else: the map is about to fly somewhere.
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

    /** Wires the search field and its suggestions panel. */
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

        // The keyboard's search key. There is nothing to submit — the suggestions are already there.
        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard()
                true
            } else {
                false
            }
        }
    }

    /** Draws one picker state. */
    fun render(state: LocationPickerState) {
        mapType = state.mapType
        binding.map.mapType = state.mapType

        renderSearch(state.search)

        // Puts the map back on the chosen point when a new view meets an existing selection.
        if (!hasCentredOnSelection) {
            hasCentredOnSelection = true
            state.selected?.let {
                binding.map.moveCameraTo(it.coordinates, RESTORED_ZOOM, animate = false)
            }
        }

        // The label carries the progress rather than a spinner beside.
        binding.imHereButton.setText(
            if (state.isLocating) R.string.map_picker_locating else R.string.map_picker_im_here
        )
        binding.imHereButton.isEnabled = !state.isLocating
        binding.recentreButton.isEnabled = !state.isLocating

        // The pin is hidden along with the map: with no tiles under it, it would be pointing at nothing.
        binding.mapUnavailableText.isVisible = !state.isMapAvailable
        // Two different reasons a map can be missing, and only one of them is the user's to fix.
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

    /** Draws the search field and its suggestions panel. */
    private fun renderSearch(search: PlaceSearchState) {
        if (binding.searchInput.text?.toString() != search.query) {
            binding.searchInput.setText(search.query)
            binding.searchInput.setSelection(search.query.length)
        }

        searchAdapter.submitList(search.results)

        val hasRows = search.results.isNotEmpty()
        val isStatusShowing = search.isSearching || search.hasNoResults
        // Rows win when there are any: a fresh search keeps the previous suggestions on screen.
        binding.searchResultsCard.isVisible = search.isExpanded && (hasRows || isStatusShowing)
        binding.searchResultsList.isVisible = hasRows
        binding.searchStatusText.isVisible = !hasRows && isStatusShowing
        binding.searchStatusText.setText(
            if (search.isSearching) R.string.place_search_searching else R.string.place_search_empty
        )
    }

    /** Puts the soft keyboard away. */
    private fun hideKeyboard() {
        val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        manager?.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
        // Dropped as well as hidden, so the panel does not reopen the moment the keyboard is gone.
        binding.searchInput.clearFocus()
    }

    /** Takes the map to a point. */
    fun moveCameraTo(at: Coordinates, zoom: Int, animate: Boolean) {
        binding.map.moveCameraTo(at, zoom, animate)
    }

    /** Offers the three layers, with the current one ticked. */
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

    /** Releases the map with the fragment's view. */
    fun destroy() {
        onUseCurrentLocation = null
        onSearchQueryChanged = null
        onSearchResultSelected = null
        onSearchFocusChanged = null
        onMapTypeSelected = null
        onCentreChangedByUser = null
        onMapAvailabilityChanged = null
        onExternalLink = null
        // The adapter holds the row click lambda, which reaches the fragment's ViewModel.
        binding.searchResultsList.adapter = null
        binding.map.destroy()
    }

    private companion object {

        /** The menu group the three layers share, which is what makes the tick exclusive. */
        const val MAP_TYPE_GROUP = Menu.FIRST

        /** Street level, for the one camera move this view makes on its own. */
        const val RESTORED_ZOOM = 17
    }
}
