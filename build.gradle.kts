// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    // Declared here so the version is resolved once for the whole build; `apply false` means
    // the root project itself does not use it — the :app module applies it.
    alias(libs.plugins.ksp) apply false
}