plugins {
    alias(libs.plugins.android.application)
    // KSP runs Room's annotation processor, which generates the DAO and database
    // implementations from the @Dao and @Database declarations at build time.
    alias(libs.plugins.ksp)
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

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}