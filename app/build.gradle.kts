plugins {
    alias(libs.plugins.android.application)
    // KSP runs Room's annotation processor, which generates the DAO and database
    // implementations from the @Dao and @Database declarations at build time.
    alias(libs.plugins.ksp)
    // Reads app/google-services.json at build time and generates the string resources
    // (project id, API key, application id) that FirebaseApp reads when it initialises itself on
    // app start. Without this plugin the JSON file is just an unused file in the module and every
    // Firebase call would fail with "Default FirebaseApp is not initialized".
    alias(libs.plugins.google.services)
}

android {
    namespace = "com.prash.petcare"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.prash.petcare"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }

    buildFeatures {
        // Generates a type-safe binding class per XML layout (activity_main.xml ->
        // ActivityMainBinding). This is the project's only sanctioned way to reach views:
        // findViewById returns a loosely typed View and cannot catch a wrong id at compile time.
        viewBinding = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)

    // Room: local SQLite persistence. The compiler is wired through `ksp` rather than
    // `implementation` because it generates code at build time and is not needed at runtime.
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Navigation Component: every screen change in the app goes through the nav graph.
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)

    // Firebase Firestore: the remote half of the offline-first sync layer. Room remains the app's
    // source of truth — see data.sync.SyncManager — and this is only ever written to and read from
    // by the data layer, never by a screen.
    //
    // `platform(...)` is what makes the BoM work: it contributes no code itself, it publishes a set
    // of versions for the Firebase libraries declared after it. That is why firestore is listed
    // with no version at all.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.firestore)
    // Firestore's API returns Play services `Task` objects. This adds the `await()` extension that
    // suspends until one completes, letting the sync code read as ordinary sequential coroutine
    // code rather than as nested success/failure listeners.
    implementation(libs.kotlinx.coroutines.play.services)

    // Fused location provider: captures a GPS fix when a place is saved, and the phone's position
    // for measuring distances on the Places tab. Only the location API - there is no Maps SDK here.
    // Distances are computed on the device (util.GeoDistance) and directions are handed off to the
    // user's own maps app through a geo: intent, so no map ever needs drawing inside PetCare.
    implementation(libs.play.services.location)

    // Lifecycle-aware ViewModels and flow collection.
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.recyclerview)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}