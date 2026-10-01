import java.util.Properties

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

/**
 * The OpenAI key the assistant screen authenticates with, read from `local.properties`.
 *
 * **Why local.properties and not a constant in source.** That file is listed in .gitignore — it is
 * where the Android SDK path already lives, so it is per-developer and never committed. A key typed
 * into a Kotlin file would be in the repository's history the moment it was pushed, and removing it
 * later would not remove it from history.
 *
 * Returns an empty string when the key is absent, rather than failing the build: the app must still
 * compile and run for anyone who clones it without a key, and the assistant screen says plainly that
 * the key is missing instead of breaking — see OpenAiAssistantRepository.
 *
 * Note what this does and does not protect. It keeps the key out of version control; it does **not**
 * keep it out of the installed APK, where a determined user can still extract it. For an app that
 * shipped to real users the call would belong behind a server that holds the key instead.
 */
/**
 * The properties file the per-developer secrets live in.
 *
 * Read once rather than per key: it is the same file, and opening it twice would be two chances for
 * the two reads to disagree about whether it exists.
 */
private val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

val openAiApiKey: String = localProperties.getProperty("OPENAI_API_KEY", "")

/**
 * The MapTiler key the Places maps fetch their vector tiles and cartographic style with, read from
 * `local.properties` for exactly the reasons given above for the OpenAI key: that file is in
 * .gitignore, so the key is per-developer and never committed.
 *
 * Returns an empty string when absent rather than failing the build, so the project still compiles
 * and runs for anyone who clones it without a key. What they get is a map card that says it could
 * not be loaded — see ui.place.MapStyles.isConfigured, which tests for exactly this — rather than a
 * blank grey panel or a crash.
 *
 * **To get one:** sign up at maptiler.com (the free tier is ample for this app), copy the key from
 * the account's API keys page, and add `MAPTILER_API_KEY=...` to local.properties.
 */
val mapTilerApiKey: String = localProperties.getProperty("MAPTILER_API_KEY", "")

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

        // Surfaces the key to Kotlin as BuildConfig.OPENAI_API_KEY. The value is quoted here because
        // buildConfigField writes its third argument into generated Java verbatim — an unquoted
        // string would be emitted as an identifier and fail to compile.
        buildConfigField("String", "OPENAI_API_KEY", "\"$openAiApiKey\"")
        buildConfigField("String", "MAPTILER_API_KEY", "\"$mapTilerApiKey\"")
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

        // Generates the BuildConfig class the OPENAI_API_KEY field above is written into. Off by
        // default from AGP 8, so declaring a buildConfigField without this silently generates
        // nothing and the Kotlin reference fails to resolve.
        buildConfig = true
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
    // Firebase Authentication: sends the password-reset email and hosts the page where the new
    // password is chosen. Reached only through data.auth.FirebasePasswordResetRepository.
    implementation(libs.firebase.auth)
    // "Continue with Google". Credential Manager shows the system account picker and returns a
    // Google ID token (ui.auth.GoogleSignInLauncher); Firebase Authentication then signs in with
    // that token. credentials-play-services-auth is needed as well as credentials itself, since
    // it is the part that reaches Google Play services on Android 13 and older.
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    // Firestore's API returns Play services `Task` objects. This adds the `await()` extension that
    // suspends until one completes, letting the sync code read as ordinary sequential coroutine
    // code rather than as nested success/failure listeners.
    implementation(libs.kotlinx.coroutines.play.services)

    // Fused location provider: captures a GPS fix when a place is saved, and the phone's position
    // for measuring distances on the Places tab. The location half of Play services only — the
    // Google Maps SDK is deliberately not here; the map is drawn by MapLibre below, and directions
    // are handed off to the user's own maps app through a Maps URL.
    implementation(libs.play.services.location)

    // MapLibre Native: the interactive map on the Places tab and on both place forms. A real Android
    // view rendering vector tiles on the GPU — see ui.place.PlaceMapView for what that buys over the
    // raster web map it replaced, and ui.place.MapStyles for where the tiles come from.
    implementation(libs.maplibre.android)

    // Retrofit: the app's one plain-HTTP data source, used for the Open-Meteo forecast behind the
    // home screen's walk-window temperature. Firestore brings its own transport, so this is not a
    // duplicate of it — see data.remote.weather.WeatherApi.
    //
    // Retrofit is used rather than a hand-rolled HttpURLConnection call because it gives the request
    // a typed Kotlin interface, parses the JSON through the converter below, and returns from a
    // `suspend` function without any thread handling of its own.
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)

    // Two-factor authentication (TOTP), compatible with Google Authenticator.
    //
    // The library supplies the secret generator, the RFC 6238 code generator and the verifier that
    // allows a step of clock drift either side — see data.auth.TotpAuthenticator, which is the only
    // class in the app that names any of those types.
    //
    // **The two excludes matter.** `dev.samstevens.totp` also ships a ready-made QR generator, and
    // that is the half that cannot come along: it draws through `com.google.zxing:javase`, which
    // renders into a `java.awt.image.BufferedImage` and writes it with `javax.imageio`. Neither
    // package exists on Android, so letting it onto the class path would put classes into the build
    // that reference types the platform has never had. The QR code is therefore drawn from ZXing's
    // pure-Java `core` encoder instead, declared below and painted by util.QrCodeRenderer.
    implementation(libs.totp) {
        exclude(group = "com.google.zxing", module = "javase")
    }
    implementation(libs.zxing.core)

    // Phone numbers. Every form that asks for one pairs a country selector with a local-number
    // field, and this is what decides whether the two together make a real number: the digit count,
    // the valid prefixes and the E.164 form all come from its per-country metadata rather than from
    // rules written here. See ui.common.PhoneNumbers, which is the only file that names its types.
    implementation(libs.libphonenumber)

    // Lifecycle-aware ViewModels and flow collection.
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.recyclerview)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}