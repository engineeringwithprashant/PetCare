# Architecture, ER and SMS Sequence Diagrams — source notes

**This file is notes only. Nothing here is code and nothing here affects the app or the build.**

Covers three report figures:

| Section | Figure | Diagram |
|---|---|---|
| 3.1 | Architecture | MVVM layer diagram |
| 3.2 | ER | Room entity–relationship diagram |
| 3.4 | SMS sequence | SMS delegation sequence diagram |

Everything below was read **out of the code**, not out of the brief. Where the plan you gave me
differs from what the app actually does, a **⚠️ Correction** box says so. Draw the code version.

---
---

# PART A — Architecture Diagram (Section 3.1)

## A1. The numbers, checked against the code

Useful to put in the caption or the surrounding paragraph, because they are all verifiable:

| Thing | Count | Where |
|---|---|---|
| Activities | **1** (`MainActivity`) | `MainActivity.kt` |
| Fragment destinations in the nav graph | **26** | `res/navigation/nav_graph.xml` |
| Fragment classes | 26 + `AddEditTaskFragment` (used as a sheet) | `ui/**` |
| ViewModels | **29** | `*ViewModel.kt` |
| RecyclerView adapters | **13** | `*Adapter.kt` |
| Repositories | **11** | `data/repository/` |
| Room DAOs | **10** | `data/local/dao/` |
| Room entities | **10** | `data/local/entity/` |
| Database version | **12** | `PetCareDatabase.kt` |
| Migrations | 11 (`MIGRATION_1_2` … `MIGRATION_11_12`) | `PetCareDatabase.kt` |

> ⚠️ **Correction to your plan:** you wrote *"Fragments (24 screens)"*. The nav graph has **26**
> fragment destinations. Say 26.

## A2. The four layers, box by box

### Layer 1 — UI (`ui/**`)

| Box | Real names / notes |
|---|---|
| **MainActivity** | The only Activity. Holds the window, the `TopAppBar`, the `FloatingNavBar` and the `NavHostFragment`. Uses **ViewBinding** (`ActivityMainBinding`) — no `findViewById`. |
| **NavHostFragment + nav_graph.xml** | One nav host, one graph, 26 destinations. All screen changes go through the `NavController`; there are no manual `FragmentTransaction`s and no second Activity. |
| **Fragments (26)** | `HomeFragment`, `PetsFragment`, `PetDetailFragment`, `ChecklistFragment`, `PlacesFragment`, `ExpensesFragment`, `ProfileFragment`, `AuthFragment`, `TwoFactorFragment`, `AssistantFragment`, … |
| **Adapters (13)** | `ChecklistAdapter`, `PetAdapter`, `ExpenseAdapter`, `VaccinationAdapter`, `RoutineAdapter`, `ComingUpAdapter`, … |
| **Custom views / `ui/common`** | `FloatingNavBar`, `CodeKeypadView`, `SegmentedProgressView`, `CategoryBarView`, `MapEmbedView`, `PhotoCropView`, `PasswordChecklistView` + formatting helpers (`CareLabels`, `DateFormatting`, `Snackbars`). |

Worth a one-line note beside this layer: **Fragments hold no business logic** — they observe
`StateFlow`/`LiveData` and forward user actions back to a ViewModel.

### Layer 2 — ViewModel (`ui/**`)

