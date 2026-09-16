package com.prash.petcare.ui.place

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.prash.petcare.R

/**
 * Runs the "explain, then ask" location permission flow for a fragment.
 *
 * Both Places screens need location access — the tab to measure distances, the form to capture a
 * geotag — and each would otherwise carry an identical copy of the launcher, the rationale dialog and
 * the result handling. Part of the **View** layer: asking for a permission needs a fragment and a
 * dialog, which is exactly what a ViewModel must never hold.
 *
 * **Why fine *and* coarse are requested together.** `ACCESS_FINE_LOCATION` is the permission the
 * feature wants, but from Android 12 the system ignores a request for fine location that does not
 * also name coarse, and it lets the user choose "Approximate" in the dialog. Asking for both is
 * what makes the prompt appear at all; either answer is then accepted, and the form tells the user
 * when they have only granted approximate access (see AddEditPlaceFragment).
 *
 * **Construct this as a property initialiser.** It registers an activity-result launcher, which
 * Android only allows while the fragment is being created — so the callback is in place before a
 * fragment recreated behind the system dialog can be handed the answer.
 *
 * @param fragment The screen asking.
 * @param rationaleMessage Why this particular screen wants the location, shown before the request.
 * @param onGranted Called once location access is available, whether just granted or already held.
 * @param onDenied Called when the user refuses. `permanently` is true when the system will not show
 *   the prompt again, so the caller can offer the settings screen instead of asking a question that
 *   can no longer be answered.
 * @param permissions Which location permissions to ask for, most important first. Defaults to the
 *   fine-and-coarse pair the Places screens need. The home screen's weather card overrides it with
 *   coarse alone: a temperature is the same across a whole city, so asking for GPS accuracy to fetch
 *   one would be requesting more than the feature uses — and a prompt that asks for less is one the
 *   user is more likely to grant.
 */
class LocationPermissionRequester(
    private val fragment: Fragment,
    @param:StringRes private val rationaleMessage: Int,
    private val onGranted: () -> Unit,
    private val onDenied: (permanently: Boolean) -> Unit,
    private val permissions: Array<String> = arrayOf(FINE, COARSE)
) {

    private val launcher =
        fragment.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            // Either grant is enough to go on: approximate location still gives a usable distance, and
            // refusing to work at all because the user chose "Approximate" would punish a reasonable
            // privacy choice.
            if (results.values.any { it }) {
                onGranted()
            } else {
                // After a refusal, "should show rationale" being false means the user ticked "don't
                // ask again" (or refused twice), and further requests return instantly with no dialog.
                // Asked of the first permission requested, which is the one the caller most wanted.
                onDenied(!fragment.shouldShowRequestPermissionRationale(permissions.first()))
            }
        }

    /** True when precise or approximate location has already been granted. */
    fun hasPermission(): Boolean {
        val context = fragment.requireContext()
        return listOf(FINE, COARSE).any {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Makes location access available: straight through when already granted, otherwise a rationale
     * dialog and then the system prompt.
     *
     * The rationale is shown every time rather than only when `shouldShowRequestPermissionRationale`
     * says so, for the reason given in ChecklistFragment.showRationaleThenRequest: that call returns
     * false on the very first request, which is exactly when the user most needs the explanation.
     * "Not now" does nothing at all, so declining never costs the user the screen they are on.
     */
    fun request() {
        if (hasPermission()) {
            onGranted()
            return
        }

        MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle(R.string.permission_location_title)
            .setMessage(rationaleMessage)
            .setNegativeButton(R.string.permission_not_now, null)
            .setPositiveButton(R.string.permission_continue) { _, _ ->
                launcher.launch(permissions)
            }
            .show()
    }

    private companion object {
        const val FINE = Manifest.permission.ACCESS_FINE_LOCATION
        const val COARSE = Manifest.permission.ACCESS_COARSE_LOCATION
    }
}
