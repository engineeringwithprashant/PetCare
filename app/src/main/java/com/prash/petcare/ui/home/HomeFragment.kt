package com.prash.petcare.ui.home

import android.Manifest
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.navOptions
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
import com.prash.petcare.R
import com.prash.petcare.data.local.entity.PetGender
import com.prash.petcare.data.repository.WeatherState
import com.prash.petcare.data.sync.SyncStatus
import com.prash.petcare.databinding.FragmentHomeBinding
import com.prash.petcare.databinding.ItemHomeStatBinding
import com.prash.petcare.databinding.ItemStreakDayBinding
import com.prash.petcare.ui.common.formatCurrency
import com.prash.petcare.ui.common.formatTimeOfDay
import com.prash.petcare.ui.common.setUp
import com.prash.petcare.ui.common.showAssistantPill
import com.prash.petcare.ui.common.showPetCount
import com.prash.petcare.ui.pet.AddEditPetFragment
import com.prash.petcare.ui.pet.PetDetailFragment
import com.prash.petcare.ui.pet.PetViewModel
import com.prash.petcare.ui.place.LocationPermissionRequester
import com.prash.petcare.ui.routine.RoutineDetailFragment
import com.prash.petcare.ui.session.SessionViewModel
import com.prash.petcare.util.WalkComfort
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The Home tab: the dashboard the app opens on once signed in.
 *
 * In MVVM this is the **View**. It draws whatever [HomeViewModel] publishes and forwards taps back — it
 * runs no queries and works nothing out. Which task is next, how many minutes away it is, whether last
 * Friday was a full day, what the insight should say: all of that is decided in [HomeDashboard] before
 * it reaches this class, which is what lets the fragment be destroyed and rebuilt on rotation without
 * losing or repeating work. What *is* done here is purely presentational — turning a time in minutes
 * into "6:00 PM" for the device's clock setting, choosing the right plural, picking a colour.
 *
 * **Two pieces of state that are not the ViewModel's.** The header's light/dark toggle belongs to the
 * installation rather than the account and is applied by recreating the Activity, so it is handled in
 * the shared header code (TabHeader.kt). And the sync line is drawn from its own stream, because backup
 * progress changes on a different rhythm from the dashboard's data — see [HomeViewModel.syncStatus].
 */
class HomeFragment : Fragment() {

    /**
     * ViewBinding for fragment_home.xml.
     *
     * Nullable because a fragment's view can be destroyed while the fragment itself lives on; holding the
     * binding past that point would leak the whole view hierarchy. Cleared in [onDestroyView].
     */
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    /**
     * Which account is signed in, read from the Activity-wide session rather than a navigation argument:
     * a bottom navigation bar moves between tabs by destination id and has nowhere to attach one.
     */
    private val sessionViewModel: SessionViewModel by activityViewModels()

    private val viewModel: HomeViewModel by viewModels {
        HomeViewModel.factory(sessionViewModel.userId.value)
    }

    /**
     * The pet-card adapter, rebuilt with each view rather than held for the fragment's whole life.
     *
     * It captures `viewLifecycleOwner.lifecycleScope` for its photo loads, and a fragment outlives its
     * view — so that scope is a *different object* each time the view is recreated. An adapter created
     * once would still hold the first view's cancelled scope after navigating to a pet and back, and every
     * photo would silently fail to load. Nullable for the same reason as [_binding].
     */
    private var petAdapter: HomePetAdapter? = null

    /** The "coming up this week" rows. Held per view like [petAdapter], for symmetry and to be detached. */
    private var comingUpAdapter: ComingUpAdapter? = null

    /** The seven streak boxes in order, so box `i` always shows `state.streak[i]`. */
    private var streakCells: List<ItemStreakDayBinding> = emptyList()

    /**
     * The latest sync status, kept so the "3 min ago" line can be redrawn when the clock ticks as well
     * as when the status itself changes — the words go stale with time even when nothing is syncing.
     */
    private var latestSyncStatus: SyncStatus = SyncStatus.Idle

    /**
     * The latest temperature, and the latest scheduled-walk state, kept for the same reason as
     * [latestSyncStatus].
     *
     * The walk-window card is drawn from **both** — the temperature when there is one, the next
     * walk's time when there is not — and they arrive on two independent streams: the walk comes from
     * Room with the rest of the dashboard, the temperature from the network some seconds later. Each
     * collector therefore stores its half and asks for the whole card to be redrawn, rather than
     * trying to draw a card it only knows half of.
     */
    private var latestWeather: WeatherState = WeatherState.Loading
    private var latestWalkWindow: WalkWindow = WalkWindow.NoneToday

