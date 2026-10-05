// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    // Declared here so the version is resolved once for the whole build.
    alias(libs.plugins.ksp) apply false
    // Same pattern for the Google services plugin, which processes app/google-services.json.
    alias(libs.plugins.google.services) apply false
}