package com.prash.petcare.ui.auth

import android.app.Activity
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.prash.petcare.R

/**
 * What came back from asking the user to pick a Google account.
 *
 * Plain Kotlin with no Android types, so [AuthViewModel] can take it and decide what the screen says
 * without holding anything from the Activity.
 */
sealed interface GoogleCredentialResult {

    /** The user picked an account. [idToken] proves which one, and Firebase checks it next. */
    data class Token(val idToken: String) : GoogleCredentialResult

    /** The user closed the account picker without choosing. */
    data object Cancelled : GoogleCredentialResult

    /** The device has no Google account to offer. */
    data object NoGoogleAccount : GoogleCredentialResult

    /** The device is offline, so Google could not be reached. */
    data object NoNetwork : GoogleCredentialResult

    /**
     * This device cannot do Google sign-in at all, typically because Google Play services is
     * missing or out of date.
     */
    data object Unavailable : GoogleCredentialResult

    /** Anything else went wrong while getting the token. */
    data object Failed : GoogleCredentialResult
}

/**
 * Shows the system "Sign in with Google" account picker through Credential Manager and returns the
 * Google ID token for the chosen account.
 *
 * **Why this is in the View layer.** Credential Manager draws its own UI on top of the app, so it
 * needs the current [Activity]. A ViewModel must never hold an Activity (it outlives it), and a
 * repository has no business with one either. So the fragment uses this class to get the token and
 * passes only the token string to [AuthViewModel], which hands it to the repository and Firebase.
 * That keeps the chain one-way: View → ViewModel → Repository.
 *
 * **Why [GetSignInWithGoogleOption].** Google's Credential Manager guide uses this option for an
 * explicit "Sign in with Google" button. The other option, `GetGoogleIdOption`, is for the pop-up
 * bottom sheet an app shows by itself. The button option always shows every Google account on the
 * device, and when there are none it offers to add one, which a button the user has deliberately
 * tapped should do.
 *
 * **No nonce.** Google's samples add a nonce for backends that check it. Firebase's Google sign-in
 * does not take one (`GoogleAuthProvider.getCredential` has no nonce parameter), so none is sent.
 *
 * @param activity The Activity to draw the picker over. Only held for one call, by a fragment
 *   whose view is alive.
 */
class GoogleSignInLauncher(private val activity: Activity) {

    private val credentialManager = CredentialManager.create(activity)

    /**
     * Shows the picker and waits for the user. Never throws: every outcome is a
     * [GoogleCredentialResult].
     *
     * A `suspend` function because the picker is on screen until the user acts. If the coroutine is
     * cancelled (the screen closed), Credential Manager closes the picker too.
     */
    suspend fun requestIdToken(): GoogleCredentialResult {
        // Checked first: Credential Manager has no dedicated "offline" error, so without this an
        // offline device would only get the general failure message.
        if (!isOnline()) return GoogleCredentialResult.NoNetwork

        val option = GetSignInWithGoogleOption.Builder(
            // The *web* OAuth client from google-services.json. The Google Services Gradle plugin
            // generates this string. Google issues the ID token for this client, and Firebase only
            // accepts tokens issued for its own project's web client.
            serverClientId = activity.getString(R.string.default_web_client_id)
        ).build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(option)
            .build()

        return try {
            val credential = credentialManager.getCredential(activity, request).credential

            // A Google sign-in comes back as a CustomCredential whose type identifies it. Any other
            // credential cannot happen with only a Google option in the request, so it is treated
            // as a failure rather than trusted.
            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                GoogleCredentialResult.Token(GoogleIdTokenCredential.createFrom(credential.data).idToken)
            } else {
                GoogleCredentialResult.Failed
            }
        } catch (e: GetCredentialCancellationException) {
            GoogleCredentialResult.Cancelled
        } catch (e: NoCredentialException) {
            GoogleCredentialResult.NoGoogleAccount
        } catch (e: GetCredentialProviderConfigurationException) {
            // No credential provider is available: Google Play services missing or disabled.
            GoogleCredentialResult.Unavailable
        } catch (e: GetCredentialUnsupportedException) {
            GoogleCredentialResult.Unavailable
        } catch (e: GetCredentialException) {
            // The connection may have dropped while the picker was open.
            if (isOnline()) GoogleCredentialResult.Failed else GoogleCredentialResult.NoNetwork
        } catch (e: GoogleIdTokenParsingException) {
            // The result could not be read. Google's guide says an outdated googleid library can
            // cause this.
            GoogleCredentialResult.Failed
        }
    }

    /**
     * Whether the device has a network that can actually reach the internet.
     *
     * `VALIDATED` means Android has checked the network really reaches the internet, so a captive
     * Wi-Fi login page counts as offline. The same test is used by data.sync.NetworkMonitor.
     */
    private fun isOnline(): Boolean {
        val connectivityManager = activity.getSystemService(ConnectivityManager::class.java)
            ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(
            connectivityManager.activeNetwork ?: return false
        ) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
