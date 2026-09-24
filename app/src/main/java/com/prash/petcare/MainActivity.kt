package com.prash.petcare

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.Surface
import android.view.TextureView
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.navOptions
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.onNavDestinationSelected
import androidx.navigation.ui.setupActionBarWithNavController
import com.prash.petcare.databinding.ActivityMainBinding
import com.prash.petcare.ui.common.NavTab
import com.prash.petcare.ui.session.SessionViewModel
import kotlinx.coroutines.launch

/**
 * The app's only Activity.
 *
 * It owns the window, the top app bar, the bottom navigation bar and the NavHostFragment; every
 * screen is a fragment swapped in by the Navigation Component. This is the single-Activity
 * architecture Google recommends, with navigation described declaratively in
 * res/navigation/nav_graph.xml.
 *
 * It holds no business logic of its own. What logic it does contain is navigation chrome —
 * which bars are visible on which screen — and one account action, signing out, which belongs here
 * because the menu it is triggered from belongs to this Activity's toolbar rather than to any
 * fragment.
 */
class MainActivity : AppCompatActivity() {

    /**
     * ViewBinding for activity_main.xml.
     *
     * `lateinit` is safe here, unlike in a fragment: an Activity's views live exactly as long as
     * the Activity itself, so there is no window in which the binding would outlive them.
     */
    private lateinit var binding: ActivityMainBinding

    private lateinit var navController: NavController

    /**
     * Who is signed in, shared with every fragment.
     *
     * Obtained here as well as in the fragments so that signing out has something to clear. `by
     * viewModels()` on the Activity and `by activityViewModels()` in a fragment resolve to the same
     * instance — see [SessionViewModel] for why the account lives at this level at all.
     */
    private val sessionViewModel: SessionViewModel by viewModels { SessionViewModel.Factory }

