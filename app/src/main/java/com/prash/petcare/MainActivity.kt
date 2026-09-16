package com.prash.petcare

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.navOptions
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.onNavDestinationSelected
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.prash.petcare.databinding.ActivityMainBinding
import com.prash.petcare.ui.session.SessionViewModel

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
    private val sessionViewModel: SessionViewModel by viewModels()

    /**
     * Tells NavigationUI which destinations are the top of a journey.
     *
     * Anything listed here shows no back arrow, because there is nowhere above it to go; every
     * other destination gets one automatically.
     *
     * The set is the five bottom-bar destinations plus the sign-in screen. The five are peers
     * rather than a hierarchy — moving from Pets to Expenses is a switch, not a step forward, so
     * offering "back" from any of them would be offering to undo something the user did not do.
     * Sign-in is the app's entry point and has nothing above it either.
     *
     * Sign-up is deliberately *not* here: it is reached from the sign-in screen and back should
     * return there.
     */
    private val appBarConfiguration = AppBarConfiguration(
        setOf(
            R.id.loginFragment,
            R.id.homeFragment,
            R.id.petsFragment,
            R.id.checklistFragment,
            R.id.placesFragment,
            R.id.expensesFragment
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Draws the app behind the status and navigation bars for a modern full-height look. The
        // inset handling below is what stops content hiding underneath them.
        enableEdgeToEdge()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Order matters: the inset listener decides whether the bottom tabs are shown, which depends
        // on the current destination, so the NavController has to exist before it can first fire.
        setUpNavigation()
        applyWindowInsets()
    }

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

        // One line for the whole bottom bar. NavigationUI matches each menu item to the destination
        // sharing its id, so this gives both directions at once: a tap navigates, and arriving at a
        // destination selects its tab. It also handles re-tapping the current tab by popping back to
        // it, which is the behaviour users expect and which a hand-written click listener almost
        // always gets wrong.
        binding.bottomNav.setupWithNavController(navController)

        // Added *after* setupActionBarWithNavController, so this listener runs after NavigationUI's
        // own and can correct the two things NavigationUI cannot know about — see updateChromeFor
        // and restoreUpArrowForPetScopedScreen.
        navController.addOnDestinationChangedListener { _, destination, arguments ->
            updateChromeFor(destination.id, arguments)
            restoreUpArrowForPetScopedScreen(destination.id, arguments)
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
     * The sign-in and sign-up screens carry their own large headings, have nowhere to navigate back
     * to and belong to nobody, so all of it goes — the tabs as well: a row of tabs leading into an
     * account the user has not signed in to yet would be worse than no chrome at all.
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
     * Nothing is lost with it: the account actions in the overflow menu are also reachable from the
     * header's count badge, which opens the profile screen, and that screen carries its own sign
     * out button. See [com.prash.petcare.ui.common.setUp].
     */
    private fun updateChromeFor(destinationId: Int, arguments: Bundle?) {
        val isAuthScreen =
            destinationId == R.id.loginFragment || destinationId == R.id.registerFragment

        val hasNoPetArgument = (arguments?.getLong(ARG_PET_ID, NO_PET_ID) ?: NO_PET_ID) == NO_PET_ID
        val isTasksTab = destinationId == R.id.checklistFragment && hasNoPetArgument
        val isMoneyTab = destinationId == R.id.expensesFragment && hasNoPetArgument

        // The pet detail screen is not a tab, but it draws the same header — with the pet's name as
        // its title — and puts its back button on the photo instead of in a toolbar, as in the design.
        val hasOwnHeader = destinationId == R.id.homeFragment ||
            destinationId == R.id.petsFragment ||
            destinationId == R.id.petDetailFragment ||
            destinationId == R.id.placesFragment ||
            // The assistant is not a tab, but it draws the same header — its own title, the theme
            // toggle and the pet badge — so the shared bar above it would show the name twice.
            destinationId == R.id.assistantFragment ||
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

        val isAuthScreen =
            destinationId == R.id.loginFragment || destinationId == R.id.registerFragment

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
     * Nothing is deleted. The app keeps no persisted login, so there is no stored credential to
     * clear — signing out is forgetting the id and going back to the front door.
     */
    private fun signOut() {
        sessionViewModel.signOut()

        navController.navigate(
            R.id.loginFragment,
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
        val isAuthScreen = navController.currentDestination?.id == R.id.loginFragment ||
            navController.currentDestination?.id == R.id.registerFragment

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
    }
}
