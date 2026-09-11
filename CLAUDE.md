# PetCare — Project Conventions

Android pet care app, university coursework. Package: `com.prash.petcare`. Single `:app` module.

## Stack rules (non-negotiable)

- **Kotlin + XML layouts only. Never use Jetpack Compose.** Do not add `androidx.compose` dependencies or `@Composable` functions.
- **ViewBinding for all view access.** No `findViewById`, no Kotlin synthetics. Enable via:
  ```kotlin
  android { buildFeatures { viewBinding = true } }
  ```
  `MainActivity.kt` currently still calls `findViewById` directly — convert it to ViewBinding (`ActivityMainBinding`) when it's next touched, don't leave new code using the old pattern.
- **Architecture: MVVM**, strict one-way dependency chain:
  `Activity/Fragment` → `ViewModel` → `Repository` → `Room DAO`.
  - Activities/Fragments hold no business logic; they observe `ViewModel` state (`LiveData`/`StateFlow`) and forward user actions.
  - `ViewModel`s never reference Android view/context types (no `Context`, no `View`, no `Activity`) and never touch a DAO directly — always go through a `Repository`.
  - `Repository` is the only layer that talks to Room DAOs (and any future data sources, e.g. network).
- **Room** for all local persistence — entities, DAOs, and a single `RoomDatabase` subclass.
- **Navigation Component** for screen flow — one `NavHostFragment` + nav graph, no manual `FragmentTransaction`/Intent-based screen juggling between app screens.
- **Material Design 3** components and theming throughout. The base theme already extends `Theme.Material3.DayNight.NoActionBar` (`app/src/main/res/values/themes.xml`) — keep new screens on M3 components (`MaterialButton`, `TextInputLayout`, `MaterialCardView`, `TopAppBar`, etc.), not plain platform widgets or M2 equivalents.

## Graded-coursework commenting requirement

**Every Kotlin file and every XML layout must have clear explanatory comments.** This is coursework being graded on demonstrated understanding, not just working code — comments should explain *why*/*what this accomplishes* for a reader learning the pattern (e.g. why a Repository wraps the DAO, what a ConstraintLayout constraint is doing), not just restate the code. Missing or sparse comments on a new file is a gap to fix, not a style nitpick.

- Kotlin: doc comment on each class explaining its role in the MVVM chain; comments on non-trivial logic.
- XML layouts: comments on the overall screen purpose and on any non-obvious view group/constraint choices.

## SDK / build config

- `minSdk = 24`, `compileSdk = 37`, `targetSdk = 37`.
- Kotlin DSL (`build.gradle.kts`) for all Gradle files; versions centralized in `gradle/libs.versions.toml`.
- The project currently has **no Kotlin Gradle plugin declared** in `app/build.gradle.kts`/`libs.versions.toml` even though source is Kotlin — check this before assuming Kotlin-specific build features (e.g. `kotlin-parcelize`, kapt/KSP for Room) are available; add the `org.jetbrains.kotlin.android` plugin (and KSP for Room's annotation processing) when introducing Room.

## Current state vs. conventions (as of initial setup)

This project was scaffolded by the Android Studio wizard and does not yet reflect the conventions above. Known gaps to address as the app is built out:
- No ViewBinding enabled; `MainActivity` uses `findViewById`.
- No Room, no Navigation Component, no ViewModel/Repository layers yet — only `MainActivity` + default `ConstraintLayout` screen exist.
- No comments in the existing generated files (`MainActivity.kt`, `activity_main.xml`) — bring them up to the commenting standard when modified.

Don't treat the above as intentional design decisions to preserve — they're wizard defaults to replace as real features are added.