    /**
     * Tells NavigationUI which destinations are the top of a journey.
     *
     * Anything listed here shows no back arrow, because there is nowhere above it to go; every
     * other destination gets one automatically.
     *
     * The set is the five bottom-bar destinations plus the log in / register screen. The five are
     * peers rather than a hierarchy — moving from Pets to Expenses is a switch, not a step forward, so
     * offering "back" from any of them would be offering to undo something the user did not do.
     * Log in is the app's entry point and has nothing above it either.
     *
     * The two-factor and password-reset screens are deliberately *not* here: they are reached from
     * the log in screen and back should return there.
     */
    private val appBarConfiguration = AppBarConfiguration(
        setOf(
            R.id.authFragment,
            R.id.homeFragment,
            R.id.petsFragment,
            R.id.checklistFragment,
            R.id.placesFragment,
            R.id.expensesFragment
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        // Hands the window back from the launch theme to the real one. The Activity is declared in
        // the manifest with Theme.PetCare.Splash, whose only job is to make the placeholder window
        // the system paints *before this line runs* the same cream as the opening animation — see
        // that style in themes.xml. From here on the app wants its ordinary theme, and this has to
        // happen before super.onCreate, which is where the window's decor is created from whichever
        // theme is current.
        setTheme(R.style.Theme_PetCare)

        super.onCreate(savedInstanceState)

        // Draws the app behind the status and navigation bars for a modern full-height look. The
        // inset handling below is what stops content hiding underneath them.
        enableEdgeToEdge()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // The opening video plays only on a genuine cold start. A savedInstanceState means the
        // Activity is being rebuilt — a rotation, a theme switch, the system reclaiming memory — and
        // the user is already inside the app; making them sit through the opening again every time
        // they turned the phone would be an interruption, not a welcome.
        if (savedInstanceState == null) startIntroVideo() else binding.splashOverlay.isVisible = false

        // Order matters: the inset listener decides whether the bottom tabs are shown, which depends
        // on the current destination, so the NavController has to exist before it can first fire.
        setUpNavigation()
        applyWindowInsets()
    }

    /**
     * Starts the opening animation as soon as there is something to draw it on.
     *
     * A [TextureView] has no surface to play into until it has been laid out and attached, so the
     * player cannot simply be started here — it has to wait to be told the surface exists, which is
     * what the listener below is for. Everything else about the video is set up in
     * [playIntroVideo], which that callback leads to.
     *
     * The app carries on starting up behind this the whole time. Nothing waits for the video: it is
     * played *over* an Activity that is already building its screens, so the animation costs the
     * user nothing — which is the difference between this and a splash screen that simply sleeps.
     * By the time the overlay fades, navigation has already settled on whichever destination the app
     * would have opened on anyway, so the animation never decides where the user lands.
     *
     * **The budget is started here rather than when playback begins**, and the distinction is the
     * whole of the promise that this cannot slow a slow device down. Started at playback, a phone
     * that took a second to lay out the surface and another to open the decoder would spend that
     * time *before* the clock even began, and the splash would last as long as the device was slow.
     * Started here, the budget is measured from the app opening, so [INTRO_BUDGET_MILLIS] is the
     * longest the animation can ever hold anybody — a slower device simply sees less of it.
     */
    private fun startIntroVideo() {
        // Tap anywhere to skip. Somebody opening the app to tick off a task has seen the animation
        // before and wants the app, not the logo — and a splash that cannot be dismissed is the
        // difference between a welcome and an obstacle. The overlay is already clickable and
        // focusable (see activity_main.xml) so that taps cannot fall through to the screen behind;
        // this is what makes that swallowed tap do something useful instead of nothing.
        binding.splashOverlay.setOnClickListener { dismissIntro() }

        // The deadline for the whole animation, posted before anything has been prepared so that it
        // covers every way this can fail to end on its own as well as the clip simply running long:
        // a surface that never arrives, a decoder that never opens, a player that stalls.
        binding.root.postDelayed(introTimeout, INTRO_BUDGET_MILLIS)

        // The status and navigation bar icons are drawn over the cream overlay for as long as it is
        // up, so they have to be dark to be seen. enableEdgeToEdge has just set them to follow the
        // app's theme, which in the dark theme means light icons — invisible against cream. This
        // overrides that for the duration; dismissIntro hands them back.
        WindowInsetsControllerCompat(window, binding.root).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        binding.splashVideo.surfaceTextureListener = object : TextureView.SurfaceTextureListener {

            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                playIntroVideo(Surface(surface))
            }

            /** The video is scaled to the view, so a size change means re-scaling it. */
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                introPlayer?.let { fillVideoToView(it.videoWidth, it.videoHeight) }
            }

            /**
             * Returning true hands the surface back to the system to release. The player is stopped
             * first, because a MediaPlayer still holding a destroyed surface is what produces the
             * "dequeueBuffer: BufferQueue has been abandoned" crashes.
             */
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                releaseIntroPlayer()
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
    }

    /**
     * Plays res/raw/splash_animation.mp4 once, then dissolves the overlay to reveal the app.
     *
     * **Every way this can fail ends the same way.** A missing or unplayable file, a codec the
     * device does not have, a video that never reports finishing — each of them calls
     * [dismissIntro], so the worst case is that the user sees the app's own cream for a moment
     * instead of an animation. An opening screen that can trap somebody in it is far worse than one
     * that does not play, which is why the budget posted in [startIntroVideo] exists as well as the
     * error handler below.
     *
     * **Prepared asynchronously.** `prepare()` reads and decodes the file's headers on the calling
     * thread, and that thread here is the one drawing the app's first screen. `prepareAsync` moves
     * it off, and playback starts from the callback.
     *
     * **Silent, deliberately.** An app that makes a noise the moment it is opened — in a lecture, on
     * a bus, at 6am — is a bad citizen, and the system volume is not the app's to read. The
     * animation has to work without sound, so it is played without it.
     */
    private fun playIntroVideo(surface: Surface) {
        val player = MediaPlayer()
        introPlayer = player

        try {
            resources.openRawResourceFd(R.raw.splash_animation).use { descriptor ->
                player.setDataSource(
                    descriptor.fileDescriptor,
                    descriptor.startOffset,
                    descriptor.length
                )
            }
        } catch (e: Exception) {
            // Resources.NotFoundException if the file is missing, IOException if it cannot be read.
            // Either way there is no animation to show, so the app simply opens.
            Log.w(TAG, "Opening video could not be opened", e)
            dismissIntro()
            return
        }

        player.setSurface(surface)
        player.setVolume(0f, 0f)

        player.setOnPreparedListener {
            fillVideoToView(player.videoWidth, player.videoHeight)

            // Played faster than it was authored. The clip runs 3.6 seconds, which is a long time to
            // hold somebody who opened the app to tick a task off — but simply cutting it at a
            // deadline means the animation never reaches the logo it is building towards, so the one
            // thing it exists to show is the thing the user never sees. Speeding it up keeps the
            // whole animation, beginning to end, and just gets there sooner. See INTRO_SPEED.
            //
            // Set here rather than before prepare(): playback parameters can only be applied to a
            // player that has been prepared, and setting them on one that has not throws.
            runCatching { player.playbackParams = player.playbackParams.setSpeed(INTRO_SPEED) }

            player.start()
        }
        player.setOnCompletionListener { dismissIntro() }
        player.setOnErrorListener { _, what, extra ->
            Log.w(TAG, "Opening video failed: what=$what extra=$extra")
            dismissIntro()
            // True: the error is handled here, so the player must not also report it as unhandled.
            true
        }

        // Not prepare(). That call reads and decodes the file's headers on the calling thread, and
        // the thread here is the one drawing the app's first screen — on a slow device it is exactly
        // the stall this whole design exists to avoid. prepareAsync moves it off and calls back above.
        player.prepareAsync()
    }

