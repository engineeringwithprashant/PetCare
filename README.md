# PetCare

An offline-first Android app for looking after pets — their care routines, daily task checklist, vaccination records, spending, and saved vets and parks.

Built as university coursework. Kotlin, XML layouts, MVVM, Room as the source of truth, with Firebase used only at the edges.

| | |
|---|---|
| **Language** | Kotlin |
| **UI** | XML layouts + ViewBinding (**no Jetpack Compose**) |
| **Architecture** | MVVM — `Fragment` → `ViewModel` → `Repository` → `Room DAO` / remote service |
| **Local database** | Room (SQLite) — 10 entities, schema version 12 |
| **Navigation** | Navigation Component — one `NavHostFragment`, one nav graph |
| **Design** | Material Design 3, light and dark |
| **Cloud** | Firebase Authentication + Cloud Firestore |
| **min / target SDK** | 24 / 37 |

---

## Contents

- [What the app does](#what-the-app-does)
- [Architecture](#architecture)
- [How Firebase is used](#how-firebase-is-used)
- [Offline-first: the sync design](#offline-first-the-sync-design)
- [Screen flow](#screen-flow)
- [Project structure](#project-structure)
- [Building it](#building-it)
- [Known limitations](#known-limitations)

---

## What the app does

- **Pets** — profile per pet with species, breed, gender, date of birth, weight and photo
- **Care routines** — reusable groups of tasks (a morning routine, a weekly grooming routine)
- **Task checklist** — today's care tasks, with completion history and streaks
- **Reminders** — local notifications for upcoming tasks, surviving reboot
- **Vaccinations** — per-pet record with due/overdue status and CSV export
- **Expenses** — spending per pet, by category, with monthly totals
- **Places** — saved vets, groomers, parks and shops, geotagged, with distance from the phone
- **Walk window** — live temperature on the home screen to suggest a good time to go out
- **Assistant** — a pet-care Q&A screen backed by the OpenAI API
- **Accounts** — email/password or Google Sign-In, two-factor authentication (TOTP), recovery codes, password reset
- **Delegation** — hand a day's checklist to someone else over SMS or email

---

## Architecture

A strict one-way dependency chain, with no shortcuts:

```
Fragment  ──>  ViewModel  ──>  Repository  ──>  Room DAO
                                    │
                                    └────────>  FirestoreSyncService
                                                FirebaseAuthService
                                                WeatherApi / OpenAiApi
                                                LocationRepository
```

The rules this project holds to:

- **Fragments hold no business logic.** They observe `StateFlow`/`LiveData` from a ViewModel and forward user actions back to it.
- **ViewModels never touch Android view or context types**, and never reach a DAO directly — always through a Repository.
- **Repositories are the only layer that talks to Room**, or to any remote service.
- **Dependencies are wired by hand** in `PetCareApplication`, every property `by lazy`. No Hilt — for an app this size the Application class keeps all the wiring visible in one readable file, and `by lazy` makes each object an effective singleton created only when something first asks for it.

Every Kotlin file and XML layout carries explanatory comments, since the coursework is marked on demonstrated understanding rather than only on working code.

---

## How Firebase is used

**Two products, and nothing else:**

| Product | What it does here |
|---|---|
| **Firebase Authentication** | Stores and checks every account's email and password, hosts Google Sign-In, sends password-reset emails, and handles password change and account deletion. **No password or password hash is stored on the device.** |
| **Cloud Firestore** | A cloud mirror of the signed-in account's pet data, for backup and for restoring onto a new phone. |

Plus the `google-services` Gradle plugin and the Firebase BoM, so the two libraries always resolve to a tested-together pair of versions.

### Deliberately not used

- **Firebase Cloud Messaging** — reminders are entirely local: `AlarmManager` + `BroadcastReceiver` (`ReminderScheduler`, `ReminderReceiver`, `BootReceiver`, `ReminderNotifier`). There is no server push, and reminders work with no network at all.
- **Firebase Storage** — pet and profile photos stay in the device's internal storage (`PetPhotoStorage`).
- **Realtime Database, Crashlytics, Analytics, Remote Config, Cloud Functions, App Check** — none of them.

### Firebase is a wrapper, not the architecture

Firebase is sealed behind exactly **two classes**:

- `data/remote/FirestoreSyncService.kt` — the only class in the app that names a Firestore type
- `data/remote/FirebaseAuthService.kt` — the only class that names a Firebase Auth type

No Fragment, ViewModel or adapter imports anything from `com.google.firebase`. `FirebaseAuthService` goes further and converts every Firebase exception into the app's own `AuthFailure` enum (`INVALID_CREDENTIALS`, `NO_NETWORK`, `RATE_LIMITED`, …), so nothing above the data layer ever sees a Firebase exception class. `FirebaseAuth.getInstance()` and `FirebaseFirestore.getInstance()` are each called in exactly one place — `PetCareApplication`.

Two remote sources hide behind interfaces as well (`PasswordResetRepository`, `AssistantRepository`), so `PetCareApplication` is the single line in the app that names an implementation.

The practical result: **Firestore could be replaced with a REST API by rewriting one file**, without touching a single screen.

### Data sources that are not Firebase

| Source | Used for |
|---|---|
| **OpenAI API** (Retrofit) | The assistant screen |
| **Open-Meteo** (Retrofit) | The home screen's walk-window temperature |
| **Play Services Location** | Geotagging saved places and measuring distance. Location only — the Maps SDK is deliberately not a dependency; distances are computed on-device (`util/GeoDistance.kt`) and directions hand off to the user's own maps app via a `geo:` intent |
| **`dev.samstevens.totp` + ZXing core** | Two-factor authentication, entirely on-device. The TOTP secret and recovery codes live in Room and never leave the phone |

---

## Offline-first: the sync design

### Room is the source of truth

**Every screen reads from Room and writes to Room, and only Room.** No Fragment or ViewModel knows that Firestore exists. The app behaves identically whether or not it has ever reached the network — being offline is the normal case, not an error state.

Firestore is only a mirror moved in and out at the edges by `data/sync/SyncManager.kt`.

### Firestore's own offline cache is bypassed on purpose

Firestore ships with offline persistence, and this app switches it off for reads by passing `Source.SERVER` on every fetch:

```kotlin
.get(Source.SERVER)   // FirestoreSyncService
```

By default Firestore will happily answer a read from its local cache while the device is offline — which would look like a successful sync that in fact reached nothing. Insisting on the server means an offline device **throws**, which is the honest answer and the one the status indicator needs.

So the app's offline support does not come from Firebase. It comes from **Room plus its own pending-sync bookkeeping.**

### How a change travels

Every synced row carries two extra columns: `updatedAt` and `isPendingSync`.

1. The user saves something. The Repository writes to Room, stamps `updatedAt`, and sets `isPendingSync = true`. **The screen is finished — the save succeeds instantly, online or off.**
2. `SyncManager` observes the database through Room `Flow`s, independently of any screen, and debounces for 1.5 s — so saving a routine with three tasks is one upload rather than four.
3. One cycle runs, and the order matters:
   - **deletions first**, so a row deleted here is gone from Firestore before the pull reads it — otherwise the pull would find it, see no local copy, and put it straight back;
   - then **pets → routines → tasks → expenses → places**, parents before children, because Room's foreign keys reject a task whose pet has not been inserted yet;
   - then the account's own profile document, recording when this last happened.
4. `isPendingSync` is cleared **only once Firestore confirms** the row.
5. On failure — offline being by far the likeliest — the cycle ends **quietly**. Nothing is shown to the user, Room is untouched, nothing is lost. A 30-second timeout means a flaky connection settles honestly on "Offline" rather than spinning on "Syncing" forever.

### What retries an unsent change

`isPendingSync` survives the failure, the app closing, and the device rebooting. Three things bring it back:

- **Connectivity returning**, watched by `NetworkMonitor` — the one that matters, because it needs nothing from the user. It uses `drop(1)` so it means *came back* online rather than *is* online, and it requires `NET_CAPABILITY_VALIDATED`, so a captive-portal Wi-Fi never wakes a doomed sync.
- Any further local edit.
- The next sign-in.

### Conflict resolution: last write wins

Compare `updatedAt`; the later timestamp wins and the other copy is overwritten. Equal timestamps mean the two are already identical and nothing happens — **unless** the local row is still `isPendingSync`, because equal-looking timestamps are not proof it ever arrived, and re-sending is cheaper than assuming.

Anything already agreed on both sides appears in neither direction. That is what makes the sync **settle**: once a cycle has run, the next one writes nothing and so cannot trigger another.

### Firestore layout

```
users/{userKey}                      ← profile fields, parent of everything below
users/{userKey}/pets/{petId}
users/{userKey}/routines/{routineId}
users/{userKey}/tasks/{taskId}
users/{userKey}/expenses/{expenseId}
users/{userKey}/places/{placeId}
```

`userKey` is the account's **lower-cased email address, not its Room row id**. Row ids are handed out per device, so the first account on any install is id 1 — two different people would both file their data under "1" and each download the other's pets.

Subcollections under a per-user document, rather than top-level collections with an owner field, because it makes "everything for this user" a single path rather than a query, and because it is the shape security rules are written against. See [`firestore.rules`](firestore.rules) — one rule restricts a signed-in user to their own subtree by matching the email in their auth token against `userKey`.

Two consequences of Firestore that the sync handles by hand:

- **Firestore has no cascading delete.** `deleteDocumentsForPet` sweeps a deleted pet's routines, tasks and expenses (found by querying their `petId` field) to reproduce what SQLite's `ON DELETE CASCADE` does locally.
- **Batches cap at 500 writes**, so uploads are chunked — one round trip per chunk, applied atomically.

### What is deliberately never synced

| Not synced | Why |
|---|---|
| Vaccination records, care completions | Local-only tables; no tombstones kept for them |
| 2FA secret, recovery codes | Second-factor material should not leave the device |
| Photos | A `photoPath` is meaningless on another device. It is stripped on upload, and the *local* path is copied back onto any pulled pet, so a sync from another phone cannot blank a photo sitting on this one |
| The phone-number → email mapping | Publishing a directory of numbers to addresses is exactly what should not leave the device. Signing in by phone number therefore works on a handset the account has used before; a brand-new phone needs the email address once |
| Passwords | They live in Firebase Authentication. There is no password or hash anywhere in Room |

### The one genuine online requirement

**Signing in needs a connection.** Firebase Authentication checks credentials against the server, and the app keeps no persisted session — it opens on the sign-in screen every time.

- **Works offline:** everything after login — viewing, adding, editing and deleting pets, routines, tasks, expenses and places; reminders firing; checklists; vaccination records
- **Needs a connection:** registering, signing in, Google Sign-In, password change, password reset, deleting the account

---

## Screen flow

One `NavHostFragment` and one nav graph; no manual `FragmentTransaction` or `Intent` juggling between screens.

```
AuthFragment ──(2FA enabled?)──> TwoFactorFragment ──> RecoveryCodeFragment
     │                                   │                      │
     ├──> ResetRequest ──> ResetSent      │                      │
     │                                    │                      │
     └────────────────────────────────────┴──────────────────────┘
                                          ▼
                                    HomeFragment
        ┌───────────┬────────────┬────────┴──────┬───────────┬────────────────┐
      Pets      Checklist      Places        Expenses     Profile      Notifications
        │                                                    │
   PetDetail                                       Account details
        ├── Add/Edit Pet                           Change password
        ├── Routines ──> Routine detail            2FA setup ──> Recovery codes
        ├── Vaccinations                           Delegation
        └── Expenses                               Assistant
```

---

## Project structure

```
app/src/main/java/com/prash/petcare/
├── PetCareApplication.kt        Dependency container; starts reminders and sync
├── MainActivity.kt              Single Activity, hosts the nav graph
├── data/
│   ├── local/                   Room database, DAOs, entities, SharedPreferences stores
│   │   ├── PetCareDatabase.kt
│   │   ├── dao/                 One DAO per table
│   │   └── entity/              Pet, CareRoutine, CareTask, Expense, Place,
│   │                            VaccinationRecord, CareCompletion, User,
│   │                            RecoveryCode, SyncDeletion
│   ├── remote/                  The only files that name Firebase
│   │   ├── FirebaseAuthService.kt
│   │   ├── FirestoreSyncService.kt
│   │   ├── RemoteMappers.kt     Entity ↔ Firestore document map conversion
│   │   ├── weather/             Open-Meteo (Retrofit)
│   │   └── assistant/           OpenAI (Retrofit)
│   ├── repository/              The boundary every ViewModel talks to
│   ├── sync/                    SyncManager, NetworkMonitor, SyncStatus
│   ├── auth/                    TOTP, recovery codes, password reset
│   └── location/                Fused location provider
├── reminder/                    AlarmManager reminders + boot receiver
├── ui/                          One package per screen: Fragment + ViewModel + adapters
└── util/                        Pure Kotlin helpers, unit-testable
app/src/main/res/
├── layout/                      ~70 XML layouts
├── navigation/nav_graph.xml     Every screen and transition
└── values/                      M3 theme, type scale, colours
design/                          37 design mockups the screens were built from
firestore.rules                  Security rules — paste into the Firebase console
```

---

## Building it

### Requirements

- Android Studio (recent stable)
- JDK 11 or newer. If a bare `./gradlew` fails to find a JDK, point `JAVA_HOME` at Android Studio's bundled JBR.

### 1. Firebase

`app/google-services.json` is committed, so the project builds against its existing Firebase project as-is. To point it at your own instead:

1. Create a Firebase project and add an Android app with the package name `com.prash.petcare`
2. Enable **Authentication → Email/Password** and **Google**
3. Create a **Cloud Firestore** database
4. Download the new `google-services.json` into `app/`
5. Paste [`firestore.rules`](firestore.rules) into Firebase console → Firestore → Rules → **Publish**. These rules are not deployed automatically; until they are published, whatever rules the project was created with stay in force.

### 2. The assistant's API key (optional)

The assistant screen calls the OpenAI API. Its key is read from `local.properties`, which is git-ignored:

```properties
OPENAI_API_KEY=sk-your-key-here
```

It is surfaced to Kotlin as `BuildConfig.OPENAI_API_KEY`. **Leaving it out is fine** — the app still builds and runs, and the assistant screen says plainly that the key is missing rather than crashing.

This keeps the key out of version control. It does **not** keep it out of the installed APK, where a determined user could still extract it — for an app shipping to real users, the call would belong behind a server that holds the key.

### 3. Build

```bash
./gradlew assembleDebug
./gradlew installDebug      # to a connected device
./gradlew test              # JVM unit tests
```

---

## Known limitations

These are recorded honestly rather than hidden — each is a deliberate boundary, not an oversight.

- **The sync is backup-and-restore for one account, not a true multi-device merge.** Firestore document ids are Room row ids, which are assigned per device, so two phones independently creating a pet would both call it id 1 and a later sync would take them for the same animal. A real fix means globally unique ids (a UUID per row) assigned at creation — a change to how every row is made, not to `SyncManager`.
- **Last-write-wins loses concurrent edits.** Nothing merges two versions of a row field by field, so an edit on one device can be overwritten by a later edit on another. It is the simplest rule that always terminates, and its cost is stated rather than hidden.
- **No persisted session.** The app opens on the sign-in screen every launch, which also means the first launch on a new device needs a connection.
- **The login lockout is a local speed bump, not account security.** Failed attempts are counted per device in SharedPreferences (`LoginAttemptStore`); Firebase's own rate limiting is the real protection.
- **The 2FA secret is stored on the device.** It is never uploaded, but it is not hardware-backed either — see `TwoFactorRepository` for what that does and does not protect against.
- **`app/google-services.json` is committed.** Its API key identifies the Firebase project rather than authorising access, so this is the usual arrangement for an Android app; the actual protection is `firestore.rules` plus Firebase Authentication. Real secrets (the OpenAI key) stay in the git-ignored `local.properties`.
- **Pinch-zoom cannot be tested on the primary test device**, because SELinux there blocks the `sendevent` used to simulate multi-touch.

---

## Licence

University coursework, published for assessment. No licence granted for reuse.
