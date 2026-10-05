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

/** The app's only Activity. */
class MainActivity : AppCompatActivity() {

    /** ViewBinding for activity_main.xml. */
    private lateinit var binding: ActivityMainBinding

    private lateinit var navController: NavController

    /** Who is signed in, shared with every fragment. */
    private val sessionViewModel: SessionViewModel by viewModels { SessionViewModel.Factory }

    /** Tells NavigationUI which destinations are the top of a journey. */
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
        // Hands the window back from the launch theme to the real one.
        setTheme(R.style.Theme_PetCare)

        super.onCreate(savedInstanceState)

        // Draws the app behind the status and navigation bars for a modern full-height look.
        enableEdgeToEdge()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // The opening video plays only on a genuine cold start.
        if (savedInstanceState == null) startIntroVideo() else binding.splashOverlay.isVisible = false

        // Order matters: the inset listener decides whether the bottom tabs are shown.
        setUpNavigation()
        applyWindowInsets()
    }

    /** Starts the opening animation as soon as there is something to draw it on. */
    private fun startIntroVideo() {
        // Tap anywhere to skip. Somebody opening the app to tick off a task has seen the animation before and wants the app.
        binding.splashOverlay.setOnClickListener { dismissIntro() }

        // The deadline for the whole animation, posted before anything has been prepared.
        binding.root.postDelayed(introTimeout, INTRO_BUDGET_MILLIS)

        // The status and navigation bar icons are drawn over the cream overlay for as long as it is up.
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

            /** Returning true hands the surface back to the system to release. */
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                releaseIntroPlayer()
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
    }

    /** Plays res/raw/splash_animation.mp4 once, then dissolves the overlay to reveal the app. */
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
            Log.w(TAG, "Opening video could not be opened", e)
            dismissIntro()
            return
        }

        player.setSurface(surface)
        player.setVolume(0f, 0f)

        player.setOnPreparedListener {
            fillVideoToView(player.videoWidth, player.videoHeight)

            // Played faster than it was authored. The clip runs 3.6 seconds.
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

        // Not prepare(). That call reads and decodes the file's headers on the calling thread.
        player.prepareAsync()
    }

    /** Scales the video to cover the screen, cropping whatever overflows. */
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

    /** Fades the overlay away and lets go of the player. */
    private fun dismissIntro() {
        if (isIntroDismissed) return
        isIntroDismissed = true

        binding.root.removeCallbacks(introTimeout)
        releaseIntroPlayer()

        // Puts the status and navigation bar icons back under the theme's control.
        enableEdgeToEdge()

        binding.splashOverlay.animate()
            .alpha(0f)
            .setDuration(INTRO_FADE_MILLIS)
            // Gone rather than left at zero alpha.
            .withEndAction { binding.splashOverlay.isVisible = false }
            .start()
    }

    /** Stops and frees the player. */
    private fun releaseIntroPlayer() {
        introPlayer?.apply {
            // The player may still be preparing, where stop() is an illegal state rather than a no-op.
            runCatching { stop() }
            release()
        }
        introPlayer = null
    }

    override fun onDestroy() {
        super.onDestroy()
        // The Activity can be torn down mid-animation — a back press on the first screen.
        releaseIntroPlayer()
    }

    /** The opening animation's deadline, held as one object. */
    private val introTimeout = Runnable { dismissIntro() }

    /** The opening video's player while it is playing, and null at every other moment. */
    private var introPlayer: MediaPlayer? = null

    /** Whether the overlay has already been dismissed. See [dismissIntro]. */
    private var isIntroDismissed = false

    /** Connects the top app bar and the bottom navigation bar to the navigation graph. */
    private fun setUpNavigation() {
        // The NavController belongs to the NavHostFragment, and is reached through the fragment manager.
        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController

        // Makes the MaterialToolbar act as the Activity's action bar, so NavigationUI can drive it.
        setSupportActionBar(binding.topAppBar)

        // Sets the title from each destination's android:label and manages the back arrow.
        setupActionBarWithNavController(navController, appBarConfiguration)

        // The bottom bar's outward half: a tap asks this Activity to navigate.
        binding.navTabs.onTabSelected = ::navigateToTab

        // Added after setupActionBarWithNavController.
        navController.addOnDestinationChangedListener { _, destination, arguments ->
            updateChromeFor(destination.id, arguments)
            restoreUpArrowForPetScopedScreen(destination.id, arguments)
            highlightTabFor(destination.id)
        }

        observeOpenTaskCount()
    }

    /** Navigates to a tapped tab, with the behaviour a set of tabs is expected to have. */
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

    /** Lights the tab the destination just arrived at belongs to. */
    private fun highlightTabFor(destinationId: Int) {
        NavTab.forDestination(destinationId)?.let(binding.navTabs::selectTab)
    }

    /** Keeps the Tasks tab's badge showing how many care tasks are still open today. */
    private fun observeOpenTaskCount() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                sessionViewModel.openTasksToday.collect(binding.navTabs::setOpenTaskCount)
            }
        }
    }

    /** Puts the back arrow back on the checklist and expenses screens. */
    private fun restoreUpArrowForPetScopedScreen(destinationId: Int, arguments: Bundle?) {
        val isDualPurposeScreen =
            destinationId == R.id.checklistFragment || destinationId == R.id.expensesFragment

        if (!isDualPurposeScreen) return

        val petId = arguments?.getLong(ARG_PET_ID, NO_PET_ID) ?: NO_PET_ID

        if (petId != NO_PET_ID) {
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
        }
    }

    /** Shows or hides the navigation chrome for the destination just arrived at. */
    private fun updateChromeFor(destinationId: Int, arguments: Bundle?) {
        val isAuthScreen = destinationId in AUTH_DESTINATIONS

        val hasNoPetArgument = (arguments?.getLong(ARG_PET_ID, NO_PET_ID) ?: NO_PET_ID) == NO_PET_ID
        val isTasksTab = destinationId == R.id.checklistFragment && hasNoPetArgument
        val isMoneyTab = destinationId == R.id.expensesFragment && hasNoPetArgument

        // The pet detail screen is not a tab, but it draws the same header — with the pet's name as its title.
        val hasOwnHeader = destinationId == R.id.homeFragment ||
            destinationId == R.id.petsFragment ||
            destinationId == R.id.petDetailFragment ||
            destinationId == R.id.placesFragment ||
            // "Add a place" is a step deeper into the Places tab, not a screen of its own.
            destinationId == R.id.addPlaceFragment ||
            // "Record a vaccination" stands in the same relation to the Pets tab: a step deeper.
            destinationId == R.id.addVaccinationFragment ||
            // The assistant is not a tab, but it draws the same header — its own title.
            destinationId == R.id.assistantFragment ||
            // The notifications screen, for the same reason, and reached the same way.
            destinationId == R.id.notificationsFragment ||
            // The profile screen, likewise — reached from the header's monogram.
            destinationId == R.id.profileFragment ||
            isTasksTab ||
            isMoneyTab

        binding.appBarLayout.isVisible = !isAuthScreen && !hasOwnHeader
        // Not set directly: whether the tabs show also depends on the keyboard, which this method knows nothing.
        updateBottomNavVisibility()

        // The overflow menu holds the account actions, which only mean something once there is an account.
        invalidateOptionsMenu()
    }

    /** Inflates the top app bar's overflow menu. */
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.top_app_bar_menu, menu)
        return true
    }

    /** Hides the account actions where they make no sense. */
    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val destinationId = navController.currentDestination?.id

        val isAuthScreen = destinationId in AUTH_DESTINATIONS

        menu.findItem(R.id.profileFragment)?.isVisible =
            !isAuthScreen && destinationId != R.id.profileFragment
        menu.findItem(R.id.action_sign_out)?.isVisible = !isAuthScreen

        return super.onPrepareOptionsMenu(menu)
    }

    /** Handles a tap in the overflow menu. */
    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_sign_out -> {
            signOut()
            true
        }

        // Falls through to `super` if NavigationUI does not recognise the item.
        else -> item.onNavDestinationSelected(navController) || super.onOptionsItemSelected(item)
    }

    /** Clears the session and returns to the sign-in screen. */
    private fun signOut() {
        sessionViewModel.signOut()

        navController.navigate(
            R.id.authFragment,
            null,
            navOptions { popUpTo(R.id.nav_graph) { inclusive = true } }
        )
    }

    /** Pushes the system bars and the soft keyboard out of the way of the content. */
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

            // The tabs are pointless.
            isKeyboardVisible = windowInsets.isVisible(WindowInsetsCompat.Type.ime())
            updateBottomNavVisibility()

            windowInsets
        }
    }

    /** Whether the soft keyboard is on screen, as last reported by the inset listener. */
    private var isKeyboardVisible = false

    /** Shows the bottom tabs unless the sign-in screens are up or the keyboard is. */
    private fun updateBottomNavVisibility() {
        val isAuthScreen = navController.currentDestination?.id in AUTH_DESTINATIONS

        binding.bottomNav.isVisible = !isAuthScreen && !isKeyboardVisible
    }

    /** Handles the toolbar's back arrow. */
    override fun onSupportNavigateUp(): Boolean =
        navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()

    private companion object {

        /** The `petId` navigation argument. */
        const val ARG_PET_ID = "petId"
        const val NO_PET_ID = -1L

        /** Every screen shown before anyone is signed in: log in / register, the two-factor check. */
        val AUTH_DESTINATIONS = setOf(
            R.id.authFragment,
            R.id.twoFactorFragment,
            R.id.recoveryCodeFragment,
            R.id.resetRequestFragment,
            R.id.resetSentFragment
        )

        private const val TAG = "MainActivity"

        /** How much faster than authored the opening animation is played. */
        const val INTRO_SPEED = 1.5f

        /** The longest the opening animation may ever hold the user, measured from the app opening. */
        const val INTRO_BUDGET_MILLIS = 3_000L

        /** How long the overlay takes to dissolve into the app once the video is over. */
        const val INTRO_FADE_MILLIS = 220L
    }
}