    /**
     * Scales the video to *cover* the screen, cropping whatever overflows.
     *
     * A TextureView stretches whatever is played into it to its own shape, which on a phone means a
     * distorted video. The transform below undoes that: it scales the stretched picture back to the
     * clip's real proportions, taking the *larger* of the two ratios so the frame covers the view
     * completely, and centring it so what is cropped is taken evenly from both sides.
     *
     * **Cropped rather than letterboxed, and this was changed after seeing it run.** Fitting the clip
     * inside the screen is the gentler option in principle — nothing is thrown away — but it leaves a
     * band above and below, and a band is only invisible if it is the same colour as the video's own
     * ground. This animation's ground is a bright yellow, and the overlay behind it is the app's
     * cream, so fitting drew the animation in a plainly visible cream-edged box. Covering removes the
     * question: there is no band to match, at any screen shape.
     *
     * What it costs is the edges of the frame. On a phone the clip's 9:16 is *narrower* than the
     * screen is tall, so covering crops the left and right — around an eighth from each side on a
     * 20:9 display — and keeps the full height, including the shape that enters from the top edge.
     * The animation's subject is centred, so nothing that matters is inside the part that goes.
     */
    private fun fillVideoToView(videoWidth: Int, videoHeight: Int) {
        val view = binding.splashVideo
        // Guards a division by zero: a player that has not resolved the track yet reports 0 × 0.
        if (videoWidth <= 0 || videoHeight <= 0 || view.width == 0 || view.height == 0) return

        val viewWidth = view.width.toFloat()
        val viewHeight = view.height.toFloat()
        val scale = maxOf(viewWidth / videoWidth, viewHeight / videoHeight)

        Matrix().apply {
            setScale(
                videoWidth * scale / viewWidth,
                videoHeight * scale / viewHeight,
                viewWidth / 2f,
                viewHeight / 2f
            )
            view.setTransform(this)
        }
    }

    /**
     * Fades the overlay away and lets go of the player.
     *
     * Called from five places that can all be right — the video ending, the user tapping to skip, an
     * error, the budget running out, and the surface being destroyed — so it does nothing after the
     * first. Without that guard a video that finished just as the budget expired would start the
     * fade twice and jump.
     */
    private fun dismissIntro() {
        if (isIntroDismissed) return
        isIntroDismissed = true

        binding.root.removeCallbacks(introTimeout)
        releaseIntroPlayer()

        // Puts the status and navigation bar icons back under the theme's control. They were forced
        // dark in startIntroVideo to be legible on the cream overlay; the app underneath is cream in
        // the light theme and near-black in the dark one, and this recomputes which they need to be.
        enableEdgeToEdge()

        binding.splashOverlay.animate()
            .alpha(0f)
            .setDuration(INTRO_FADE_MILLIS)
            // Gone rather than left at zero alpha: an invisible full-screen view would still be
            // measured on every layout pass, and would still be the thing every tap landed on.
            .withEndAction { binding.splashOverlay.isVisible = false }
            .start()
    }

