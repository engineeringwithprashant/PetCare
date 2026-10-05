import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // KSP runs Room's annotation processor.
    alias(libs.plugins.ksp)
    // Reads app/google-services.json at build time and generates the string resources.
    alias(libs.plugins.google.services)
}

/** The OpenAI key the assistant screen authenticates with, read from `local.properties`. */
/** The properties file the per-developer secrets live in. */
private val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

val openAiApiKey: String = localProperties.getProperty("OPENAI_API_KEY", "")

/** The MapTiler key the Places maps fetch their vector tiles and cartographic style. */
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

        // Surfaces the key to Kotlin as BuildConfig.OPENAI_API_KEY.
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
        // Generates a type-safe binding class per XML layout (activity_main.xml -> ActivityMainBinding).
        viewBinding = true

        // Generates the BuildConfig class the OPENAI_API_KEY field above is written into.
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

    // Room: local SQLite persistence. The compiler is wired through `ksp` rather than `implementation`.
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Navigation Component: every screen change in the app goes through the nav graph.
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)

    // Firebase Firestore: the remote half of the offline-first sync layer.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.firestore)
    // Firebase Authentication: sends the password-reset email and hosts the page where the new password is chosen.
    implementation(libs.firebase.auth)
    // "Continue with Google". Credential Manager shows the system account picker and returns a Google ID token.
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    // Firestore's API returns Play services `Task` objects.
    implementation(libs.kotlinx.coroutines.play.services)

    // Fused location provider: captures a GPS fix when a place is saved.
    implementation(libs.play.services.location)

    // MapLibre Native: the interactive map on the Places tab and on both place forms.
    implementation(libs.maplibre.android)

    // Retrofit: the app's one plain-HTTP data source.
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)

    // Two-factor authentication (TOTP), compatible with Google Authenticator.
    implementation(libs.totp) {
        exclude(group = "com.google.zxing", module = "javase")
    }
    implementation(libs.zxing.core)

    // Phone numbers. Every form that asks for one pairs a country selector with a local-number field.
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