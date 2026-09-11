package com.prash.petcare

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.prash.petcare.databinding.ActivityMainBinding

/**
 * The app's only Activity.
 *
 * It hosts the NavHostFragment declared in activity_main.xml and does nothing else: every screen
 * is a fragment swapped in by the Navigation Component. This is the single-Activity architecture
 * Google recommends — one Activity owning the window, with navigation described declaratively in
 * res/navigation/nav_graph.xml.
 */
class MainActivity : AppCompatActivity() {

    /**
     * ViewBinding for activity_main.xml.
     *
     * `lateinit` is safe here, unlike in a fragment: an Activity's views live exactly as long as
     * the Activity itself, so there is no window in which the binding would outlive them.
     */
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Draws the app behind the status and navigation bars for a modern full-height look.
        // The inset handling below is what stops content hiding underneath them.
        enableEdgeToEdge()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // With edge-to-edge enabled the system bars overlap the layout, so the space they occupy
        // is applied back as padding. Doing this at runtime rather than with a fixed margin is
        // what makes the app sit correctly on devices with very different bar and cutout sizes.
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { view, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            windowInsets
        }
    }
}