    /**
     * Stops and frees the player.
     *
     * A MediaPlayer holds a decoder, and there are only so many of those on a device: one left
     * running keeps hardware other apps need. Safe to call more than once, and safe to call before
     * the player was ever created.
     */
    private fun releaseIntroPlayer() {
        introPlayer?.apply {
            // The player may still be preparing, where stop() is an illegal state rather than a
            // no-op. release() is valid from every state, so the stop is attempted and forgiven.
            runCatching { stop() }
            release()
        }
        introPlayer = null
    }

    override fun onDestroy() {
        super.onDestroy()
        // The Activity can be torn down mid-animation — a back press on the first screen, the system
        // reclaiming memory — and the player would outlive the window it was drawing into.
        releaseIntroPlayer()
    }

    /**
     * The opening animation's deadline, held as one object rather than written as `::dismissIntro`
     * at each of the two places it is posted and cancelled.
     *
     * A method reference makes a *new* object every time it is evaluated, so posting one and then
     * cancelling another would cancel nothing: the deadline would still fire, after the video had
     * already finished and the overlay had gone — dropping the user back through a fade into a
     * screen they were already using. Keeping the one instance is what makes the cancel in
     * [dismissIntro] actually cancel it.
     */
    private val introTimeout = Runnable { dismissIntro() }

    /** The opening video's player while it is playing, and null at every other moment. */
    private var introPlayer: MediaPlayer? = null

    /** Whether the overlay has already been dismissed. See [dismissIntro]. */
    private var isIntroDismissed = false

    /** Connects the top app bar and the bottom navigation bar to the navigation graph. */
    private fun setUpNavigation() {
        // The NavController belongs to the NavHostFragment, and is reached through the fragment
        // manager rather than findNavController() — at this point in onCreate the host's view is
        // not yet attached, and the shortcut would fail.
        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController

        // Makes the MaterialToolbar act as the Activity's action bar, so NavigationUI can drive it.
        setSupportActionBar(binding.topAppBar)

        // Sets the title from each destination's android:label and manages the back arrow, both
        // automatically on every navigation. Fragments whose title depends on their data override
        // it afterwards — see ui.common.setToolbarTitle.
        setupActionBarWithNavController(navController, appBarConfiguration)

        // The bottom bar's outward half: a tap asks this Activity to navigate. The bar does not
        // light the tab itself — it waits to be told by the listener below, which is what stops a
        // tap that navigation declines to honour leaving the bar pointing at a screen nobody is on.
        binding.navTabs.onTabSelected = ::navigateToTab

        // Added *after* setupActionBarWithNavController, so this listener runs after NavigationUI's
        // own and can correct the two things NavigationUI cannot know about — see updateChromeFor
        // and restoreUpArrowForPetScopedScreen. It also fires once immediately with the current
        // destination, which is what lights the right tab after a rotation.
        navController.addOnDestinationChangedListener { _, destination, arguments ->
            updateChromeFor(destination.id, arguments)
            restoreUpArrowForPetScopedScreen(destination.id, arguments)
            highlightTabFor(destination.id)
        }

        observeOpenTaskCount()
    }

