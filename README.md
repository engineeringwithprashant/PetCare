# PetCare

An offline-first Android app for looking after pets — care routines, a daily task checklist, vaccination records, spending, and saved vets and parks.

Built as university coursework in Kotlin, using MVVM with Room as the source of truth and Firebase only at the edges.

| | |
|---|---|
| **Language** | Kotlin |
| **UI** | XML layouts + ViewBinding (no Compose) |
| **Architecture** | MVVM — Fragment → ViewModel → Repository → Room DAO |
| **Database** | Room (SQLite), 10 entities, schema version 12 |
| **Navigation** | Navigation Component, single Activity |
| **Design** | Material Design 3, light and dark |
| **Cloud** | Firebase Authentication + Cloud Firestore |
| **min / target SDK** | 24 / 37 |

## Features

- **Pets** — a profile per pet: species, breed, gender, date of birth, weight and photo
- **Care routines** — reusable groups of tasks, such as a morning or weekly grooming routine
- **Checklist** — today's tasks, with completion history and streaks
- **Reminders** — local notifications that survive a reboot
- **Vaccinations** — per-pet records with due and overdue status, and CSV export
- **Expenses** — spending per pet and category, with monthly totals
- **Places** — saved vets, groomers, parks and shops on a map, with distance from the phone
- **Assistant** — a pet-care Q&A screen backed by the OpenAI API
- **Accounts** — email/password or Google Sign-In, two-factor authentication, recovery codes
- **Delegation** — hand a day's checklist to someone else by SMS or email

## Architecture

A one-way dependency chain:

```
Fragment  ──>  ViewModel  ──>  Repository  ──>  Room DAO
                                   │
                                   └────────>  Firestore / Firebase Auth
                                               Weather, OpenAI, Location
```

- Fragments hold no business logic — they observe state from a ViewModel and forward user actions back to it.
- ViewModels never touch view or context types, and reach data only through a Repository.
- Repositories are the only layer that talks to Room or to any remote service.
- Dependencies are wired by hand in `PetCareApplication`, each one `by lazy`. No Hilt — at this size, one readable file keeps the wiring visible.

Firebase is sealed behind two classes, `FirestoreSyncService` and `FirebaseAuthService`. Nothing above the data layer imports `com.google.firebase`, so Firestore could be swapped for a REST API by rewriting one file.

## Offline-first

Every screen reads and writes Room, and only Room, so the app works the same whether or not it has a connection. Firestore is a mirror for backup and restore, moved in and out by `SyncManager`.

Each synced row carries `updatedAt` and `isPendingSync`. A save writes to Room immediately and marks the row pending; the sync uploads it later and clears the flag only once Firestore confirms it. A failed cycle changes nothing locally, and the pending flag survives the app closing or the device rebooting, so the change is retried when connectivity returns, on the next edit, or at the next sign-in. Conflicts are resolved by last write wins.

Signing in is the one thing that needs a connection — credentials are checked by Firebase Authentication, and no session is persisted. Everything after that works offline.

## Project structure

```
app/src/main/java/com/prash/petcare/
├── PetCareApplication.kt   Dependency container; starts reminders and sync
├── MainActivity.kt         Single Activity, hosts the nav graph
├── data/
│   ├── local/              Room database, DAOs, entities, preference stores
│   ├── remote/             The only files that name Firebase; weather and assistant APIs
│   ├── repository/         The boundary every ViewModel talks to
│   ├── sync/               SyncManager, NetworkMonitor, SyncStatus
│   ├── auth/               TOTP, recovery codes, password reset
│   └── location/           Fused location provider
├── reminder/               AlarmManager reminders and boot receiver
├── ui/                     One package per screen: Fragment + ViewModel + adapters
└── util/                   Pure Kotlin helpers, unit-tested
app/src/main/res/
├── layout/                 73 XML layouts
├── navigation/             nav_graph.xml — every screen and transition
└── values/                 Theme, type scale, colours
firestore.rules             Security rules, pasted into the Firebase console
```

## Getting started

Requires Android Studio (recent stable) and JDK 11 or newer. If a bare `./gradlew` cannot find a JDK, point `JAVA_HOME` at Android Studio's bundled JBR.

### Firebase

`app/google-services.json` is committed, so the project builds as-is against its existing Firebase project. To use your own:

1. Create a Firebase project and add an Android app with the package name `com.prash.petcare`.
2. Enable **Authentication → Email/Password** and **Google**.
3. Create a **Cloud Firestore** database.
4. Download the new `google-services.json` into `app/`.
5. Paste [`firestore.rules`](firestore.rules) into Firebase console → Firestore → Rules → Publish. They are not deployed automatically.

### API keys (optional)

Both are read from `local.properties`, which is git-ignored, and surfaced as `BuildConfig` fields:

```properties
OPENAI_API_KEY=sk-your-key-here
MAPTILER_API_KEY=your-key-here
```

Leaving either out still builds and runs — the assistant screen and the map card each say plainly that their key is missing. This keeps the keys out of version control, though not out of the installed APK; a shipping app would put the calls behind a server.

### Build

```bash
./gradlew assembleDebug
./gradlew installDebug      # to a connected device
./gradlew test              # JVM unit tests
```

## Limitations

- The sync is backup and restore for one account, not a multi-device merge. Firestore document ids are Room row ids, which are assigned per device, so two phones could each create a pet with the same id. A proper fix is a UUID per row at creation.
- Last write wins loses concurrent edits — nothing merges two versions of a row field by field.
- No persisted session, so the app opens on the sign-in screen every launch and a new device needs a connection once.
- The login lockout counts failed attempts per device, as a speed bump; Firebase's own rate limiting is the real protection.
- The two-factor secret is stored on the device. It is never uploaded, but it is not hardware-backed either.

## Licence

University coursework, published for assessment. No licence granted for reuse.