    /**
     * Approximate location for the weather card, asked for the first time the dashboard is shown.
     *
     * **Why this screen asks on arrival**, where the Places tab deliberately waits for a button. There
     * is nothing to wait for here: the card is not something the user chooses to use, it is simply
     * part of the dashboard, so there is no later moment that would explain the request better than
     * the first sight of the card it feeds. The rationale dialog still comes first, so the system
     * prompt is never the opening move.
     *
     * **Refusing costs nothing**, which is what makes asking acceptable: the repository falls back to
     * Kathmandu's coordinates and the card shows a temperature either way. So there is no denial
     * handling beyond refreshing — no dialog, no message, nothing for the user to resolve.
     *
     * Coarse alone, not the fine-and-coarse pair the Places screens request: a temperature is the
     * same across a whole city. See [LocationPermissionRequester].
     */
    private val locationPermission = LocationPermissionRequester(
        fragment = this,
        rationaleMessage = R.string.permission_location_weather_message,
        // Granted: fetch again straight away, so the card moves from the fallback city to the real
        // one without waiting for the user to leave the tab and come back.
        onGranted = { viewModel.refreshWeather() },
        onDenied = { viewModel.refreshWeather() },
        permissions = arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION)
    )

    /**
     * The clock time of the latest dashboard state, so a sync status arriving between ticks is measured
     * against the same "now" the greeting shows.
     */
    private var latestNowMillis: Long = 0L

    /**
     * Date formatters, built on first use with the locale in force at that moment. A change of language
     * recreates the Activity and with it this fragment, so they can never outlive the locale they were
     * built for.
     *
     * `android.icu` rather than `java.text`: only ICU understands "EEEEEE" — the locale's own two-letter
     * weekday ("Fr", "Mo"), which cutting the full name to two characters would get wrong in many
     * languages. ICU is part of the platform from API 24, this app's minimum. The multi-field patterns
     * are asked of the locale with getBestDateTimePattern, so "Thu 11 Sep" in the UK is "Thu, Sep 11" in
     * the US without either being spelled out here.
     */
    private val streakWeekdayFormat by lazy {
        android.icu.text.SimpleDateFormat("EEEEEE", Locale.getDefault())
    }
    private val headerDateFormat by lazy {
        android.icu.text.SimpleDateFormat(
            DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEdMMM"),
            Locale.getDefault()
        )
    }
    private val vetDateFormat by lazy {
        android.icu.text.SimpleDateFormat(
            DateFormat.getBestDateTimePattern(Locale.getDefault(), "dMMM"),
            Locale.getDefault()
        )
    }

    /**
     * The short month name for the spending card, in **UTC**: the month boundaries MonthlySpending
     * returns are midnight UTC, as stored expense dates are, and formatting them in a zone west of
     * Greenwich would print the previous month. "LLL" is the stand-alone form, the correct one for a
     * month name on its own.
     */
    private val monthFormat by lazy {
        android.icu.text.SimpleDateFormat("LLL", Locale.getDefault()).apply {
            timeZone = android.icu.util.TimeZone.getTimeZone("UTC")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // The shared header: title, theme toggle and badge, plus this tab's AI pill. See TabHeader.kt.
        binding.header.setUp(title = R.string.nav_home, onBadgeClick = ::openProfile)
        binding.header.showAssistantPill(::openAssistant)

        streakCells = listOf(
            binding.streakDay0, binding.streakDay1, binding.streakDay2, binding.streakDay3,
            binding.streakDay4, binding.streakDay5, binding.streakDay6
        )

        setUpLists()
        setUpClickListeners()
        observeViewModel()
    }

    /**
     * Refreshes the temperature each time the dashboard comes into view, and asks for location once.
     *
     * `onStart` rather than `onViewCreated`, as on the Places tab: returning from another app does
     * not recreate the view, but half an hour may well have passed. The call is cheap to repeat —
     * the repository answers from its cache without a request while the reading is fresh.
     *
     * The permission is asked for through the ViewModel's one-shot flag rather than a field here, so
     * that rotating the phone, or leaving the app and coming back, does not ask a second time. Only
     * when it has not already been granted, since [LocationPermissionRequester.request] would
     * otherwise show a rationale for something the user has already agreed to.
     */
    override fun onStart() {
        super.onStart()
        viewModel.refreshWeather()

        if (!locationPermission.hasPermission() && viewModel.markLocationAsked()) {
            locationPermission.request()
        }
    }

    /**
     * Prepares the two lists.
     *
     * Neither calls `setHasFixedSize(true)`: both sit inside the page's NestedScrollView with wrap_content
     * size, so they must be free to re-measure as their contents change.
     */
    private fun setUpLists() {
        petAdapter = HomePetAdapter(
            photoScope = viewLifecycleOwner.lifecycleScope,
            onPetClick = ::openPetDetail
        )
        binding.petsRecyclerView.apply {
            // HORIZONTAL is what makes this the sideways row; the layout manager, not the adapter,
            // decides how cards are placed.
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = petAdapter
        }

        comingUpAdapter = ComingUpAdapter(onItemClick = ::openRoutine)
        binding.comingUpRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = comingUpAdapter
        }
    }

    private fun setUpClickListeners() {
        binding.addFirstPetButton.setOnClickListener { openNewPetForm() }
        binding.actionAddPet.setOnClickListener { openNewPetForm() }

        // See openTab for why these two open a tab rather than a form directly.
        binding.actionAddTask.setOnClickListener { openTab(R.id.checklistFragment) }
        binding.actionLogExpense.setOnClickListener { openTab(R.id.expensesFragment) }

        binding.askAssistantButton.setOnClickListener { openAssistant() }
    }

    /**
     * Subscribes to the ViewModel.
     *
     * `repeatOnLifecycle(STARTED)` collects while the screen is visible and cancels when it stops, so the
     * fragment never touches views in the background — and, because the ViewModel's clock is shared
     * `WhileSubscribed`, the minute ticks stop too. Two collectors in their own coroutines, because
     * `collect` never returns and a second call after the first would never run.
     */
    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.uiState.collect(::render) }
                launch {
                    viewModel.syncStatus.collect { status ->
                        latestSyncStatus = status
                        renderSyncStatus()
                    }
                }
                launch {
                    viewModel.weather.collect { weather ->
                        latestWeather = weather
                        renderWalkWindow()
                    }
                }
            }
        }
    }

    /** Applies one dashboard snapshot to every card. */
    private fun render(state: HomeUiState) {
        latestNowMillis = state.nowMillis

        renderGreeting(state)
        binding.header.showPetCount(state.pets.size, state.overdueCount)

        // The dashboard and the empty state swap as a unit. Both stay hidden while loading, which stops
        // "No pets yet" flashing up before the first query returns.
        binding.dashboardContent.isVisible = state.pets.isNotEmpty()
        binding.emptyStateContainer.isVisible = state.hasNoPets
        if (state.isLoading) return

        latestWalkWindow = state.walkWindow
        renderWalkWindow()
        renderSpending(state)
        renderNextVet(state.nextVet)
        renderNextUp(state)
        renderTodayCard(state)
        renderStreak(state)
        petAdapter?.submitList(state.pets)
        binding.insightText.text = insightText(state.insight)
        renderComingUp(state.comingUp)

        // The "3 min ago" wording depends on the time, so it is refreshed with every tick.
        renderSyncStatus()
    }

    // ---------------------------------------------------------------------------------------------
    // Greeting
    // ---------------------------------------------------------------------------------------------

    /**
     * "Good evening, / Prashant", with the clock and date on the right.
     *
     * The time uses the device's own 12- or 24-hour setting through [DateFormat.getTimeFormat]. Before
     * the first snapshot there is no "now" yet, so the clock is left blank rather than showing 1970.
     */
    private fun renderGreeting(state: HomeUiState) {
        val salutation = getString(
            when (state.partOfDay) {
                PartOfDay.MORNING -> R.string.home_greeting_morning
                PartOfDay.AFTERNOON -> R.string.home_greeting_afternoon
                PartOfDay.EVENING -> R.string.home_greeting_evening
                PartOfDay.NIGHT -> R.string.home_greeting_night
            }
        )
        binding.greetingText.text = if (state.firstName.isNotEmpty()) {
            getString(R.string.home_greeting_format, salutation, state.firstName)
        } else {
            salutation
        }

        if (state.nowMillis > 0L) {
            val now = Date(state.nowMillis)
            binding.clockText.text = DateFormat.getTimeFormat(requireContext()).format(now)
            binding.dateText.text = headerDateFormat.format(now)
        }
    }

    /**
     * The sync line under the greeting: a coloured dot and a short phrase, or nothing at all.
     *
     * Hidden rather than emptied before the first sync, so it takes no space — a blank line under the
     * greeting would read as something that had failed to load. The dot carries the mood (green all
     * backed up, yellow still waiting, grey offline with nothing at risk) so the words can stay quiet.
     */
    private fun renderSyncStatus() {
        val binding = _binding ?: return
        val status = latestSyncStatus
        val now = if (latestNowMillis > 0L) latestNowMillis else System.currentTimeMillis()

        val text: String? = when (status) {
            is SyncStatus.Idle -> null
            is SyncStatus.Syncing -> getString(R.string.sync_status_syncing)
            is SyncStatus.Pending -> resources.getQuantityString(
                R.plurals.sync_status_pending,
                status.changeCount,
                status.changeCount
            )
            is SyncStatus.Synced -> getString(R.string.home_sync_synced, relativeTime(status.atMillis, now))
            is SyncStatus.Offline -> getString(R.string.sync_status_offline)
        }

        binding.syncRow.isVisible = text != null
        binding.syncStatusText.text = text.orEmpty()

        val dotColour = when (status) {
            is SyncStatus.Synced -> ContextCompat.getColor(requireContext(), R.color.sync_dot_synced)
            is SyncStatus.Offline -> themeColour(com.google.android.material.R.attr.colorOutline)
            else -> ContextCompat.getColor(requireContext(), R.color.sync_dot_pending)
        }
        binding.syncDot.backgroundTintList = ColorStateList.valueOf(dotColour)
    }

    /**
     * "3 min. ago", produced by [DateUtils] so it arrives already translated.
     *
     * The reference moment is never allowed to be *before* the sync: the clock only ticks once a
     * minute, so a sync finishing a few seconds after the last tick would otherwise read "in 0 min.".
     */
    private fun relativeTime(atMillis: Long, nowMillis: Long): CharSequence =
        DateUtils.getRelativeTimeSpanString(
            atMillis,
            maxOf(nowMillis, atMillis),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE
        )

    // ---------------------------------------------------------------------------------------------
    // Stat cards
    // ---------------------------------------------------------------------------------------------

    /**
     * The walk window card: "WALK WINDOW / 18°C / GOOD NOW", as the design has it.
     *
     * **Two sources, one card.** The figure is the live temperature where the user is, and the pill
     * says whether that suits a walk. Neither is always available, so the card degrades in two steps
     * rather than ever going blank:
     *
     * - a reading, fresh or cached → the temperature and its verdict;
     * - nothing yet, still fetching → a dash and "CHECKING", so the card holds its shape instead of
     *   appearing broken. Only reachable on a first launch; once anything has been cached, the
     *   repository serves that while it refetches;
     * - nothing at all, and nothing cached → [renderScheduledWalk], the card's original behaviour,
     *   which answers the same question ("is now a good time?") from the walks already on the
     *   calendar. A weatherless card is still a useful one.
     *
     * Takes no parameters because it is called from two places on two different rhythms — see
     * [latestWeather]. Guarded against a null binding for the same reason [renderSyncStatus] is: the
     * weather can arrive from the network after the view has been destroyed.
     */
    private fun renderWalkWindow() {
        val binding = _binding ?: return
        val card = binding.statWalk
        card.statLabel.setText(R.string.home_stat_walk_window)

        when (val weather = latestWeather) {
            is WeatherState.Available -> {
                // Whole degrees: the card is a glance, and a decimal place implies a precision the
                // forecast does not have. getString formats the number in the device's own digits.
                card.show(
                    getString(R.string.home_weather_temperature, weather.temperatureCelsius.roundToInt()),
                    // The comfortable case reuses the card's existing "Good now" wording, which said
                    // the same thing about the scheduled walk and is exactly what the design shows.
                    when (weather.comfort) {
                        WalkComfort.GOOD -> R.string.home_walk_good_now
                        WalkComfort.TOO_HOT -> R.string.home_weather_too_hot
                        WalkComfort.TOO_COLD -> R.string.home_weather_too_cold
                    },
                    // Mint for good walking weather, as the design shows. The two warnings take the
                    // neutral wash rather than a colour of their own: "too hot" and "too cold" are
                    // opposite facts but the same advice — not now — and inventing a warm and a cold
                    // pill role would mean two more colours to keep legible in both themes for a
                    // distinction the words already make.
                    if (weather.comfort == WalkComfort.GOOD) PillTone.GOOD else PillTone.NEUTRAL
                )
            }

            WeatherState.Loading -> card.show(
                getString(R.string.home_stat_empty_value),
                R.string.home_weather_checking,
                PillTone.NEUTRAL
            )

            WeatherState.Unavailable -> renderScheduledWalk(latestWalkWindow)
        }
    }

    /**
     * The walk-window card's fallback, used when no temperature can be had at all.
     *
     * This was the card's whole behaviour before it had a weather source, and it is kept because it
     * degrades honestly: rather than an empty card or a stale guess, it answers the same question
     * from data the app definitely has — see [WalkWindow].
     */
    private fun renderScheduledWalk(walk: WalkWindow) {
        val card = binding.statWalk

        when (walk) {
            WalkWindow.NoneToday ->
                card.show(getString(R.string.home_stat_empty_value), R.string.home_walk_none_pill, PillTone.NEUTRAL)

            WalkWindow.AllDone ->
                card.show(getString(R.string.home_walk_done_value), R.string.home_walk_done_pill, PillTone.GOOD)

            is WalkWindow.GoodNow -> card.show(
                walk.timeOfDayMinutes?.let { formatTimeOfDay(requireContext(), it) }
                    ?: getString(R.string.home_walk_any_time),
                R.string.home_walk_good_now,
                PillTone.GOOD
            )

            is WalkWindow.Later -> card.show(
                formatTimeOfDay(requireContext(), walk.timeOfDayMinutes),
                R.string.home_walk_later,
                PillTone.NEUTRAL
            )
        }
    }

    /**
     * "SPENT · SEP / Rs 5,240 / +12% VS AUG". Amounts use the app's fixed rupee formatting — see
     * [formatCurrency] for why the currency is not taken from the device's region.
     */
    private fun renderSpending(state: HomeUiState) {
        val card = binding.statSpent
        val month = state.spending ?: return

        card.statLabel.text = getString(R.string.home_stat_spent_format, monthFormat.format(Date(month.monthStart)))
        card.statValue.text = formatCurrency(month.total)

        val previousMonth = monthFormat.format(Date(month.previousMonthStart))
        val change = month.changePercent
        card.statPill.text = when {
            change == null -> getString(R.string.home_spent_no_previous, previousMonth)
            change > 0 -> getString(R.string.home_spent_change_up, change, previousMonth)
            change < 0 -> getString(R.string.home_spent_change_down, abs(change), previousMonth)
            else -> getString(R.string.home_spent_change_flat, change, previousMonth)
        }
        card.statPill.applyTone(PillTone.NEUTRAL)
    }

    /** "NEXT VET / 22 Sep / KRISHALA", the pill in the accent because it names *whose* visit it is. */
    private fun renderNextVet(visit: NextVetVisit?) {
        val card = binding.statVet
        card.statLabel.setText(R.string.home_stat_next_vet)

        if (visit == null) {
            card.show(getString(R.string.home_stat_empty_value), R.string.home_vet_none_pill, PillTone.NEUTRAL)
        } else {
            card.statValue.text = vetDateFormat.format(Date(visit.dueAtMillis))
            card.statPill.text = visit.petName
            card.statPill.applyTone(PillTone.ACCENT)
        }
    }

    /** Fills a stat card's figure and pill in one call. */
    private fun ItemHomeStatBinding.show(value: String, pillText: Int, tone: PillTone) {
        statValue.text = value
        statPill.setText(pillText)
        statPill.applyTone(tone)
    }

    /**
     * The three pill colourings: the neutral grey wash PetCare.Home.StatPill starts with, the mint pill
     * role for good news ("GOOD NOW", "WALKED"), and the accent for naming a pet.
     */
    private enum class PillTone { NEUTRAL, GOOD, ACCENT }

    /**
     * Recolours a stat pill.
     *
     * A style can only be applied when a view is inflated, and these pills change meaning as the day
     * goes on ("LATER" becomes "GOOD NOW"), so the colours are applied here instead. Theme roles are
     * resolved from the theme so the pale and deep versions follow light and dark mode — the mint pill
     * is a pale wash on the white card and a deep one on the charcoal card — while the accent is the
     * same in both.
     */
    private fun TextView.applyTone(tone: PillTone) {
        val (background, foreground) = when (tone) {
            PillTone.NEUTRAL -> themeColour(com.google.android.material.R.attr.colorSurfaceVariant) to
                themeColour(com.google.android.material.R.attr.colorOnSurfaceVariant)
            PillTone.GOOD -> themeColour(R.attr.colorPillMint) to themeColour(R.attr.colorOnPillMint)
            PillTone.ACCENT -> ContextCompat.getColor(context, R.color.accent_yellow) to
                ContextCompat.getColor(context, R.color.on_accent_yellow)
        }
        backgroundTintList = ColorStateList.valueOf(background)
        setTextColor(foreground)
    }

    // ---------------------------------------------------------------------------------------------
    // Next up
    // ---------------------------------------------------------------------------------------------

    /**
     * The dark next-up card: a countdown ring, the task, and "Mark done".
     *
     * **What the ring measures.** It fills over the last [RING_WINDOW_MINUTES] before the task is due —
     * empty two hours out, full at the moment itself — so it reads as "how close is this", which is the
     * question the number in its middle answers. A late task, a task with no set time and a finished day
     * all draw a full ring, since there is nothing left to count down to.
     *
     * From 100 minutes away the number switches to hours, because a three-digit count of minutes is
     * harder to read at a glance than "2 hrs". A late task keeps counting in minutes: lateness is urgent,
     * and "1 hr late" would hide how much later it has become.
     */
    private fun renderNextUp(state: HomeUiState) {
        val next = state.nextUp
        binding.nextUpRing.max = RING_WINDOW_MINUTES
        binding.markDoneButton.isVisible = next != null

        if (next == null) {
            val isDayDone = state.tasksDueToday > 0
            binding.nextUpTitleText.setText(
                if (isDayDone) R.string.home_next_up_done_title else R.string.home_next_up_none_title
            )
            binding.nextUpMetaText.setText(
                if (isDayDone) R.string.home_next_up_done_meta else R.string.home_next_up_none_meta
            )
            showRing(
                number = if (isDayDone) formatCount(state.tasksDoneToday) else getString(R.string.home_stat_empty_value),
                caption = if (isDayDone) getString(R.string.home_ring_done) else getString(R.string.home_ring_any_time),
                progress = if (isDayDone) RING_WINDOW_MINUTES else 0
            )
            return
        }

        binding.nextUpTitleText.text = next.task.title

        // "Krishala · 6:00 PM · Slicker brush": whichever of the three parts exist, joined.
        binding.nextUpMetaText.text = listOfNotNull(
            next.petName,
            next.task.timeOfDayMinutes?.let { formatTimeOfDay(requireContext(), it) },
            next.task.requiredSupplies?.takeIf { it.isNotBlank() }
        ).joinToString(getString(R.string.home_meta_separator))

        val minutes = next.minutesAway
        when {
            minutes == null -> showRing(
                number = getString(R.string.home_stat_empty_value),
                caption = getString(R.string.home_ring_any_time),
                progress = RING_WINDOW_MINUTES
            )

            minutes < 0 -> showRing(
                number = formatCount(abs(minutes)),
                caption = getString(R.string.home_ring_min_late),
                progress = RING_WINDOW_MINUTES
            )

            minutes < HOURS_THRESHOLD_MINUTES -> showRing(
                number = formatCount(minutes),
                caption = getString(R.string.home_ring_min_away),
                progress = RING_WINDOW_MINUTES - minutes
            )

            else -> {
                val hours = minutes / MINUTES_PER_HOUR
                showRing(
                    number = formatCount(hours),
                    caption = resources.getQuantityString(R.plurals.home_ring_hours_away, hours),
                    progress = (RING_WINDOW_MINUTES - minutes).coerceAtLeast(0)
                )
            }
        }

        // The task is captured now, so the button and the Snackbar's undo act on *this* task even if the
        // card moves on to the next one before they are pressed.
        val task = next.task
        binding.markDoneButton.setOnClickListener {
            viewModel.setTaskDone(task.id, true)
            Snackbar.make(binding.root, getString(R.string.home_marked_done, task.title), Snackbar.LENGTH_LONG)
                .setAction(R.string.action_undo) { viewModel.setTaskDone(task.id, false) }
                .show()
        }
    }

    /**
     * Sets the ring's number, caption and fill, and announces the pair as one phrase ("22 min away").
     * `setProgressCompat` animates from the previous value, so the ring creeps round as minutes pass
     * rather than jumping.
     */
    private fun showRing(number: String, caption: String, progress: Int) {
        binding.nextUpMinutesText.text = number
        binding.nextUpRingCaption.text = caption
        binding.nextUpRing.setProgressCompat(progress, true)
        binding.nextUpRingContainer.contentDescription = getString(R.string.cd_next_up_ring, number, caption)
    }

    // ---------------------------------------------------------------------------------------------
    // Today's care and the streak
    // ---------------------------------------------------------------------------------------------

    /**
     * The ring, its "1 / OF 6", and the headline.
     *
     * The ring's maximum is the day's total, so the progress can be the completed count itself rather
     * than a rounded percentage. A day with nothing due would give a maximum of zero, which the indicator
     * cannot divide by, so it is floored at one; the progress is zero then anyway.
     */
    private fun renderTodayCard(state: HomeUiState) {
        binding.todayProgressRing.max = state.tasksDueToday.coerceAtLeast(1)
        binding.todayProgressRing.setProgressCompat(state.tasksDoneToday, true)

        binding.todayDoneCount.text = formatCount(state.tasksDoneToday)
        binding.todayTotalCount.text = getString(R.string.home_ring_of_total, state.tasksDueToday)

        // One sentence for TalkBack rather than "1" and "of 6" as unrelated fragments. The plural is
        // chosen by the total, so a single task does not read as "1 of 1 tasks".
        binding.todayProgressLabels.contentDescription = resources.getQuantityString(
            R.plurals.cd_today_progress,
            state.tasksDueToday,
            state.tasksDoneToday,
            state.tasksDueToday
        )

        // Three sentences for what is arithmetically one figure: a finished day is an achievement, an
        // unplanned one is simply quiet, and "0 things left" would flatten both into an absence.
        binding.todayHeadlineText.text = when {
            state.tasksDueToday == 0 -> getString(R.string.home_nothing_scheduled)
            state.tasksLeftToday == 0 -> getString(R.string.home_all_caught_up)
            else -> resources.getQuantityString(
                R.plurals.home_things_left,
                state.tasksLeftToday,
                state.tasksLeftToday
            )
        }
    }

    /**
     * The seven streak boxes. Each box is only told two facts — full, and today — and bg_streak_day.xml
     * turns those into yellow, white or outlined.
     */
    private fun renderStreak(state: HomeUiState) {
        streakCells.forEachIndexed { index, cell ->
            val day = state.streak.getOrNull(index) ?: return@forEachIndexed
            cell.dayBox.isActivated = day.isFull
            cell.dayBox.isSelected = day.isToday
            cell.dayLabel.text = streakWeekdayFormat.format(Date(day.dayStart))
        }

        binding.streakCountText.text = resources.getQuantityString(
            R.plurals.home_streak_full_days,
            state.streakFullDays,
            state.streakFullDays
        )
        binding.streakRow.contentDescription =
            resources.getQuantityString(R.plurals.cd_streak, state.streakFullDays, state.streakFullDays)
    }

    // ---------------------------------------------------------------------------------------------
    // Insight and coming up
    // ---------------------------------------------------------------------------------------------

    /**
     * The insight sentence for whichever case [HomeDashboard.insightFor] chose.
     *
     * Task titles are dropped into the middle of a sentence, so their first letter is lower-cased —
     * "front-load his Walk" reads as a typo. Only the first letter, so a title like "Give Max his
     * pill" keeps its proper noun.
     */
    private fun insightText(insight: Insight): String = when (insight) {
        Insight.NothingScheduled -> getString(R.string.home_insight_nothing_scheduled)

        is Insight.AllDone ->
            resources.getQuantityString(R.plurals.home_insight_all_done, insight.fullDays, insight.fullDays)

        is Insight.BusiestPet -> resources.getQuantityString(
            // "so the evening is clear" only makes sense when the later task falls in the evening.
            if (insight.laterTaskMinutes >= EVENING_START_MINUTES) {
                R.plurals.home_insight_busiest_evening
            } else {
                R.plurals.home_insight_busiest_day
            },
            // The plural form follows the total, the number the word "tasks" belongs to.
            insight.totalOpenCount,
            insight.petName,
            insight.petOpenCount,
            insight.totalOpenCount,
            getString(
                when (insight.petGender) {
                    PetGender.MALE -> R.string.home_pronoun_his
                    PetGender.FEMALE -> R.string.home_pronoun_her
                    PetGender.UNKNOWN -> R.string.home_pronoun_their
                }
            ),
            insight.firstTaskTitle.inSentence(),
            formatTimeOfDay(requireContext(), insight.laterTaskMinutes),
            insight.laterTaskTitle.inSentence()
        )

        is Insight.Overdue -> resources.getQuantityString(
            R.plurals.home_insight_overdue,
            insight.overdueCount,
            insight.taskTitle,
            insight.petName,
            insight.overdueCount
        )

        is Insight.NextTask -> {
            val time = insight.timeOfDayMinutes
            if (time != null) {
                resources.getQuantityString(
                    R.plurals.home_insight_next_timed,
                    insight.totalOpenCount,
                    insight.totalOpenCount,
                    insight.taskTitle.inSentence(),
                    insight.petName,
                    formatTimeOfDay(requireContext(), time)
                )
            } else {
                resources.getQuantityString(
                    R.plurals.home_insight_next_untimed,
                    insight.totalOpenCount,
                    insight.totalOpenCount,
                    insight.taskTitle.inSentence(),
                    insight.petName
                )
            }
        }
    }

    /** A title as it reads mid-sentence: first letter lower-cased in the user's locale. */
    private fun String.inSentence(): String = replaceFirstChar { it.lowercase(Locale.getDefault()) }

    /** The week-ahead rows, their count, and the empty line in their place when there are none. */
    private fun renderComingUp(items: List<ComingUpItem>) {
        comingUpAdapter?.submitList(items)
        binding.comingUpRecyclerView.isVisible = items.isNotEmpty()
        binding.comingUpEmptyText.isVisible = items.isEmpty()
        binding.comingUpCountText.text =
            resources.getQuantityString(R.plurals.home_coming_up_count, items.size, items.size)
    }

    // ---------------------------------------------------------------------------------------------
    // Navigation and small helpers
    // ---------------------------------------------------------------------------------------------

    /** Opens an empty pet form. [PetViewModel.NO_PET_ID] is what tells it to create rather than edit. */
    private fun openNewPetForm() {
        findNavController().navigate(
            R.id.action_home_to_add_edit_pet,
            bundleOf(
                AddEditPetFragment.ARG_PET_ID to PetViewModel.NO_PET_ID,
                // The form needs the owner so a newly created pet is attached to this account.
                AddEditPetFragment.ARG_OWNER_ID to sessionViewModel.userId.value
            )
        )
    }

    /** Opens a pet's detail screen, from a card in the sideways row. */
    private fun openPetDetail(petId: Long) {
        findNavController().navigate(
            R.id.action_home_to_pet_detail,
            bundleOf(PetDetailFragment.ARG_PET_ID to petId)
        )
    }

    /** Opens the routine a "coming up" item belongs to. */
    private fun openRoutine(routineId: Long) {
        findNavController().navigate(
            R.id.action_home_to_routine_detail,
            bundleOf(RoutineDetailFragment.ARG_ROUTINE_ID to routineId)
        )
    }

    /**
     * Opens the account screen, from the pet-count badge — this tab hides the shared toolbar that carries
     * the account actions elsewhere. Signing out lives on the profile screen itself.
     */
    private fun openProfile() {
        findNavController().navigate(R.id.profileFragment)
    }

    /**
     * Switches to another bottom-navigation tab, for the "Add task" and "Log expense" quick actions.
     *
     * **Why a tab rather than straight to a form.** Neither a task nor an expense can be created from
     * nothing: a task belongs to a routine and an expense to a pet, so both forms need an argument the
     * dashboard cannot choose on the user's behalf. The tab that owns each is where that choice is made,
     * one tap before the form. Guessing a pet here would file things under the wrong animal.
     *
     * **Why by destination id and not an action.** The tabs are peers rather than steps in a journey, so
     * the graph declares no actions between them (see nav_graph.xml). These are the options NavigationUI
     * applies when a tab is tapped in the bar, so arriving this way is indistinguishable from arriving
     * that way: the back stack does not grow, the tab left behind keeps its state, and the bar moves its
     * highlight by itself.
     */
    private fun openTab(destinationId: Int) {
        findNavController().navigate(
            destinationId,
            null,
            navOptions {
                popUpTo(findNavController().graph.startDestinationId) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        )
    }

    /**
     * Opens the assistant, from the header's AI pill and from the "Ask the assistant" button.
     *
     * Both controls lead to the same place, which is why they share one method: the pill is the
     * shortcut for someone who already knows what it is, and the button is the invitation for
     * someone who does not.
     *
     * A normal forward navigation with an action, unlike the tab switches in [openTab]: the
     * assistant is somewhere the user has gone *from* the dashboard, so the back stack should grow
     * and the system back button should return here.
     */
    private fun openAssistant() {
        findNavController().navigate(R.id.action_home_to_assistant)
    }

    /**
     * A bare number in the locale's own digits. `toString()` always gives Western Arabic digits, where a
     * device set to, say, Bengali expects its own numerals; [NumberFormat] asks the locale.
     */
    private fun formatCount(value: Int): String = NumberFormat.getInstance().format(value)

    /**
     * Reads a colour role from the current theme, so it follows light and dark mode. [MaterialColors]
     * resolves the attribute through this view's themed context.
     */
    @ColorInt
    private fun themeColour(@AttrRes attribute: Int): Int = MaterialColors.getColor(binding.root, attribute)

    override fun onDestroyView() {
        super.onDestroyView()
        // Detach the adapters: a RecyclerView keeps its adapter and the adapter its holders, which would
        // keep the destroyed hierarchy alive behind them.
        binding.petsRecyclerView.adapter = null
        binding.comingUpRecyclerView.adapter = null
        petAdapter = null
        comingUpAdapter = null
        streakCells = emptyList()
        _binding = null
    }

    private companion object {
        /** The ring fills over the last two hours before the next task. See [renderNextUp]. */
        const val RING_WINDOW_MINUTES = 120

        /** From this many minutes away, the ring's number is shown in hours. */
        const val HOURS_THRESHOLD_MINUTES = 100

        const val MINUTES_PER_HOUR = 60

        /** 17:00 — from here a later task is "the evening" in the busiest-pet insight. */
        const val EVENING_START_MINUTES = 17 * 60
    }
}
