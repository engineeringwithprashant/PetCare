package com.prash.petcare

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import com.prash.petcare.databinding.ActivityMainBinding

/**
 * The app's only Activity.
 *
 * It owns the window, the top app bar and the NavHostFragment; every screen is a fragment swapped
 * in by the Navigation Component. This is the single-Activity architecture Google recommends, with
 * navigation described declaratively in res/navigation/nav_graph.xml.
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
     * Tells NavigationUI which destinations are the top of a journey.
     *
     * Anything listed here shows no back arrow, because there is nowhere above it to go; every
     * other destination gets one automatically. Sign-in is the app's entry point, and the home
     * screen is what a successful sign-in replaces it with — offering "back" from either would only
     * lead somewhere the user has deliberately left.
     */
    private val appBarConfiguration = AppBarConfiguration(
        setOf(R.id.loginFragment, R.id.homeFragment)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Draws the app behind the status and navigation bars for a modern full-height look. The
        // inset handling below is what stops content hiding underneath them.
        enableEdgeToEdge()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setUpNavigation()
        applyWindowInsets()
    }

    /** Connects the top app bar to the navigation graph. */
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

        // The sign-in and sign-up screens carry their own large headings and have nowhere to
        // navigate back to, so the bar would be an empty strip above them.
        navController.addOnDestinationChangedListener { _, destination, _ ->
            binding.appBarLayout.isVisible = when (destination.id) {
                R.id.loginFragment, R.id.registerFragment -> false
                else -> true
            }
        }
    }

    /**
     * Pushes the system bars out of the way of the content.
     *
     * With edge-to-edge enabled the status and navigation bars overlap the layout, so the space
     * they occupy is applied back as padding. Doing this at runtime rather than with a fixed margin
     * is what makes the app sit correctly on devices with very different bar and cutout sizes.
     */
    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { view, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            windowInsets
        }
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
}
