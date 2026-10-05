package com.prash.petcare.ui.place

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.prash.petcare.R

/** Runs the "explain, then ask" location permission flow for a fragment. */
class LocationPermissionRequester(
    private val fragment: Fragment,
    @param:StringRes private val rationaleMessage: Int,
    private val onGranted: () -> Unit,
    private val onDenied: (permanently: Boolean) -> Unit,
    private val permissions: Array<String> = arrayOf(FINE, COARSE)
) {

    private val launcher =
        fragment.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            // Either grant is enough to go on: approximate location still gives a usable distance.
            if (results.values.any { it }) {
                onGranted()
            } else {
                // After a refusal, "should show rationale" being false means the user ticked "don't ask again".
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

    /** Makes location access available: straight through when already granted. */
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
