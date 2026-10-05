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
import com.prash.petcare.data.local.CurrencyPreference
import com.prash.petcare.data.local.entity.PetGender
import com.prash.petcare.data.repository.WeatherState
import com.prash.petcare.data.sync.SyncStatus
import com.prash.petcare.databinding.FragmentHomeBinding
import com.prash.petcare.databinding.ItemHomeStatBinding
import com.prash.petcare.databinding.ItemStreakDayBinding
import com.prash.petcare.ui.common.bindHeaderFromSession
import com.prash.petcare.ui.common.formatCurrency
import com.prash.petcare.ui.common.formatTimeOfDay
import com.prash.petcare.ui.common.openProfile
import com.prash.petcare.ui.common.setUp
import com.prash.petcare.ui.common.showAssistantPill
import com.prash.petcare.ui.pet.AddEditPetFragment
import com.prash.petcare.ui.pet.PetDetailFragment
import com.prash.petcare.ui.pet.PetViewModel
import com.prash.petcare.ui.place.LocationPermissionRequester
import com.prash.petcare.ui.routine.RoutineDetailFragment
import com.prash.petcare.ui.session.SessionViewModel
import com.prash.petcare.util.WalkComfort
import java.text.NumberFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** The Home tab: the dashboard the app opens on once signed in. */
class HomeFragment : Fragment() {

    /** ViewBinding for fragment_home.xml. */
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    /** Which account is signed in, read from the Activity-wide session rather than a navigation argument. */
    private val sessionViewModel: SessionViewModel by activityViewModels { SessionViewModel.Factory }

    private val viewModel: HomeViewModel by viewModels {
        HomeViewModel.factory(sessionViewModel.userId.value)
    }

    /** The pet-card adapter, rebuilt with each view rather than held for the fragment's whole life. */
    private var petAdapter: HomePetAdapter? = null

    /** The "coming up this week" rows. Held per view like [petAdapter], for symmetry and to be detached. */
    private var comingUpAdapter: ComingUpAdapter? = null

    /** The seven streak boxes in order, so box `i` always shows `state.streak[i]`. */
    private var streakCells: List<ItemStreakDayBinding> = emptyList()

    /** The latest sync status, kept so the "3 min ago" line can be redrawn when the clock ticks as well. */
    private var latestSyncStatus: SyncStatus = SyncStatus.Idle

    /** The currency symbol the spend card writes, kept for the same reason as [latestSyncStatus]. */
    private var latestCurrencySymbol: String = CurrencyPreference.NPR.symbol

    /** The latest temperature, and the latest scheduled-walk state. */
    private var latestWeather: WeatherState = WeatherState.Loading
    private var latestWalkWindow: WalkWindow = WalkWindow.NoneToday