| Box | Notes |
|---|---|
| **Screen ViewModels (29)** | One per screen, Fragment-scoped via `by viewModels()`. E.g. `ChecklistViewModel`, `HomeViewModel`, `PetDetailViewModel`, `PlacesViewModel`, `AuthViewModel`. |
| **SessionViewModel** | **Activity-scoped** (`by activityViewModels { SessionViewModel.Factory }`), so every screen reaches the same instance. Holds the signed-in user id in a **`SavedStateHandle`** so it survives process death, plus two derived values read live from Room: `profilePhotoPath` (the shared header's avatar) and `openTasksToday` (the Tasks tab badge). |

Note to put beside this layer: **ViewModels never touch `Context`, `View` or `Activity` types, and
never call a DAO directly.**

### Layer 3 — Data / Repository (`data/repository/`)

The **only** layer allowed to talk to a data source. All 11:

`UserRepository` · `PetRepository` · `CareRoutineRepository` · `CareTaskRepository` ·
`ExpenseRepository` · `VaccinationRepository` · `PlaceRepository` · `SyncDeletionRepository` ·
`TwoFactorRepository` · `AlertRepository` · `WeatherRepository`

Plus two behind interfaces, so the implementation is named in exactly one file:
`PasswordResetRepository` → `FirebasePasswordResetRepository`, and
`AssistantRepository` → `OpenAiAssistantRepository`.

### Layer 4 — Sources

| Source box | Reached through | What it is |
|---|---|---|
| **Room DAOs (10)** → **`PetCareDatabase`** | every repository | SQLite file `petcare.db`, **version 12** |
| **`FirestoreSyncService`** → Cloud Firestore | `SyncManager` | the cloud mirror |
| **`FirebaseAuthService`** → Firebase Auth | `UserRepository`, `FirebasePasswordResetRepository` | sign-in, reset, password change, delete |
| **`WeatherApi`** → Open-Meteo | `WeatherRepository` (+ `WeatherCache`) | walk-window temperature |
| **`OpenAiApi`** → OpenAI | `OpenAiAssistantRepository` | assistant Q&A |
| **`LocationRepository`** → Play Services Location | `PlacesViewModel` / place screens | GPS fix for Places |
| **`PetPhotoStorage`** → app internal storage | pet & profile photo screens | photos are files, not blobs |
| **SharedPreferences stores** | various repositories/VMs | `SettingsStore`, `AlertReadStore`, `LoginAttemptStore`, `DelegationContactStore`, `WeatherCache` |

> The five `SharedPreferences` stores live in `data/local/` beside Room but are **not** Room. Draw
> them as one small box labelled *"Local key–value stores (SharedPreferences)"* so the diagram does
> not imply they are tables.

## A3. The side panel — "Runs without a screen"

Everything here is alive with **no Fragment on screen**. Start both from `PetCareApplication.onCreate()`.

```
┌── Runs without a screen ─────────────────────────────────────────────┐
│                                                                      │
│  NetworkMonitor ──(connectivity returns)──> SyncManager              │
│                                                │                     │
│                                                └─> FirestoreSyncService
│                                                                      │
│  ReminderManager ──> ReminderScheduler ──> AlarmManager (OS)          │
│        ▲                                        │                    │
│        │                                   (alarm fires)             │
│        │                                        ▼                    │
│        └────────(re-plan)──────────────── ReminderReceiver           │
│                                                 │                    │
│                                                 ▼                    │
│                                           ReminderNotifier           │
│                                                                      │
│  BootReceiver ──(BOOT_COMPLETED)──> ReminderManager (re-register all) │
└──────────────────────────────────────────────────────────────────────┘
```

Two sentences worth writing under the figure, because they explain *why* it is shaped like this:

- **Reminders are derived state, not bookkeeping.** `ReminderManager` permanently observes the
  tasks/routines/pets Room queries. Any write re-emits, it recomputes the whole alarm plan and makes
  reality match. No screen ever "adds a reminder" — saving a row is the whole job. Only the **next**
  occurrence of each task is scheduled; when the alarm fires the app re-plans. Alarms do not survive
  a reboot, which is the only reason `BootReceiver` exists.
- **Sync is at the edges only.** `SyncManager` pushes deletions first, then syncs pets → routines →
  tasks → expenses → places (parents before children, so Room's foreign keys never reject a child),
  then refreshes the account document. Conflicts are **last write wins** on `updatedAt`. Failure is
  silent; `isPendingSync` is what makes the retry inevitable.

## A4. Arrows

| Arrow | Style | Meaning |
|---|---|---|
| UI → ViewModel → Repository → Source | **solid, downwards** | calls / user actions |
| Source → Repository → ViewModel → UI | **dashed, upwards** | data — `Flow`/`StateFlow` from Room, `LiveData` where used |

Label the dashed upward arrow out of Room with **"Flow — re-emits on every write"**, because that is
what makes the whole app refresh itself with no manual reload anywhere.

## A5. The two details you wanted shown

1. **Shade `FirestoreSyncService` and `FirebaseAuthService` together**, one tint, one bracket,
   labelled:
   > **"The only two classes in the app that import `com.google.firebase`"**

   Back it up in the text: no Fragment, ViewModel or adapter imports anything from
   `com.google.firebase`; `FirebaseAuthService` converts every Firebase exception into the app's own
   `AuthFailure` enum (`INVALID_CREDENTIALS`, `NO_NETWORK`, `RATE_LIMITED`, …), so nothing above the
   data layer ever sees a Firebase type at all. `FirebaseAuth.getInstance()` and
   `FirebaseFirestore.getInstance()` are each called in exactly one place —
   `PetCareApplication`.

2. **A small box to the side:**
   > **`PetCareApplication` — wires every dependency by hand, each `by lazy`**

   Thin line to the Data layer (and, honestly, to the side panel too, since it constructs
   `SyncManager` and `ReminderManager`). Text note: **no Hilt/Dagger** — for an app this size the
   Application class keeps all the wiring visible in one readable file, and `by lazy` makes each
   object an effective singleton built only when something first asks for it.

## A6. Drawing notes

- Four full-width horizontal bands, one per layer, each band labelled on the left edge
  (**UI / ViewModel / Data / Sources**). The side panel is a tall box down the right, outside the
  bands, with a dashed border so it reads as "parallel to the layers, not part of the stack".
- Colour: one tint per band (light → dark going down reads well). The two Firebase boxes get the
  shared shading from A5.1.
- Keep every arrow crossing exactly one band boundary. If you find yourself drawing UI → Repository,
  something is wrong — that arrow does not exist in this codebase.

---
---

# PART B — ER Diagram (Section 3.2)

## B1. Facts to state before the entities

- Database: **`petcare.db`**, Room, **schema version 12**, **10 entities**, **10 DAOs**.
- **Every date/time is a `Long` of epoch milliseconds**, not a `java.time` type — `minSdk 24`, and
  `java.time` needs API 26 or desugaring. Say this once; it explains every `Long` in the diagram.
- **Dates with no time attached** (date of birth, `dateGiven`, `dateDue`, `incurredOn`) are stored as
  **midnight UTC**.
- **Times of day** (`timeOfDayMinutes`, `openingMinutes`, `closingMinutes`) are **minutes since
  midnight** — 450 is 07:30 — so one row can repeat every day.
- Every `id` is `@PrimaryKey(autoGenerate = true)`, i.e. SQLite `INTEGER PRIMARY KEY AUTOINCREMENT`.
- **No password is stored anywhere in this database.** Firebase Auth holds it.

## B2. Entities — exact fields as the code declares them

Types below are the Kotlin types; `?` means nullable. Enum-typed columns go through
`Converters` and are stored as TEXT.

### `users` — **User**

| Field | Type | Notes |
|---|---|---|
| **id** | Long | PK |
| fullName | String | |
| email | String | **UNIQUE index** |
| phone | String? | the user's *own* number |
| role | UserRole | enum, default `OWNER` |
| passwordChangedAt | Long? | null = never changed |
| photoPath | String? | **column name is `photoUri`** (`@ColumnInfo`) |
| twoFactorSecret | String? | Base32 TOTP secret; **null is the "2FA off" switch** |
| twoFactorEnabledAt | Long? | |
| createdAt | Long | |

Sync: **profile fields only**, written to the Firestore `users/{email}` document.

> ⚠️ **Correction:** `User` has **no `updatedAt` and no `isPendingSync` columns.** Your note said
> "add `updatedAt` and `isPendingSync` to every synced entity" — do **not** add them to `User`.

### `pets` — **Pet**  *(synced)*

| Field | Type | Notes |
|---|---|---|
| **id** | Long | PK |
| *ownerId* | Long | FK → `users.id`, **ON DELETE CASCADE**, indexed |
| name | String | |
| species | PetSpecies | enum |
| gender | PetGender | enum, default `UNKNOWN` |
| breed | String? | |
| dateOfBirth | Long? | |
| weightKg | Double? | |
| photoPath | String? | **column name `photoUri`**; a file path, not image bytes |
| notes | String? | |
| dietaryNotes | String? | |
| allergies | String? | kept apart from notes — safety info |
| favouriteToys | String? | |
| createdAt | Long | |
| updatedAt | Long | last-write-wins key |
| isPendingSync | Boolean | never uploaded — local bookkeeping |

### `care_routines` — **CareRoutine**  *(synced)*

| Field | Type | Notes |
|---|---|---|
| **id** | Long | PK |
| *petId* | Long | FK → `pets.id`, **ON DELETE CASCADE**, indexed |
| title | String | |
| **description** | String? | ⚠️ missing from your list |
| category | TaskCategory | enum, default `OTHER` |
| repeatInterval | Int | default 1 |
| repeatUnit | RepeatUnit | enum, default `DAY` — `2` + `WEEK` = every two weeks |
| startDate | Long | |
| isActive | Boolean | pausing sets false rather than deleting |
| createdAt, updatedAt, isPendingSync | | |

### `care_tasks` — **CareTask**  *(synced)*

| Field | Type | Notes |
|---|---|---|
| **id** | Long | PK |
| *petId* | Long | FK → `pets.id`, **ON DELETE CASCADE** |
| *routineId* | Long**?** | FK → `care_routines.id`, **ON DELETE SET NULL** |
| title | String | |
| notes | String? | |
| requiredSupplies | String? | so a sitter knows what to bring |
| category | TaskCategory | enum, the checklist groups by it |
| timeOfDayMinutes | Int? | minutes since midnight |
| dueAt | Long | the **first** occurrence; later ones are derived, indexed |
| isCompleted | Boolean | |
| **completedAt** | Long? | ⚠️ missing from your list — this is the field the checklist actually tests |
| createdAt, updatedAt, isPendingSync | | |

Indexes: `petId`, `routineId`, `dueAt`.

> ⚠️ **Correction — the biggest one on this diagram.** The `CareRoutine → CareTask` foreign key is
> **`ON DELETE SET NULL`, not CASCADE**, and `routineId` is therefore **nullable**. Deleting a
> routine deliberately leaves its completed tasks in place as history, simply detached from a
> schedule. Draw that line with **`ON DELETE SET NULL`** on it, and make the relationship
> **`0..1` routine to many tasks**, not `1` to many.

Also worth a sentence: **a task is a repeating template, not one row per day.** That is why it has a
time of day rather than a timestamp, and why `care_completions` exists.

### `care_completions` — **CareCompletion**  *(local only)*

| Field | Type | Notes |
|---|---|---|
| **id** | Long | PK |
| *taskId* | Long | FK → `care_tasks.id`, **ON DELETE CASCADE** |
| *petId* | Long | FK → `pets.id`, **ON DELETE CASCADE** |
| dayStart | Long | local midnight of the day the tick counts for |
| completedAt | Long | exact moment of the tick |

Indexes: **UNIQUE (`taskId`, `dayStart`)** — one row per task per day — plus `petId` and
`completedAt`.

> ⚠️ **Correction:** this entity has **two** foreign keys, not one. Your relationship list only had
> `Pet → CareCompletion` and `CareTask → CareCompletion` separately — that is right, but make sure
> **both** lines are drawn, and both are CASCADE.

Why it exists (good sentence for the report): `CareTask.completedAt` can only remember the *last*
tick, which answers "is it done today?" and nothing about the past. The streak on the dashboard needs
to know whether every task due last Friday was done last Friday, so each tick also writes an
immutable row here.

### `expenses` — **Expense**  *(synced)*

| Field | Type | Notes |
|---|---|---|
| **id** | Long | PK |
| *petId* | Long | FK → `pets.id`, **ON DELETE CASCADE**, indexed |
| title | String | |
| amount | Double | |
| category | ExpenseCategory | enum |
| incurredOn | Long | indexed — **when the money was spent**, ≠ `createdAt` (receipts get typed in late) |
| notes | String? | |
| createdAt, updatedAt, isPendingSync | | |

### `vaccination_records` — **VaccinationRecord**  *(local only)*

| Field | Type | Notes |
|---|---|---|
| **id** | Long | PK |
| *petId* | Long | FK → `pets.id`, **ON DELETE CASCADE**, indexed |
| vaccineName | String | free text, not an enum — names differ by country/species/vet |
| dateGiven | Long | required |
| dateDue | Long? | **null = a one-off dose that never repeats** |
| clinic | String? | |

No `updatedAt`, no `isPendingSync`, **not synced**. Status (overdue / due today / up to date) is
**derived at draw time** from `dateDue`, never stored — a stored status starts lying at midnight.

### `places` — **Place**  *(synced)*

| Field | Type | Notes |
|---|---|---|
| **id** | Long | PK |
| *ownerId* | Long | FK → `users.id`, **ON DELETE CASCADE**, indexed |
| name | String | |
| category | PlaceCategory | enum |
| area | String? | |
| latitude | Double | **required** |
| longitude | Double | **required** |
| phone | String? | |
| openingMinutes | Int? | minutes since midnight |
| closingMinutes | Int? | may be *earlier* than opening, for past-midnight |
| **notes** | String? | ⚠️ missing from your list |
| isPinned | Boolean | |
| createdAt, updatedAt, isPendingSync | | |

Why it hangs off `User` and not `Pet`: a vet is shared by the whole household; hanging it off one
pet would duplicate the clinic per animal and delete it with the first pet.

### `recovery_codes` — **RecoveryCode**  *(local only)*

| Field | Type | Notes |
|---|---|---|
| **id** | Long | PK |
| *userId* | Long | FK → `users.id`, **ON DELETE CASCADE**, indexed (non-unique — ten rows per account) |
| **salt** | String | random bytes, Base64 |
| **codeHash** | String | hash of salt + code, Base64 |
| createdAt | Long | |
| usedAt | Long? | null = still usable; a spent code is **kept**, not deleted |

> ⚠️ **Correction — this is the one you asked me to check.** There is **no `code` column.** The
> plaintext code is never stored. The columns are **`salt`** and **`codeHash`**. Say so explicitly in
> the report: the table can *check* a code without being able to *produce* one, and a per-row salt
> defeats a precomputed table of all one-million six-digit hashes. Keeping a used row is what lets
> the app distinguish "you already used that code" from "that is not one of your codes".

### `sync_deletions` — **SyncDeletion**  *(local bookkeeping — a tombstone table)*

| Field | Type | Notes |
|---|---|---|
| **id** | Long | PK |
| entityType | SyncEntityType | enum: PET / CARE_ROUTINE / CARE_TASK / EXPENSE / PLACE |
| entityId | Long | the deleted row's PK |
| deletedAt | Long | the deletion's own "last write" timestamp |

**UNIQUE (`entityType`, `entityId`)** — the same number is a valid id in every table.
**No foreign keys, no owner column** — that is deliberate and worth a sentence: a deleted row cannot
be referenced, and row ids are unique per table across the whole device.

Why it exists: a deleted row is, by definition, not in the database, so the sync would never see the
deletion and the next pull would put the pet straight back. A tombstone turns "this row is absent"
into the sendable statement "this row was deleted".

## B3. Relationships — every line, with its cascade rule

| Parent | Child | Cardinality | `onDelete` |
|---|---|---|---|
| User | Pet | 1 : N | **CASCADE** |
| User | Place | 1 : N | **CASCADE** |
| User | RecoveryCode | 1 : N | **CASCADE** |
| Pet | CareRoutine | 1 : N | **CASCADE** |
| Pet | CareTask | 1 : N | **CASCADE** |
| Pet | Expense | 1 : N | **CASCADE** |
| Pet | VaccinationRecord | 1 : N | **CASCADE** |
| Pet | CareCompletion | 1 : N | **CASCADE** |
| CareRoutine | CareTask | **0..1 : N** | **SET NULL** |
| CareTask | CareCompletion | 1 : N | **CASCADE** |

**10 relationship lines.** `SyncDeletion` connects to nothing — draw it in a corner with a note:
*"no foreign keys — deliberately standalone (see §B2)"*.

Crow's-foot reading: the single bar goes at the parent end, the crow's foot at the child end. The
`CareRoutine → CareTask` line is the only one that gets a **circle** (optional) at the parent end,
because `routineId` is nullable.

## B4. Sync colouring and legend

| Fill | Meaning | Tables |
|---|---|---|
| **Colour 1 (e.g. pale blue)** | Synced to Firestore — carries `updatedAt` + `isPendingSync` | `pets`, `care_routines`, `care_tasks`, `expenses`, `places` |
| **Colour 2 (e.g. pale green)** | Partially synced — profile fields only, **no sync columns** | `users` |
| **Colour 3 (e.g. pale grey)** | Local only — never leaves the device | `care_completions`, `vaccination_records`, `recovery_codes` |
| **Colour 4 (e.g. white / dashed)** | Sync bookkeeping | `sync_deletions` |

Three colours would also do if you merge `users` into "synced" with an asterisk — but then the
asterisk has to say "profile fields only, no sync columns".

The Firestore shape, worth a small note beside the legend:

```
users/{email}                      ← profile fields
users/{email}/pets/{petId}
users/{email}/routines/{routineId}
users/{email}/tasks/{taskId}
users/{email}/expenses/{expenseId}
users/{email}/places/{placeId}
```

Note the remote key is the **email address**, not the local row id — local ids are per-device, so
they cannot identify a row across two phones.

## B5. Layout and notation

- **Layout:** `User` top-left. `Pet` centre. Care chain running right:
  `CareRoutine → CareTask → CareCompletion`. `Expense` and `VaccinationRecord` below `Pet`.
  `Place` and `RecoveryCode` below `User`. `SyncDeletion` alone in the bottom corner.
- **Bold** for primary keys, *italic* for foreign keys. Put `PK` / `FK` tags in a right-hand column
  if the tool supports it.
- Write the cascade rule (`ON DELETE CASCADE` / `ON DELETE SET NULL`) **on each line**, small.
- If the boxes get too tall, it is acceptable to drop `createdAt` / `updatedAt` / `isPendingSync`
  from every box and add one footnote: *"all synced tables also carry `createdAt`, `updatedAt` and
  `isPendingSync`; all tables carry `createdAt` except `care_completions`, `vaccination_records` and
  `sync_deletions`"*. Do **not** drop them silently.

---
---

# PART C — SMS Delegation Sequence Diagram (Section 3.4)

## C0. ⚠️ Correction to the lifelines — read this first

Your plan has **`ChecklistViewModel` building the message**. It does not.

What the code actually does:

- **`ChecklistViewModel`** holds only **state**: `uiState` (which carries `outstandingItems`,
  `outstandingCount`, `outstandingPetNames`, `selectedDay`) and `delegationStage` — a `StateFlow` of
  a sealed interface `DelegationStage { Idle | Preview(recipient) | Sending(recipient) }`. Its
  delegation methods are just `onRecipientChosen(name, phone)`, `onSendConfirmed()` and
  `onDelegationFinished()`.
- **The message text is built by a top-level function**, `buildDelegationMessage(...)` in
  `ui/checklist/DelegationMessage.kt`, **called by the Fragment**. It is deliberately a pure
  formatting helper in the View layer with no state.
- **`SmsSender`** (`util/SmsSender.kt`) is the object that wraps `SmsManager`.
- **`ContactReader`** (`util/ContactReader.kt`) is what reads the picked contact — two
  `ContentResolver` queries on `Dispatchers.IO`, returning a sealed `ContactLookup`
  (`Found` / `NoPhoneNumber` / `Unreadable`).

So use these **7 lifelines**, left to right:

| # | Lifeline | Stereotype |
|---|---|---|
| 1 | **User** | actor |
| 2 | **ChecklistFragment** | `«View»` |
| 3 | **ChecklistViewModel** | `«ViewModel»` — holds `DelegationStage` |
| 4 | **DelegationMessage** | `«helper»` — `buildDelegationMessage()` |
| 5 | **Android permissions** | `«system»` — `ActivityResultContracts.RequestPermission` |
| 6 | **Contact picker + ContactReader** | `«system»` — `ActivityResultContracts.PickContact`, then `ContactReader` |
| 7 | **SmsSender → SmsManager** | `«system»` |

If seven is too wide for the page, merge 4 into the Fragment's activation bar and add a note
*"message built by `buildDelegationMessage()`"* — but do **not** move it onto the ViewModel, that
would be wrong.

## C1. Main success path — every message, in order

Solid arrows = calls, dashed arrows = returns. Steps numbered so the alt frames below can refer to
them.

| # | From → To | Message |
|---|---|---|
| 1 | User → Fragment | tap **Delegate** (`delegateButton`) |
| 2 | Fragment → ViewModel | read `uiState.value` |
| 3 | ViewModel ⇢ Fragment | `outstandingCount > 0` |
| 4 | Fragment → User | **channel chooser dialog**: *Text message* / *Email* |
| 5 | User → Fragment | choose **Text message** |
| 6 | Fragment → SmsSender | `isSmsSupported(context)` — telephony feature check |
| 7 | SmsSender ⇢ Fragment | `true` |
| 8 | Fragment → User | **rationale dialog**: why contacts access is needed |
| 9 | User → Fragment | **Continue** |
| 10 | Fragment → Permissions | request `READ_CONTACTS` |
| 11 | Permissions ⇢ Fragment | granted |
| 12 | Fragment → Contact picker | `pickContactLauncher.launch(null)` |
| 13 | Contact picker ⇢ Fragment | `contactUri` |
| 14 | Fragment → ContactReader | `read(context, contactUri)` *(on `Dispatchers.IO`)* |
| 15 | ContactReader ⇢ Fragment | `ContactLookup.Found(name, phoneNumber)` |
| 16 | Fragment → ViewModel | `onRecipientChosen(name, phone)` |
| 17 | ViewModel ⇢ Fragment | `delegationStage = Preview(recipient)` *(emitted on the StateFlow)* |
| 18 | Fragment → DelegationMessage | `buildDelegationMessage(petLabel, dayStart, outstandingItems, showPetNames)` |
| 19 | DelegationMessage ⇢ Fragment | message text |
| 20 | Fragment → SmsSender | `partCount(context, message)` |
| 21 | SmsSender ⇢ Fragment | `2` |
| 22 | Fragment → User | **preview dialog**: summary + full message + *"Sent as 2 messages"* |
| 23 | User → Fragment | **Send** |
| 24 | Fragment → ViewModel | `onSendConfirmed()` |
| 25 | ViewModel ⇢ Fragment | `delegationStage = Sending(recipient)` |
| 26 | Fragment → User | **rationale dialog**: why SMS permission is needed |
| 27 | User → Fragment | **Continue** |
| 28 | Fragment → Permissions | request `SEND_SMS` |
| 29 | Permissions ⇢ Fragment | granted |
| 30 | Fragment → SmsSender | `send(context, phoneNumber, message)` |
| 31 | SmsSender → SmsManager | `divideMessage(message)`, then `sendTextMessage()` **or** `sendMultipartTextMessage()` |
| 32 | SmsManager ⇢ SmsSender | accepted |
| 33 | SmsSender ⇢ Fragment | `true` |
| 34 | Fragment → User | snackbar **"Sent to Ramesh"** |
| 35 | Fragment → ViewModel | `onDelegationFinished()` → `delegationStage = Idle` |

Three points worth a note on the diagram:

- Steps 8–9 and 26–27: the app **always shows its own rationale dialog before requesting**, not only
  when `shouldShowRequestPermissionRationale()` is true — that returns false on the very first
  request, exactly when the user has least idea why they are being asked.
- Step 31: `divideMessage` is needed because a single SMS holds only 160 GSM characters, and passing
  an over-length string to `sendTextMessage` would silently truncate it. The part count is shown at
  step 22 because each part is billed separately.
- Steps 32–33: `true` means **accepted for delivery, not delivered**. Tracking real delivery would
  need a `PendingIntent`. Worth stating — it is an honest limitation.

## C2. The alt / opt frames

Draw five. The first three are the ones you listed; 4 and 5 are real branches in the code that are
cheap to add and show thoroughness.

**Frame 1 — around step 6**, `alt`:
- `[telephony available]` → continue
- `[no telephony]` → Fragment → User: snackbar *"This device can't send texts"*, flow ends

  *Note:* the check is done **before** asking for any permission, so a Wi-Fi-only tablet is never
  prompted for something that could not work.

**Frame 2 — around steps 8–11, and again around steps 26–29**, `alt` with three branches:
- `[granted]` → continue
- `[denied]` → snackbar explaining the refusal, flow ends
- `[denied permanently]` → snackbar **with an "Open settings" action** that opens the app's entry in
  system settings

  *Note:* "denied permanently" is detected as `!shouldShowRequestPermissionRationale(permission)`
  *after* a refusal — the system will not prompt again, so settings is the only route left.
  Also: **"Not now"** on the rationale dialog silently does nothing and never reaches the system
  prompt.

**Frame 3 — around steps 22–23**, `alt`:
- `[Send]` → continue
- `[Cancel / back / tap outside]` → Fragment → ViewModel: `onDelegationFinished()` → `Idle`

**Frame 4 — around step 15**, `alt` on the `ContactLookup` result:
- `[Found]` → continue
- `[NoPhoneNumber]` → snackbar *"Ramesh has no phone number saved"*, flow ends
- `[Unreadable]` → snackbar *"That contact couldn't be read"*, flow ends

**Frame 5 — around step 33**, `opt`:
- `[send returned false]` → snackbar *"Couldn't send"*, then `onDelegationFinished()`

  `SmsSender.send` returns false on `IllegalArgumentException` (empty message / unusable number) or
  `SecurityException` (the permission revoked between the check and the call — the user can do that
  from settings at any moment).

## C3. Activation bars, and the one thing that makes this diagram interesting

- **Activation bar on the Fragment**: effectively the whole diagram, steps 1–35.
- **Activation bar on the ViewModel**: three short bursts — steps 2–3, 16–17, 24–25, 35.
- **Short bars** on DelegationMessage (18–19), Permissions (10–11, 28–29), Contact picker (12–15),
  SmsSender (6–7, 20–21, 30–33).

Add this note beside step 17, because it is the architectural point the figure demonstrates:

> The chosen recipient is held in the **ViewModel**, not in the Fragment, as
> `DelegationStage.Preview`. Picking a contact leaves the app, and the system may destroy the
> Fragment while the picker is open. Because the Fragment simply re-renders whatever
> `delegationStage` says, coming back — or rotating mid-flow — puts the preview dialog back with the
> recipient intact instead of losing it.

## C4. Scope note

**Keep the email branch out** (your Figure 9 covers it). One line of difference is worth a footnote
though: the email branch needs **no permission and no recipient** — it shows its own preview dialog
and then hands a draft to the user's mail app via `EmailComposer`; the app never sends an email
itself.

There is also a **second entry point** worth one sentence in the text but **not** in the diagram: the
Profile screen can start delegation with a saved contact already attached (nav arguments
`delegateToName` / `delegateToPhone`, from `DelegationContactStore`). That path skips steps 4–15 and
lands straight at the preview, but still requests `SEND_SMS` with its rationale.

---
---

# PART D — Checklist across all three figures

- [ ] **Architecture:** 26 fragments (not 24); MainActivity uses ViewBinding; solid arrows down,
      dashed up; Firebase pair shaded; `PetCareApplication` box present
- [ ] **ER:** 10 entities; `recovery_codes` shows **`salt` + `codeHash`**, never `code`;
      `care_tasks.routineId` nullable with **SET NULL**; `users` has **no** `updatedAt`/`isPendingSync`;
      `care_completions` has **two** FKs; `sync_deletions` has none; cascade rule written on all 10 lines;
      sync legend present
- [ ] **SMS sequence:** message built by **`DelegationMessage`/the Fragment**, not the ViewModel;
      telephony check before any permission; rationale dialog before each of the two permission
      requests; `divideMessage` shown; five alt/opt frames; note that the recipient lives in the
      ViewModel so the flow survives rotation
- [ ] All three figures numbered, captioned, and referred to by number in the body text