    /**
     * Navigates to a tapped tab, with the behaviour a set of tabs is expected to have.
     *
     * **Why this is written out rather than left to `setupWithNavController`.** NavigationUI drives
     * a bar from a *menu*, matching each item's id to a destination's. The new bar has no menu —
     * it cannot, because a menu item has nowhere to say that its tab should widen, or carry a label
     * that appears only when it does — so the wiring a menu bought has to be supplied here. The
     * options below are deliberately the same ones NavigationUI builds, so nothing about how the app
     * actually moves between tabs has changed:
     *
     * - **popUpTo the graph's start, saving state.** Tabs are peers, not a stack. Without this,
     *   hopping between them would pile five screens up and the system back button would walk back
     *   through every tab the user had visited. `saveState` is what lets each tab keep its scroll
     *   position and filters while it is away.
     * - **restoreState** puts that saved state back on return, so a tab is where the user left it.
     * - **launchSingleTop** stops a second copy of a tab being stacked on the one already showing —
     *   which, combined with the pop above, is what makes re-tapping the current tab return to its
     *   root rather than do nothing or duplicate it.
     * - The four animations are the Navigation library's own defaults, named explicitly because they
     *   came free with NavigationUI and their absence would be a visible change to every tab switch.
     */
    private fun navigateToTab(tab: NavTab) {
        navController.navigate(
            tab.destinationId,
            null,
            navOptions {
                launchSingleTop = true
                restoreState = true
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                anim {
                    enter = androidx.navigation.ui.R.anim.nav_default_enter_anim
                    exit = androidx.navigation.ui.R.anim.nav_default_exit_anim
                    popEnter = androidx.navigation.ui.R.anim.nav_default_pop_enter_anim
                    popExit = androidx.navigation.ui.R.anim.nav_default_pop_exit_anim
                }
            }
        )
    }

    /**
     * Lights the tab the destination just arrived at belongs to.
     *
     * The inward half of the wiring NavigationUI used to provide, and it does one thing the menu
     * version could not: a tab stays lit for the screens *underneath* it, so opening a pet and then
     * adding a routine to it keeps Pets marked all the way down. Which screens belong to which tab is
     * declared in [NavTab].
     *
     * Destinations belonging to no tab — the profile, the notifications list, the assistant, all
     * reached from the shared header rather than from the bar — deliberately change nothing. They
     * are a step sideways available from all five tabs, so leaving the tab the user came from lit is
     * what tells them where they will be when they come back.
     */
    private fun highlightTabFor(destinationId: Int) {
        NavTab.forDestination(destinationId)?.let(binding.navTabs::selectTab)
    }