    /** Approximate location for the weather card, asked for the first time the dashboard is shown. */
    private val locationPermission = LocationPermissionRequester(
        fragment = this,
        rationaleMessage = R.string.permission_location_weather_message,
        // Granted: fetch again straight away.
        onGranted = { viewModel.refreshWeather() },
        onDenied = { viewModel.refreshWeather() },
        permissions = arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION)
    )

    /** The clock time of the latest dashboard state. */
    private var latestNowMillis: Long = 0L

    /** Date formatters, built on first use with the locale in force at that moment. */
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

    /** The short month name for the spending card, in UTC. */
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
        binding.header.setUp(
            title = R.string.nav_home,
            onBadgeClick = ::openNotifications,
            onMonogramClick = ::openProfile
        )

        // Keeps the header's mark showing the user's profile photo, falling back to the app's monogram.
        bindHeaderFromSession(binding.header, sessionViewModel)
        binding.header.showAssistantPill(::openAssistant)

        streakCells = listOf(
            binding.streakDay0, binding.streakDay1, binding.streakDay2, binding.streakDay3,
            binding.streakDay4, binding.streakDay5, binding.streakDay6
        )

        setUpLists()
        setUpClickListeners()
        observeViewModel()
    }

    /** Refreshes the temperature each time the dashboard comes into view, and asks for location once. */
    override fun onStart() {
        super.onStart()
        viewModel.refreshWeather()

        if (!locationPermission.hasPermission() && viewModel.markLocationAsked()) {
            locationPermission.request()
        }
    }

    /** Prepares the two lists. */
    private fun setUpLists() {
        petAdapter = HomePetAdapter(
            photoScope = viewLifecycleOwner.lifecycleScope,
            onPetClick = ::openPetDetail
        )
        binding.petsRecyclerView.apply {
            // HORIZONTAL is what makes this the sideways row; the layout manager, not the adapter.
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

    /** Subscribes to the ViewModel. */
    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.uiState.collect(::render) }
                launch {
                    // Kept and re-rendered rather than folded into the state.
                    viewModel.currencySymbol.collect { symbol ->
                        latestCurrencySymbol = symbol
                        viewModel.uiState.value.let(::renderSpending)
                    }
                }
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

        // The dashboard and the empty state swap as a unit. Both stay hidden while loading.
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

    // Greeting.

    /** "Good evening, / Prashant", with the clock and date on the right. */
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

    /** The sync line under the greeting: a coloured dot and a short phrase, or nothing at all. */
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

    /** "3 min. ago", produced by [DateUtils] so it arrives already translated. */
    private fun relativeTime(atMillis: Long, nowMillis: Long): CharSequence =
        DateUtils.getRelativeTimeSpanString(
            atMillis,
            maxOf(nowMillis, atMillis),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE
        )

    // Stat cards.

    /** The walk window card: "WALK WINDOW / 18°C / GOOD NOW", as the design has it. */
    private fun renderWalkWindow() {
        val binding = _binding ?: return
        val card = binding.statWalk
        card.statLabel.setText(R.string.home_stat_walk_window)

        when (val weather = latestWeather) {
            is WeatherState.Available -> {
                // Whole degrees: the card is a glance.
                card.show(
                    getString(R.string.home_weather_temperature, weather.temperatureCelsius.roundToInt()),
                    // The comfortable case reuses the card's existing "Good now" wording.
                    when (weather.comfort) {
                        WalkComfort.GOOD -> R.string.home_walk_good_now
                        WalkComfort.TOO_HOT -> R.string.home_weather_too_hot
                        WalkComfort.TOO_COLD -> R.string.home_weather_too_cold
                    },
                    // Mint for good walking weather, as the design shows.
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

    /** The walk-window card's fallback, used when no temperature can be had at all. */
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

    /** "SPENT · SEP / Rs 5,240 / +12% VS AUG". */
    private fun renderSpending(state: HomeUiState) {
        val card = binding.statSpent
        val month = state.spending ?: return

        card.statLabel.text = getString(R.string.home_stat_spent_format, monthFormat.format(Date(month.monthStart)))
        card.statValue.text = formatCurrency(month.total, latestCurrencySymbol)

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

    /** The three pill colourings: the neutral grey wash PetCare.Home.StatPill starts. */
    private enum class PillTone { NEUTRAL, GOOD, ACCENT }

    /** Recolours a stat pill. */
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

    // Next up.

    /** The dark next-up card: a countdown ring, the task, and "Mark done". */
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

        // The task is captured now.
        val task = next.task
        binding.markDoneButton.setOnClickListener {
            viewModel.setTaskDone(task.id, true)
            Snackbar.make(binding.root, getString(R.string.home_marked_done, task.title), Snackbar.LENGTH_LONG)
                .setAction(R.string.action_undo) { viewModel.setTaskDone(task.id, false) }
                .show()
        }
    }

    /** Sets the ring's number, caption and fill, and announces the pair as one phrase ("22 min away"). */
    private fun showRing(number: String, caption: String, progress: Int) {
        binding.nextUpMinutesText.text = number
        binding.nextUpRingCaption.text = caption
        binding.nextUpRing.setProgressCompat(progress, true)
        binding.nextUpRingContainer.contentDescription = getString(R.string.cd_next_up_ring, number, caption)
    }

    // Today's care and the streak.

    /** The ring, its "1 / OF 6", and the headline. */
    private fun renderTodayCard(state: HomeUiState) {
        binding.todayProgressRing.max = state.tasksDueToday.coerceAtLeast(1)
        binding.todayProgressRing.setProgressCompat(state.tasksDoneToday, true)

        binding.todayDoneCount.text = formatCount(state.tasksDoneToday)
        binding.todayTotalCount.text = getString(R.string.home_ring_of_total, state.tasksDueToday)

        // One sentence for TalkBack rather than "1" and "of 6" as unrelated fragments.
        binding.todayProgressLabels.contentDescription = resources.getQuantityString(
            R.plurals.cd_today_progress,
            state.tasksDueToday,
            state.tasksDoneToday,
            state.tasksDueToday
        )

        // Three sentences for what is arithmetically one figure: a finished day is an achievement.
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

    /** The seven streak boxes. Each box is only told two facts — full, and today. */
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

    // Insight and coming up.

    /** The insight sentence for whichever case [HomeDashboard.insightFor] chose. */
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

    // Navigation and small helpers.

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

    /** Opens the notifications screen, from the pet-count badge — as every screen that draws this header does. */
    private fun openNotifications() {
        findNavController().navigate(R.id.notificationsFragment)
    }

    /** Switches to another bottom-navigation tab, for the "Add task" and "Log expense" quick actions. */
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

    /** Opens the assistant, from the header's AI pill and from the "Ask the assistant" button. */
    private fun openAssistant() {
        findNavController().navigate(R.id.action_home_to_assistant)
    }

    /** A bare number in the locale's own digits. `toString()` always gives Western Arabic digits. */
    private fun formatCount(value: Int): String = NumberFormat.getInstance().format(value)

    /** Reads a colour role from the current theme, so it follows light and dark mode. */
    @ColorInt
    private fun themeColour(@AttrRes attribute: Int): Int = MaterialColors.getColor(binding.root, attribute)

    override fun onDestroyView() {
        super.onDestroyView()
        // Detach the adapters: a RecyclerView keeps its adapter and the adapter its holders.
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
