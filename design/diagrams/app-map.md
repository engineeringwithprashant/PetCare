# PetCare — App Map & Screen Hierarchy

A written description of every screen in the app, how they connect, and what happens underneath each
one. This is the text version of `app-navigation-map.drawio.xml` and `auth-flowchart.drawio.xml`,
written out in full so the whole app can be read without opening a diagram tool.

**Nothing here is part of the app build.** This folder sits outside `app/src/`, so Gradle never
compiles, packages or lints it.

Drawn from the code, not from memory. The sources are listed under [Keeping this honest](#keeping-this-honest)
at the end.

---

## Contents

1. [The app in one paragraph](#1-the-app-in-one-paragraph)
2. [The shape of the app](#2-the-shape-of-the-app)
3. [Full screen hierarchy](#3-full-screen-hierarchy)
4. [Layer 1 — Authentication](#4-layer-1--authentication)
5. [Layer 2 — The five tabs](#5-layer-2--the-five-tabs)
6. [Layer 3 — Screens under a tab](#6-layer-3--screens-under-a-tab)
7. [Layer 4 — Sideways screens](#7-layer-4--sideways-screens)
8. [Navigation rules](#8-navigation-rules)
9. [What is on screen: the chrome](#9-what-is-on-screen-the-chrome)
10. [How a screen actually works (MVVM)](#10-how-a-screen-actually-works-mvvm)
11. [The data model](#11-the-data-model)
12. [How data gets to the cloud](#12-how-data-gets-to-the-cloud)
13. [What runs when no screen is open](#13-what-runs-when-no-screen-is-open)
14. [End-to-end journeys](#14-end-to-end-journeys)
15. [Keeping this honest](#keeping-this-honest)

---

## 1. The app in one paragraph

PetCare is an offline-first Android app for looking after pets. A user signs in, adds their animals,
builds **care routines** out of repeating **tasks**, ticks those tasks off on a daily **checklist**,
records **vaccinations** and **spending**, and saves the **vets, groomers, parks and shops** they use.
Everything is stored locally in Room and works with no network at all; Firebase is used only at the
edges — Firebase Authentication checks the password, and Cloud Firestore keeps a backup mirror.

There is **one Activity** (`MainActivity`) and **one navigation graph**
(`res/navigation/nav_graph.xml`). Every screen is a Fragment swapped in by the Navigation Component.
No screen is ever opened with a manual `FragmentTransaction` or an `Intent`.

---

## 2. The shape of the app

The graph is in two halves, split by whether the user is signed in:

```
  ┌───────────────────────────────────────────────────────────┐
  │  AUTHENTICATION                                           │
  │  No bottom bar, no top app bar. The user has no account    │
  │  session yet. 5 destinations.                             │
  └────────────────────────┬──────────────────────────────────┘
                           │  sign-in succeeds
                           │  (the whole auth half is popped off
                           │   the back stack — see §8)
                           ▼
  ┌───────────────────────────────────────────────────────────┐
  │  THE SIGNED-IN APP                                        │
  │                                                           │
  │  5 TOP-LEVEL TABS  ── peers of each other, bottom bar     │
  │      │                                                    │
  │      ├─ screens UNDER a tab ── pushed on top, back arrow  │
  │      │                                                    │
  │      └─ SIDEWAYS screens ── reached from the shared        │
  │           header, belong to no tab                        │
  └───────────────────────────────────────────────────────────┘
```

Three kinds of destination, and the difference matters because it decides the chrome:

| Kind | Example | Bottom bar | Back arrow |
|---|---|---|---|
| **Top-level tab** | Home, Pets, Tasks, Places, Money | Shown, that tab lit | No — they are peers, not a stack |
| **Under a tab** | Pet detail, Add routine, Add expense | Shown, parent tab stays lit | Yes |
| **Sideways** | Profile, Notifications, Assistant | Shown, *previous* tab stays lit | Yes |

"Sideways" is the interesting one. The profile, notifications and assistant screens are reachable
from all five tabs through the shared header, so they deliberately belong to **no** tab. The bottom
bar leaves whichever tab the user came from lit while they are open, which tells the user where they
will be when they come back.

---

## 3. Full screen hierarchy

Every destination in `nav_graph.xml`, in the order it appears there. 24 screens.

```
PetCare
│
├── AUTHENTICATION (start destination)
│   │
│   ├── authFragment ........................ Log in / Register (one screen, a toggle)
│   │     ├──> twoFactorFragment ............ 6-digit authenticator code
│   │     │      └──> recoveryCodeFragment .. "Lost access?" — use a recovery code
│   │     ├──> resetRequestFragment ......... Forgot password: type your email
│   │     │      └──> resetSentFragment ..... "Check your inbox", with a resend
│   │     └──> homeFragment ................. straight in, if 2FA is off
│   │
│   (every route into the app below clears the whole auth half)
│
├── TAB 1 — homeFragment .................... The dashboard
│   ├──> petDetailFragment .................. tap a pet card
│   ├──> addEditPetFragment ................. "Add pet" quick action / empty state
│   ├──> routineDetailFragment .............. tap a "coming up this week" row
│   └──> assistantFragment .................. "Ask the assistant" / the AI pill
│
├── TAB 2 — petsFragment .................... Every pet, one card each
│   ├──> addEditPetFragment ................. "+ Add a pet" row
│   └──> petDetailFragment .................. tap a pet
│         ├──> addEditPetFragment ........... Edit this pet
│         ├──> addRoutineFragment ........... "+" on the routines card  (also Edit routine)
│         ├──> routineDetailFragment ........ tap a routine card
│         │      └──> addEditTaskFragment ... FAB adds, tapping a task edits
│         ├──> addVaccinationFragment ....... "+ RECORD" on the vaccination card
│         ├──> checklistFragment ............ this pet's checklist (scoped — see §8)
│         └──> expensesFragment ............. this pet's spending (scoped)
│
├── TAB 3 — checklistFragment ............... Today's care checklist
│   └──> addEditTaskFragment ................ "+ Add task"
│
├── TAB 4 — placesFragment .................. Saved vets, groomers, parks, shops
│   ├──> addPlaceFragment ................... "+ ADD PLACE" (create only)
│   ├──> addEditPlaceFragment ............... long-press a saved place (edit / delete)
│   └──> addEditTaskFragment ................ "Book a visit", prefilled
│
├── TAB 5 — expensesFragment ................ Spending
│   └──> addEditExpenseFragment ............. FAB adds, tapping a row edits
│
└── SIDEWAYS — reached from the shared header, from any tab
    │
    ├── profileFragment ..................... the app monogram (top-left of the header)
    │     ├──> accountDetailsFragment ....... "EDIT" — name, phone, role
    │     ├──> changePasswordFragment ....... "UPDATE" — set a new password
    │     ├──> twoFactorSetupFragment ....... turning the 2FA switch on
    │     │      └──> recoveryCodesFragment . the ten codes, shown once
    │     ├──> checklistFragment ............ tap a saved delegation contact
    │     └──> assistantFragment ............ the AI pill
    │
    ├── notificationsFragment ............... the pet-count badge in the header
    │     ├──> petDetailFragment ............ an alert about the animal
    │     ├──> checklistFragment ............ an alert about care
    │     └──> assistantFragment ............ the AI pill
    │
    └── assistantFragment ................... "Ask the assistant" / the AI pill
```

---

## 4. Layer 1 — Authentication

Five screens, all shown before anyone is signed in. All five hide the top app bar **and** the bottom
tabs — a row of tabs leading into an account nobody has signed into would be worse than no chrome.

### authFragment — Log in / Register

The app's front door and the graph's start destination.

- **One destination, two forms.** Log in and register are the same screen with a link at the foot
  that switches between them. Both forms are in the layout at once and only one is visible, so text
  typed into one survives switching to the other and back. Switching is not navigation and adds
  nothing to the back stack.
- **Ways in:** email + password, **phone number** + password (only on a handset this account has used
  before — the phone→email mapping is never synced), or **Google Sign-In**.
- **The failed-attempt lockout.** Wrong passwords are counted per email address in SharedPreferences
  (`LoginAttemptStore`). After a few there is an inline warning card; after five the form is replaced
  by a locked panel with a live fifteen-minute countdown. This is a local speed bump on this device,
  not account security — Firebase's own rate limiting is the real protection.
- **Where a correct password goes** is decided by one question asked of `TwoFactorRepository`: does
  this account have an authenticator set up?
  - No → straight to **Home**.
  - Yes → to **twoFactorFragment**.

```
                       ┌─────────────────────────┐
   email / phone       │      authFragment       │       "FORGOT PASSWORD?"
   / Google  ─────────>│    Log in · Register    │──────────────┐
                       └────────────┬────────────┘              │
                                    │ password correct          ▼
                     ┌──────────────┴───────────┐    ┌──────────────────────┐
             2FA off │                          │ on │ resetRequestFragment │
                     ▼                          ▼    │  type your email     │
              ┌────────────┐        ┌─────────────────┴────┐        │
              │ homeFragment│       │  twoFactorFragment   │        ▼
              └────────────┘        │  6-digit TOTP code   │  ┌──────────────────┐
                     ▲              └──────────┬───────────┘  │ resetSentFragment│
                     │                         │ "LOST ACCESS?"│ link sent, resend│
                     │                         ▼               └──────────────────┘
                     │              ┌──────────────────────┐           │
                     └──────────────┤ recoveryCodeFragment │           │ "BACK TO LOG IN"
                        code accepted│ one of the 10 codes │           │ (pops, never pushes)
                                     └──────────────────────┘          ▼
                                                                  authFragment
```

### twoFactorFragment — the second factor

Step 2 of logging in. Takes the account's `userId` as a navigation **argument**, not from the session
— because the account is *not signed in yet*, and `SessionViewModel` has nothing in it at this point.
The code is typed on the app's own keypad (`CodeKeypadView`), not the system keyboard, and the screen
actively closes the keyboard if one was left open by the previous screen.

TOTP is computed entirely on-device (`dev.samstevens.totp`). The secret lives in Room and is never
uploaded.

### recoveryCodeFragment — the way in when the phone is gone

Reached from "LOST ACCESS?" on the two-factor screen, and carrying the same `userId` for the same
reason. Ten codes were generated at setup; each is **single-use** and is marked spent once accepted.
Three outcomes: accepted, wrong, or already used.

Back from here returns to the two-factor screen with the digits as they were — in case the
authenticator phone turns up after all.

### resetRequestFragment → resetSentFragment — password reset

Reset is **by email only**. The app asks Firebase to send a reset link and stops there; Firebase's own
web page, opened from the email, is what actually sets the new password. There is no code screen and
no new-password screen in the app.

The confirmation screen takes the address **as typed** as an argument, names it back to the user, and
offers a rate-limited resend. The request screen stays on the back stack, so system back returns to
the address ready to correct a typo.

"BACK TO LOG IN" on both screens **pops** back to `authFragment` rather than navigating to it, so a
second log in screen is never stacked on the first.

---

## 5. Layer 2 — The five tabs

The bottom bar is a custom view (`FloatingNavBar`), not a `BottomNavigationView` — the design needs
the active tab to *widen* into a pill with its name beside the icon, which Material's bar cannot
express. The five tabs are built in code from the `NavTab` enum, which is the single table tying each
tab to its destination id, its two icons, its label and the screens that count as being inside it.

### Tab 1 — homeFragment (the dashboard)

The screen the app opens on. Top to bottom:

- greeting, clock, and the **sync status** line (Synced / Syncing / Offline)
- three stat cards
- the **next task** and how many minutes away it is
- today's care progress and the completion **streak**
- a row of pet cards
- an **insight** derived from the data
- the **walk window** — live temperature from Open-Meteo, suggesting a good time to go out
- the week ahead, and quick actions ("Add pet", "Ask the assistant")

Nothing on this screen is worked out in the Fragment. `HomeDashboard` decides which task is next,
whether last Friday was a full day, what the insight should say — all before it reaches the View, so
a rotation redraws without recomputing.

### Tab 2 — petsFragment

One large card per animal: photo, species, name, carer, age, weight, and tasks due today. A headline
above adapts to the count ("Two pets, one household"), and an outlined "Add a pet" row closes the
list. Tapping a pet opens its **detail** screen, not the edit form — viewing an animal and changing
its record are different intentions.

### Tab 3 — checklistFragment (Tasks)

Today's care, and the busiest screen in the app:

- a **week strip** to pick a day
- a progress card for that day
- **category** chips and **pet** chips to filter
- the day's tasks grouped into **morning / afternoon / evening**, each tickable

Gestures, each a shortcut to something also reachable by a button TalkBack can find:

- swipe a card **right** → complete it (today only)
- swipe **left** → delete it, with undo
- **shake the phone** (or press "Reset day") → clear the day's ticks, after a confirmation

**Delegation** — handing a day's checklist to someone else — also lives here. Either channel shows the
exact message before anything leaves the app:

- **SMS**: contacts-permission rationale → system contact picker → message preview with its cost in
  messages → SMS-permission rationale → send. The app sends it itself.
- **Email**: no permission, no recipient — the checklist is handed to the user's own mail app as a
  draft. The preview still comes first, since otherwise the first sight of the message would be in
  another app next to a send button.

### Tab 4 — placesFragment

Saved vets, groomers, parks and shops, ordered by distance from home:

- a **map card** centred on the selected place, its name and distance on a pill, with "Directions"
  and "Google maps" links across the foot
- category chips
- a large card for the selected place — hours, phone, notes, booking, pinning
- the saved-places list; tapping a row re-centres the map and re-points both links

**A real map with no API key.** The card is a `WebView` over Google's keyless embed URL, and the links
use the documented Maps URL scheme, which Android hands to the Maps app when installed and to the
browser when not. Distances are computed on-device (`util/GeoDistance.kt`). The Maps SDK is
deliberately not a dependency — only Play Services **Location**, for the geotag.

### Tab 5 — expensesFragment (Money)

This month's spending: a dark total card with a stacked category bar, a legend of what it went on,
and every recorded expense. Swipe to delete, tap to edit, FAB to add.

---

## 6. Layer 3 — Screens under a tab

These are pushed *on top of* a tab. Each shows a back arrow and returns where it came from, and the
bottom bar keeps the parent tab lit the whole way down — opening a pet and then adding a routine to
it never leaves the Pets section.

| Screen | Parent tab | What it does |
|---|---|---|
| `petDetailFragment` | Pets | One pet: photo card, the facts a sitter would need, its vaccination card, and its care routines. The hub for everything about one animal. |
| `addEditPetFragment` | Pets | Create or edit a pet. `petId = -1` means create. |
| `addRoutineFragment` | Pets | Create or edit a care routine. `routineId = -1` means create. |
| `routineDetailFragment` | Pets | One routine and the tasks inside it. |
| `addVaccinationFragment` | Pets | Record a dose already given, and when the next falls due. Create only. |
| `addEditTaskFragment` | Tasks | Create or edit a task in a routine. Takes optional prefill values. |
| `addPlaceFragment` | Places | Save a place — by search, by dropping a pin, or from the current GPS position. Create only. |
| `addEditPlaceFragment` | Places | Change or delete a saved place. |
| `addEditExpenseFragment` | Money | Record or change an expense. |

Two conventions run through all of them:

- **`-1` means "create".** Every add/edit form is one destination, not two. The form knows it is
  creating rather than editing because the id it was handed is `-1`. (In the nav graph these literals
  need the `L` suffix — `-1L` — or the value is parsed as an `Int` and rejected.)
- **Required ids have no default.** `petId` on the expense and vaccination forms is required, because
  a row with no pet would violate the foreign key on its table.

`addEditTaskFragment` takes the **routine** id rather than the pet id: the pet is read *from* the
routine, so the two can never disagree about which animal a task belongs to. It also accepts three
nullable prefill arguments (`prefillTitle`, `prefillNotes`, `prefillCategory`) used only by "Book a
visit" on the Places tab; every other route in simply gets an empty form.

`addVaccinationFragment` and `addPlaceFragment` are a special case: they are steps *deeper* into their
tab rather than screens of their own, so they draw the **shared header** with their own round back
button instead of the toolbar, and the bottom bar keeps Pets / Places lit behind them.

---

## 7. Layer 4 — Sideways screens

Reached from the shared header rather than the bottom bar, available from every screen that draws that
header, and belonging to no tab.

### profileFragment — reached from the app monogram (top-left)

Who is signed in, how the app behaves for them, and the way out. A single long layout rather than a
list, because its content is fixed — except the contacts card, which inflates a row per contact.

Leads to:

- **accountDetailsFragment** — name, phone number and role. The **email is deliberately not editable**:
  it is the login identifier *and* the key Firestore files this account's data under, so changing it
  would strand everything already uploaded.
- **changePasswordFragment** — confirm the current password, set a new one.
- **twoFactorSetupFragment** → **recoveryCodesFragment** — see below.
- **checklistFragment** — tapping a saved delegation contact opens the Tasks tab with that person
  already chosen, so the checklist skips the channel dialog and the contact picker and goes straight
  to the confirmation preview.

Turning 2FA **off** is a dialog on this screen — one field and a confirm, so it needs no route.

### twoFactorSetupFragment → recoveryCodesFragment

Three steps, two screens:

1. scan the QR code (rendered on-device with ZXing core),
2. type a code back to prove the scan worked,
3. the ten recovery codes, shown **once**.

The write that actually switches 2FA on happens on the **codes** screen, not the setup screen — so an
account cannot end up with 2FA enabled and no recovery codes written down. Both of that screen's
arguments (`secret`, `codes: string[]`) are required with no default: a screen reached without the
secret could not switch anything on, and one reached without the codes would have nothing to show.
`string[]` is what a Bundle carries, so the codes survive the process being killed while the user is
copying them down.

These setup screens are **not** counted as authentication destinations, even though they are about
signing in: they are reached by somebody who is *already* signed in, so they get the ordinary app bar
and back arrow.

### notificationsFragment — reached from the pet-count badge

Care coming up, care that was missed, recent activity, milestones and tips — newest first, grouped
into today and earlier. Tapping an alert about the animal opens its detail screen; tapping one about
care opens the Tasks tab.

**Nothing here is stored.** Every alert is derived on the fly from tasks, routines, expenses and pet
records — which is why this destination takes no arguments at all: there is no alert id to pass,
because there are no alert rows.

### assistantFragment — reached from the AI pill / "Ask the assistant"

A pet-care Q&A screen backed by the OpenAI API, with suggestion chips seeded from the user's own pets.
It has no tab of its own on purpose: it is a thing you go and ask, not a section you live in.

Its API key is read from the git-ignored `local.properties` into `BuildConfig.OPENAI_API_KEY`.
**Leaving it out is fine** — the screen says plainly that the key is missing rather than crashing.

---

## 8. Navigation rules

### The five tabs are peers, not a stack

Tapping a tab navigates with: `popUpTo(start) { saveState = true }`, `restoreState = true`,
`launchSingleTop = true`. In plain terms:

- **popUpTo the start, saving state** — hopping between tabs does not pile five screens up, so system
  back never walks backwards through every tab the user visited. `saveState` lets a tab keep its
  scroll position and filters while it is away.
- **restoreState** puts that back, so a tab is where the user left it.
- **launchSingleTop** stops a second copy of a tab stacking on the one already showing — which,
  combined with the pop, makes re-tapping the current tab return to its root.

### Signing in clears the auth half

Every route from the authentication screens into the app uses
`popUpTo(nav_graph) { popUpToInclusive = true }` — it removes **every** auth screen from the back
stack. Without it, pressing back from Home would return the user to the form they had just completed,
which looks exactly like being signed out.

Signing out does the mirror image: pop the whole graph inclusively, then go to `authFragment`. Nothing
is deleted — the session id is forgotten and Firebase is signed out; the pets and records stay on the
device for the next login.

### One global action, seven callers

`action_global_to_profile` is declared as a direct child of `<navigation>` rather than inside any
fragment, so it can be taken from **anywhere**. That is what the profile screen needs: the monogram in
the shared header opens it, and seven different screens draw that header. Seven ordinary actions would
say the same thing seven times and give seven chances for one to drift out of step.

### Two screens do two jobs each

`checklistFragment` and `expensesFragment` are each reached two ways:

| Reached from | `petId` | What the screen does |
|---|---|---|
| The bottom bar | `-1` (default) | Shows a row of **pet chips** to filter by; shared header; no back arrow |
| A pet's detail page | a real id | Shows **that pet alone**; chips hidden; toolbar with a back arrow and the pet's name |

One screen with a conditional rather than two near-identical ones — which is what keeps delegation,
shake-to-reset and swipe-to-delete existing in exactly one place.

This is also the one thing `AppBarConfiguration` cannot express ("top-level, except when it isn't"),
so `MainActivity.restoreUpArrowForPetScopedScreen` turns the back arrow back on when a real `petId`
arrives. It only ever turns the arrow *on*, leaving every other case to NavigationUI.

### No screen carries a `userId`

Apart from the two pre-sign-in screens (`twoFactorFragment`, `recoveryCodeFragment`), **no destination
takes the account as an argument.** The signed-in account lives in `SessionViewModel`, scoped to the
Activity and therefore shared by every screen. This is forced by the bottom bar: a bar navigates by
destination id with no arguments attached, so the tabs could never have been handed one.

---

## 9. What is on screen: the chrome

`MainActivity` owns the window, the top app bar, the bottom bar and the `NavHostFragment`, and listens
for every destination change to decide what to show. Three rules:

**1. The shared top app bar is hidden when the screen has its own header.** The dashboard, Pets,
Places, pet detail, add-place, add-vaccination, assistant, notifications, profile, and the Tasks and
Money tabs all draw `view_tab_header.xml` themselves — the monogram, the title, the theme toggle and
the pet-count badge. Every one of those depends on the screen's own data or state, which a toolbar
shared by twelve destinations has no way to supply. Leaving the shared bar above it would show the
screen's name twice.

**2. The bottom tabs are hidden on the authentication screens and while the keyboard is open.** Two
independent rules, so both are applied in one place (`updateBottomNavVisibility`) — otherwise
navigating with the keyboard open would put the tabs back, and the keyboard closing on an auth screen
would reveal tabs belonging to an account nobody has signed into.

**3. The Tasks tab carries a badge** showing how many care tasks are still open today, collected in
the Activity because the bar is on screen for all five tabs and everything under them.

Two smaller pieces of window handling worth knowing:

- **The opening animation.** A muted video plays over the overlay on a genuine cold start only (a
  rotation skips it). The app carries on starting up behind it the whole time — nothing waits — and a
  3-second budget started *before* the surface or decoder are touched guarantees a slow device sees
  *less of the animation*, never more waiting. Tap anywhere to skip.
- **Edge-to-edge insets.** The app draws behind the system bars, so the bars' size is applied back as
  padding at runtime. The **keyboard** has to be handled here too: once edge-to-edge is on, Android 11+
  no longer resizes the window when the keyboard opens, so the bottom padding is `max(systemBars, ime)`
  — the larger of the two, never the sum, because an open keyboard already covers the navigation bar.

---

## 10. How a screen actually works (MVVM)

A strict one-way dependency chain with no shortcuts:

```
  Fragment  ───>  ViewModel  ───>  Repository  ───>  Room DAO
  (View)          (state)          (the only layer      │
     ▲                              that talks to       │
     │                              data)               │
     └── observes StateFlow/LiveData                    ├──> FirestoreSyncService
                                                        ├──> FirebaseAuthService
                                                        ├──> WeatherApi / OpenAiApi
                                                        └──> LocationRepository
```

The rules the project holds to:

- **Fragments hold no business logic.** They observe `StateFlow`/`LiveData` and forward user actions.
  What they *do* own is everything that genuinely needs a `Context`: string resources and plurals,
  date and number formatting, dialogs, permission prompts, the `NavController`, and writing an
  exported file through a `ContentResolver`.
- **ViewModels never touch Android view or context types**, and never reach a DAO directly.
- **Repositories are the only layer that talks to Room**, or to any remote service.
- **Dependencies are wired by hand** in `PetCareApplication`, every property `by lazy`. No Hilt — for
  an app this size the Application class keeps all the wiring visible in one readable file, and
  `by lazy` makes each object an effective singleton created only when first asked for.

### Firebase is a wrapper, not the architecture

Firebase is sealed behind exactly **two classes** — `FirestoreSyncService` and `FirebaseAuthService`.
No Fragment, ViewModel or adapter imports anything from `com.google.firebase`. `FirebaseAuthService`
converts every Firebase exception into the app's own `AuthFailure` enum
(`INVALID_CREDENTIALS`, `NO_NETWORK`, `RATE_LIMITED`, …), so nothing above the data layer ever sees a
Firebase exception class. The practical result: **Firestore could be swapped for a REST API by
rewriting one file**, without touching a single screen.

---

## 11. The data model

Room, SQLite, **schema version 12**, ten entities.

```
User ──1:N──> Pet ──1:N──> CareRoutine ──1:N──> CareTask ──1:N──> CareCompletion
  │            │
  │            ├──1:N──> Expense
  │            └──1:N──> VaccinationRecord
  │
  ├──1:N──> Place
  └──1:N──> RecoveryCode

SyncDeletion  (standalone — tombstones for rows deleted locally)
```

| Entity | Key fields | Notes |
|---|---|---|
| `User` | `fullName`, `email`, `phone`, `role`, `photoPath`, `twoFactorSecret`, `twoFactorEnabledAt` | **No password or hash is stored anywhere.** Those live in Firebase Authentication. |
| `Pet` | `ownerId`, `name`, `species`, `gender`, `breed`, `dateOfBirth`, `weightKg`, `photoPath`, `notes`, `dietaryNotes`, `allergies`, `favouriteToys` | Photos are files in internal storage; only the path is a column. |
| `CareRoutine` | `petId`, `title`, `category`, `repeatInterval`, `repeatUnit`, `startDate`, `isActive` | The repeating template. `isActive = false` is a paused routine. |
| `CareTask` | `petId`, `routineId`, `title`, `notes`, `requiredSupplies`, `category`, `timeOfDayMinutes`, `dueAt`, `isCompleted` | `timeOfDayMinutes` is what sorts it into morning / afternoon / evening. |
| `CareCompletion` | `taskId`, `petId`, `dayStart`, `completedAt` | One row per task per day — this is what makes streaks and history possible for a *repeating* task. |
| `Expense` | `petId`, `title`, `amount`, `category`, `incurredOn`, `notes` | |
| `Place` | `ownerId`, `name`, `category`, `area`, `latitude`, `longitude`, `phone`, `openingMinutes`, `closingMinutes`, `isPinned` | |
| `VaccinationRecord` | `petId`, `vaccineName`, `dateGiven`, `dateDue`, `clinic` | Urgency is **derived** from `dateDue`, never stored — so a screen left open overnight is correct in the morning with nothing written. |
| `RecoveryCode` | one row per code, single-use | Never leaves the device. |
| `SyncDeletion` | entity type + id | A tombstone, so a row deleted here is deleted in Firestore too. |

Every **synced** entity also carries two bookkeeping columns: **`updatedAt`** and **`isPendingSync`**.
`VaccinationRecord`, `CareCompletion`, `RecoveryCode` and `User`'s 2FA fields do not — they are
local-only by design.

---

## 12. How data gets to the cloud

### Room is the source of truth

**Every screen reads from Room and writes to Room, and only Room.** No Fragment or ViewModel knows
that Firestore exists. Being offline is the normal case, not an error state.

Firestore's *own* offline cache is switched off on purpose — every fetch passes `Source.SERVER`.
By default Firestore would happily answer a read from its local cache while offline, which would look
like a successful sync that in fact reached nothing. Insisting on the server means an offline device
**throws**, which is the honest answer the status indicator needs.

### How a change travels

1. The user saves something. The Repository writes to Room, stamps `updatedAt`, sets
   `isPendingSync = true`. **The screen is finished — the save succeeds instantly, online or off.**
2. `SyncManager` observes the database through Room `Flow`s, independently of any screen, and
   debounces for 1.5 s — so saving a routine with three tasks is one upload, not four.
3. One cycle runs, and the order matters:
   - **deletions first**, or the pull would find a row deleted here, see no local copy, and put it
     straight back;
   - then **pets → routines → tasks → expenses → places**, parents before children, because Room's
     foreign keys reject a task whose pet has not been inserted yet;
   - then the account's own profile document.
4. `isPendingSync` is cleared **only once Firestore confirms** the row.
5. On failure — offline being by far the likeliest — the cycle ends **quietly**. Nothing is shown,
   Room is untouched, nothing is lost. A 30-second timeout settles a flaky connection honestly on
   "Offline" rather than spinning on "Syncing" forever.

An unsent change is retried by **connectivity returning** (watched by `NetworkMonitor`, which requires
`NET_CAPABILITY_VALIDATED` so a captive-portal Wi-Fi never wakes a doomed sync), by any further local
edit, or by the next sign-in.

**Conflicts: last write wins.** Compare `updatedAt`; the later timestamp wins. Equal timestamps mean
the copies already agree and nothing happens — *unless* the local row is still `isPendingSync`,
because equal-looking timestamps are not proof it ever arrived. Anything already agreed on both sides
moves in neither direction, which is what makes the sync **settle**.

### Firestore layout

```
users/{userKey}                      ← profile fields, parent of everything below
users/{userKey}/pets/{petId}
users/{userKey}/routines/{routineId}
users/{userKey}/tasks/{taskId}
users/{userKey}/expenses/{expenseId}
users/{userKey}/places/{placeId}
```

`userKey` is the account's **lower-cased email address, not its Room row id** — row ids are handed out
per device, so the first account on any install is id 1, and two different people would both file
their data under "1" and each download the other's pets.

### What is never synced

Vaccination records and care completions (local-only tables); the 2FA secret and recovery codes
(second-factor material should not leave the device); photos (a `photoPath` is meaningless on another
device — it is stripped on upload and the local path copied back onto any pulled pet, so a sync from
another phone cannot blank a photo sitting on this one); the phone→email mapping (publishing a
directory of numbers to addresses is exactly what should not leave the device); and passwords.

### The one genuine online requirement

**Signing in needs a connection**, and the app keeps no persisted session, so it opens on the sign-in
screen every launch.

- **Works offline:** everything after login — viewing, adding, editing and deleting pets, routines,
  tasks, expenses and places; reminders firing; checklists; vaccination records.
- **Needs a connection:** registering, signing in, Google Sign-In, password change, password reset,
  deleting the account.

---

## 13. What runs when no screen is open

Reminders are **entirely local** — `AlarmManager` + `BroadcastReceiver`, no Firebase Cloud Messaging,
no server push. They work with no network at all.

```
PetCareApplication.onCreate()
        │
        └──> ReminderManager ──> ReminderScheduler ──> AlarmManager
                                                            │
                                    (task falls due)        ▼
                                                    ReminderReceiver
                                                            │
                                                            ▼
                                                    ReminderNotifier
                                                    (posts the notification)

Device reboots ──> BootReceiver ──> ReminderManager (alarms are cleared by a reboot,
                                                     so they are rescheduled here)
```

`SyncManager` is started from the same place, and runs independently of whatever screen is open.

---

## 14. End-to-end journeys

### A. First run — register and add a pet

```
authFragment (Register tab)
  → account created in Firebase Auth + a User row in Room
  → homeFragment  [auth half popped off the back stack]
  → empty dashboard, "Add pet" quick action
  → addEditPetFragment (petId = -1, ownerId = the session's account)
  → save → Room write, isPendingSync = true, back to the dashboard
  → 1.5 s later SyncManager uploads it, isPendingSync cleared
```

### B. Setting up care for a pet

```
petsFragment → tap the pet → petDetailFragment
  → "+" on the routines card → addRoutineFragment (petId, routineId = -1)
  → save → back on petDetailFragment, the routine now listed
  → tap the routine → routineDetailFragment (routineId)
  → FAB → addEditTaskFragment (routineId, taskId = -1)
  → save → the task now exists, ReminderScheduler sets its alarm
  → it appears on the Tasks tab on the day it falls due, and in the
    Home tab's "coming up this week"
```

### C. A normal day

```
open the app → splash → authFragment → sign in → homeFragment
  → "next task in 40 minutes"
  → Tasks tab → today already selected on the week strip
  → swipe a card right → CareCompletion row written, progress card and
    streak update, the Tasks tab badge drops by one
  → (or the notification fires first, and the user taps that instead)
```

### D. Handing the day over to a sitter

```
Tasks tab → "Delegate"           |  or:  profileFragment → tap a saved contact
  → choose SMS or Email          |         → checklistFragment arrives with
  → SMS: contacts permission     |           delegateToName + delegateToPhone
        → contact picker         |         → skips straight to the preview
  → message preview (always)     |
  → SMS permission → sent        |
```

### E. Turning on two-factor authentication, then signing in with it

```
profileFragment → 2FA switch on → twoFactorSetupFragment
  → scan the QR code → type a code back to prove it
  → recoveryCodesFragment → the ten codes, shown once
      ↳ this screen is what actually writes 2FA as enabled

next launch:
authFragment → password correct → twoFactorFragment (userId as an argument)
  → 6 digits on the app's own keypad → homeFragment
  or → "LOST ACCESS?" → recoveryCodeFragment → one code, spent → homeFragment
```

### F. Saving a vet and booking a visit

```
placesFragment → "+ ADD PLACE" → addPlaceFragment
  → search, drop a pin, or use the current GPS position → save
  → the new place's id and name are handed back to the Places tab through
    the previous back stack entry's SavedStateHandle, and it is selected
  → "Book a visit" → addEditTaskFragment, prefilled with the place's name,
    notes and category → save
```

---

## Keeping this honest

Written from the code. If any of the following changes, this file needs updating too:

| Source | What it decides here |
|---|---|
| `app/src/main/res/navigation/nav_graph.xml` | Every screen, argument and route in §3–§7 |
| `app/src/main/java/com/prash/petcare/MainActivity.kt` | The chrome rules in §9 and the tab options in §8 |
| `app/src/main/java/com/prash/petcare/ui/common/FloatingNavBar.kt` | Which five tabs the bar has, and which screens count as inside each |
| `app/src/main/java/com/prash/petcare/ui/auth/AuthViewModel.kt` | The sign-in, Google and registration branches, and the lockout (§4) |
| `app/src/main/java/com/prash/petcare/data/repository/TwoFactorRepository.kt`, `data/local/dao/RecoveryCodeDao.kt` | The three recovery-code outcomes and the single-use rule |
| `app/src/main/java/com/prash/petcare/data/auth/FirebasePasswordResetRepository.kt` | The password-reset branch |
| `app/src/main/java/com/prash/petcare/data/local/PetCareDatabase.kt` and `data/local/entity/` | The data model and schema version in §11 |
| `app/src/main/java/com/prash/petcare/data/sync/SyncManager.kt` | The sync cycle in §12 |
| `app/src/main/java/com/prash/petcare/reminder/` | The reminder chain in §13 |

Related: [`README.md`](../../README.md) in the project root (architecture and the sync design in
full), and the two `.drawio.xml` files in this folder (the drawn versions of §3 and §4).