    /**
     * Keeps the Tasks tab's badge showing how many care tasks are still open today.
     *
     * Collected here rather than in a fragment because the bar belongs to this Activity and is on
     * screen for all five tabs and every detail screen under them — see [SessionViewModel.openTasksToday]
     * for why the count lives at that level too.
     *
     * `repeatOnLifecycle(STARTED)` is what stops the collection while the app is in the background:
     * the flow behind it is a Room query, and left running it would keep re-counting for a bar
     * nobody can see. It resumes with the current value when the Activity comes back.
     */
    private fun observeOpenTaskCount() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                sessionViewModel.openTasksToday.collect(binding.navTabs::setOpenTaskCount)
            }
        }
    }

    /**
     * Puts the back arrow back on the checklist and expenses screens when they were opened for one
     * particular pet.
     *
     * Those two destinations are each reached two ways — as a bottom-navigation tab, and from a
     * pet's detail page — and the two want opposite chrome. As a tab there is nothing above them, so
     * [appBarConfiguration] lists them as top-level and NavigationUI shows no arrow. Opened from a
     * pet, though, there very much is somewhere to go back to, and the user arrived by a deliberate
     * step they should be able to retrace.
     *
     * An AppBarConfiguration is fixed at construction and cannot express "top-level, except when
     * it isn't", so the arrow is restored here instead. The two cases are told apart by the argument
     * that already distinguishes them everywhere else: a real `petId` means the screen was opened
     * for one animal.
     *
     * Only ever turns the arrow *on*. Leaving the other cases to NavigationUI is what keeps this
     * from having to reimplement the rule for every other screen in the app.
     */
    private fun restoreUpArrowForPetScopedScreen(destinationId: Int, arguments: Bundle?) {
        val isDualPurposeScreen =
            destinationId == R.id.checklistFragment || destinationId == R.id.expensesFragment

        if (!isDualPurposeScreen) return

        val petId = arguments?.getLong(ARG_PET_ID, NO_PET_ID) ?: NO_PET_ID

        if (petId != NO_PET_ID) {
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
        }
    }

    /**
     * Shows or hides the navigation chrome for the destination just arrived at.
     *
     * Two destinations go without the shared top app bar, for different reasons.
     *
     * The sign-in screens (see [AUTH_DESTINATIONS]) carry their own large headings and their own "back
     * to log in" links, and belong to nobody, so all of it goes — the tabs as well: a row of tabs
     * leading into an account the user has not signed in to yet would be worse than no chrome at all.
     *
     * The **dashboard** and the **Pets**, **Tasks**, **Places** and **Money** tabs hide only the app
     * bar, and keep their tabs. They draw their own header — the monogram, the title, the theme toggle and the pet count,
     * shared as res/layout/view_tab_header.xml — because every one of those depends on the screen's
     * own data or state, which a toolbar shared by twelve destinations has no way to supply. Leaving
     * the shared bar visible above it would show the screen's name twice.
     *
     * The Tasks and Money destinations only count as tabs when they arrive with no `petId`. Opened for
     * one pet from its detail page, the same screens hide that header and rely on this toolbar instead,
     * for their title and back arrow — see [restoreUpArrowForPetScopedScreen].
     *
     * **The account actions used to be the one cost of hiding the bar, and no longer are.** The
     * header's count badge once doubled as the way into the profile screen; it now opens the
     * notifications screen, which is what a count with a red dot on it reads as. The account is
     * reached instead by pressing the app's **monogram** at the left of that same header — available
     * on every screen that draws it, which is precisely the set of screens this method hides the
     * toolbar on. The toolbar's overflow menu still leads there too, for the screens that show it.
     * See [com.prash.petcare.ui.common.setUp] and [com.prash.petcare.ui.common.openProfile].
     */
    private fun updateChromeFor(destinationId: Int, arguments: Bundle?) {
        val isAuthScreen = destinationId in AUTH_DESTINATIONS

        val hasNoPetArgument = (arguments?.getLong(ARG_PET_ID, NO_PET_ID) ?: NO_PET_ID) == NO_PET_ID
        val isTasksTab = destinationId == R.id.checklistFragment && hasNoPetArgument
        val isMoneyTab = destinationId == R.id.expensesFragment && hasNoPetArgument

        // The pet detail screen is not a tab, but it draws the same header — with the pet's name as
        // its title — and puts its back button on the photo instead of in a toolbar, as in the design.
        val hasOwnHeader = destinationId == R.id.homeFragment ||
            destinationId == R.id.petsFragment ||
            destinationId == R.id.petDetailFragment ||
            destinationId == R.id.placesFragment ||
            // "Add a place" is a step deeper into the Places tab, not a screen of its own: it draws
            // the same header with "Add place" as its title and its own round back button, and the
            // bottom bar keeps Places lit behind it.
            destinationId == R.id.addPlaceFragment ||
            // "Record a vaccination" stands in the same relation to the Pets tab: a step deeper into
            // it rather than a screen of its own, drawing the shared header with "Vaccination" as its
            // title and its own round back button, with Pets still lit below.
            destinationId == R.id.addVaccinationFragment ||
            // The assistant is not a tab, but it draws the same header — its own title, the theme
            // toggle and the pet badge — so the shared bar above it would show the name twice.
            destinationId == R.id.assistantFragment ||
            // The notifications screen, for the same reason, and reached the same way: from the
            // header's pet-count badge rather than from the bottom bar.
            destinationId == R.id.notificationsFragment ||
            // The profile screen, likewise — reached from the header's monogram, and drawing that
            // same header with "Profile" as its title.
            destinationId == R.id.profileFragment ||
            isTasksTab ||
            isMoneyTab

        binding.appBarLayout.isVisible = !isAuthScreen && !hasOwnHeader
        // Not set directly: whether the tabs show also depends on the keyboard, which this method
        // knows nothing about — see updateBottomNavVisibility.
        updateBottomNavVisibility()

        // The overflow menu holds the account actions, which only mean something once there is an
        // account. invalidateOptionsMenu asks the framework to rebuild the menu, which calls
        // onPrepareOptionsMenu below.
        invalidateOptionsMenu()
    }

    /**
     * Inflates the top app bar's overflow menu.
     *
     * The menu is also declared on the toolbar in activity_main.xml, but inflating it here is what
     * registers it with the Activity — which is what routes taps to [onOptionsItemSelected] and
     * lets [onPrepareOptionsMenu] hide it.
     */
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.top_app_bar_menu, menu)
        return true
    }

    /**
     * Hides the account actions where they make no sense.
     *
     * Runs every time the menu is rebuilt, which [updateChromeFor] triggers on each navigation.
     * The profile screen hides its own entry as well: an item that reopens the screen already being
     * looked at is a dead end.
     */
    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val destinationId = navController.currentDestination?.id

        val isAuthScreen = destinationId in AUTH_DESTINATIONS

        menu.findItem(R.id.profileFragment)?.isVisible =
            !isAuthScreen && destinationId != R.id.profileFragment
        menu.findItem(R.id.action_sign_out)?.isVisible = !isAuthScreen

        return super.onPrepareOptionsMenu(menu)
    }

    /**
     * Handles a tap in the overflow menu.
     *
     * The two entries are handled very differently, and deliberately so. "Profile" is an ordinary
     * destination whose menu id matches its id in the graph, so it is handed to
     * [onNavDestinationSelected] and NavigationUI navigates there — no destination id is written
     * out here, and the item cannot drift out of step with the graph.
     *
     * "Sign out" is not a destination. It is an action with a side effect, so it is handled here.
     */
    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_sign_out -> {
            signOut()
            true
        }

        // Falls through to `super` if NavigationUI does not recognise the item, which is what keeps
        // the toolbar's own back arrow working.
        else -> item.onNavDestinationSelected(navController) || super.onOptionsItemSelected(item)
    }

    /**
     * Clears the session and returns to the sign-in screen.
     *
     * `popUpTo` the graph itself with `inclusive` empties the entire back stack on the way, which is
     * what makes signing out final: without it the system back button from the sign-in screen would
     * walk straight back into the account that has just been left.
     *
     * Nothing is deleted. [SessionViewModel.signOut] forgets the id and signs the account out of
     * Firebase Authentication; the pets and records stay on the device for the next login.
     */
    private fun signOut() {
        sessionViewModel.signOut()

        navController.navigate(
            R.id.authFragment,
            null,
            navOptions { popUpTo(R.id.nav_graph) { inclusive = true } }
        )
    }

    /**
     * Pushes the system bars **and the soft keyboard** out of the way of the content.
     *
     * With edge-to-edge enabled the status and navigation bars overlap the layout, so the space
     * they occupy is applied back as padding. Doing this at runtime rather than with a fixed margin
     * is what makes the app sit correctly on devices with very different bar and cutout sizes.
     *
     * Applied to the root LinearLayout, so the top inset lands above the app bar and the bottom
     * inset below the navigation tabs — which is what keeps the tabs clear of the gesture handle on
     * a device that uses one.
     *
     * **Why the keyboard has to be handled here, when the manifest already says `adjustResize`.**
     * It does, and that declaration is correct and still needed — but it is not sufficient once
     * [enableEdgeToEdge] has been called. Going edge-to-edge sets `decorFitsSystemWindows` to false,
     * and from Android 11 (API 30) a window in that mode is **not resized** when the keyboard opens:
     * the system reports the keyboard as one more inset and leaves it to the app to make room. So
     * before this, the keyboard simply drew on top of whatever was at the bottom of the screen — the
     * assistant's input row, or the last field of a form — and nothing moved.
     *
     * **`max`, not a sum.** An open keyboard already covers the area the navigation bar occupies, so
     * adding the two together would reserve that space twice and leave a visible gap under the
     * keyboard. Taking the larger of the two is what makes this correct with a gesture handle
     * (a small bottom inset) and with three-button navigation (a large one) alike.
     *
     * Because the padding is on the root, every screen inherits the fix — the assistant, and the
     * add/edit pet, task and expense forms, which are each wrapped in a ScrollView and so re-measure
     * into the smaller space and scroll the focused field into view by themselves.
     */
    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { view, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())

            view.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                maxOf(systemBars.bottom, ime.bottom)
            )

            // The tabs are pointless while the user is typing and would otherwise sit as a band
            // between the keyboard and the field above it. Hiding them puts the assistant's input
            // row directly above the keyboard and gives every form an extra row of space.
            isKeyboardVisible = windowInsets.isVisible(WindowInsetsCompat.Type.ime())
            updateBottomNavVisibility()

            windowInsets
        }
    }

    /**
     * Whether the soft keyboard is on screen, as last reported by the inset listener.
     *
     * Kept as state because two independent rules decide whether the tabs are shown — which screen
     * the user is on, and whether they are typing — and each is told about its own change only. The
     * flag is what lets [updateBottomNavVisibility] apply both whichever one fired.
     */
    private var isKeyboardVisible = false

    /**
     * Shows the bottom tabs unless the sign-in screens are up or the keyboard is.
     *
     * One place deciding it, called from both [updateChromeFor] and the inset listener, because the
     * two rules would otherwise overwrite each other: navigating with the keyboard open would put
     * the tabs back, and the keyboard closing on an auth screen would reveal tabs that belong to an
     * account nobody has signed in to yet.
     */
    private fun updateBottomNavVisibility() {
        val isAuthScreen = navController.currentDestination?.id in AUTH_DESTINATIONS

        binding.bottomNav.isVisible = !isAuthScreen && !isKeyboardVisible
    }

    /**
     * Handles the toolbar's back arrow.
     *
     * Passing [appBarConfiguration] is what makes the arrow respect the top-level destinations
     * declared above. Falling through to `super` covers anything NavigationUI chooses not to
     * handle.
     */
    override fun onSupportNavigateUp(): Boolean =
        navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()

    private companion object {

        /**
         * The `petId` navigation argument, as declared on the checklist and expenses destinations
         * in nav_graph.xml, with the default those declarations give it.
         *
         * Repeated here rather than reaching into either fragment for its constant, because this
         * Activity reads the raw argument bundle before any fragment exists to ask.
         */
        const val ARG_PET_ID = "petId"
        const val NO_PET_ID = -1L

        /**
         * Every screen shown before anyone is signed in: log in / register, the two-factor check, the
         * recovery-code screen behind it, and the two password-reset screens. All of them hide the app
         * bar, the tabs and the account menu. One set, used by every check in this class, so adding a
         * sign-in screen later is one line.
         *
         * The two-factor *setup* screens are deliberately not here. They are about signing in, but
         * they are reached from the profile screen by somebody who is already signed in — so they get
         * the ordinary app bar with its back arrow, like the change-password form.
         */
        val AUTH_DESTINATIONS = setOf(
            R.id.authFragment,
            R.id.twoFactorFragment,
            R.id.recoveryCodeFragment,
            R.id.resetRequestFragment,
            R.id.resetSentFragment
        )

        private const val TAG = "MainActivity"

        /**
         * How much faster than authored the opening animation is played.
         *
         * res/raw/splash_animation.mp4 runs 3.6 seconds. At this rate it runs in about 2.4, which is
         * the length a splash can be without the user starting to wonder — and, unlike simply cutting
         * it off at a deadline, the animation still *finishes*: the logo it spends its whole length
         * assembling is actually arrived at.
         *
         * 1.5 rather than something larger because the clip has real motion in it, and past roughly
         * this the movement stops reading as brisk and starts reading as a glitch.
         *
         * **To play the animation exactly as authored, set this to 1f** and raise
         * [INTRO_BUDGET_MILLIS] past its 3,600 ms so the budget does not cut what the speed no longer
         * shortens.
         */
        const val INTRO_SPEED = 1.5f

        /**
         * The longest the opening animation may ever hold the user, measured from the app opening.
         *
         * **A ceiling, not a duration.** At [INTRO_SPEED] the clip finishes by itself in about 2.4
         * seconds and dismisses itself the moment it does, so in the ordinary case this is never
         * reached. It is what catches every way the animation can fail to end on its own — a surface
         * that never arrives, a decoder that never opens, a player that stalls, a replacement clip
         * that runs longer than the last — with just enough margin over that 2.4 to not clip the end
         * off a normal playback.
         *
         * It doubles as the guarantee that a slow device is not held longer than a fast one: because
         * [startIntroVideo] starts the clock before the surface, the decoder or the file are touched,
         * time lost to any of those comes out of the animation rather than out of the user's. A phone
         * that takes a second to open its decoder sees a second less of the animation, not a second
         * more of waiting.
         */
        const val INTRO_BUDGET_MILLIS = 3_000L

        /** How long the overlay takes to dissolve into the app once the video is over. */
        const val INTRO_FADE_MILLIS = 220L
    }
}
