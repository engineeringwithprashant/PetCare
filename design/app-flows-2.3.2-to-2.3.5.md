# PetCare — How the app actually works (flowchart source notes)

Written from the code, not from the design brief. Each section below is the **real** sequence of
screens, dialogs, decisions and outcomes, so the flowcharts in Figures 7–10 can be drawn to match
what the app does. Where the brief's wording differs from the build, a **"Differs from the brief"**
note says exactly how — draw the code version, not the brief version.

Common conventions used throughout the app, worth knowing before reading any flow:

- All screen changes go through the **Navigation Component** (`nav_graph.xml`), one Activity, one
  `NavHostFragment`. There are no manual fragment transactions.
- Forms are **one screen in two modes**. An id argument of `-1` means "create", any real id means
  "edit". This is true of the routine, task, pet, expense and place forms.
- **Nothing is refreshed by hand.** Every list observes Room, so when a screen writes a row the
  screen behind it redraws itself. On a flowchart this means a "Save" arrow simply returns; there is
  no "reload list" box.
- A permission is always **explained first** (an in-app rationale dialog with "Not now" /
  "Continue"), and only then does the system prompt appear. "Not now" silently does nothing.

---

## 2.3.2 — Create a care routine (Figure 7)

### Main path

```
Pets tab (petsFragment)
  → tap a pet card
Pet detail (petDetailFragment)
  → tap "+" on the care routines card
Add routine form (addRoutineFragment, petId passed, routineId absent = create mode)
  → enter routine name
  → choose schedule: [ Daily ] or [ Weekly ]  (a two-button toggle group)
  → tap "Save routine"
        ↓ (validation — see decision below)
  → routine written to Room, screen closes (navigateUp)
Pet detail — the new routine card is already showing
  → tap the routine card
Routine detail (routineDetailFragment, routineId passed)
  → tap the FAB ("+")
Add task form (addEditTaskFragment, routineId passed, taskId = -1 = create mode)
  → title
  → time of day  (opens a Material time picker)
  → category     (dropdown: Feeding, Medication, Walk, Grooming, Vet visit, …)
  → required supplies (optional)
  → notes             (optional)
  → tap "Save task"
        ↓ (validation — see decision below)
  → task written to Room, screen closes
Routine detail — the new task appears in the list
```

Once the task row exists, three things happen on their own, with no further user action:

1. **The reminder alarm is planned.** `ReminderManager` permanently observes the task/routine/pet
   tables. The new row makes that stream re-emit, the manager recomputes the whole set of alarms
   and registers one alarm for this task's *next* occurrence.
2. **The Tasks tab badge updates**, if the task falls due today (`SessionViewModel.openTasksToday`).
3. **The task appears on the Tasks tab and on Home**, because both screens project the routine's
   repeat rule onto the days they show.

### Decisions to draw

**D1 — Routine form valid?** (`RoutineViewModel.save`)
- Name blank (after trimming) → inline error on the name field ("name required"), stay on the form.
  The error clears as soon as the user types again.
- Otherwise → save. There is no other validation on this form.

**D2 — Task form valid?** (`CareTaskViewModel.save`)
- Title blank → inline error on the title field.
- No time chosen → inline error on the time field.
- Both are checked together, so both errors can show at once. Stay on the form.
- Otherwise → save.

**D3 — Notification permission** (Android 13+ only), asked at the moment a **time is picked**, not
at save:
- Already granted, or Android 12 or lower, or already asked once on this screen → nothing shown.
- Otherwise → rationale dialog "PetCare would like to remind you…" with [Not now] / [Continue].
  - Not now → nothing happens.
  - Continue → system permission prompt.
    - Granted → nothing shown (the reminder simply arrives later).
    - Denied → snackbar explaining reminders won't show. If the refusal was permanent
      ("don't ask again"), the snackbar carries an **"Open settings"** action.
- **Important for the chart:** none of these branches blocks the save. The task saves and the alarm
  is registered either way; only the visible notification is lost.

### Differs from the brief

- The Add routine form has **two fields only: a name and a Daily/Weekly toggle**. There is **no
  category, no repeat-interval number and no start-date picker**. The repeat interval is always 1,
  and the start date is set automatically to **midnight today** (`CareSchedule.startOfToday()`).
  Editing a routine deliberately leaves the start date untouched so a weekly routine keeps landing
  on the same weekday.
- The task, not the routine, carries the **category** and the **time**. A task never carries a date —
  the routine decides which days it runs on. This is what lets one row mean "every morning".
- The reminder is **not scheduled by the save**. The save writes a row; the reminder system derives
  the alarms from the database. Draw this as a side effect off the save, not as a step in the form.
- Home's **"coming up this week"** starts **tomorrow** (1–7 days ahead) and **excludes every-day
  routines** — a daily feed would otherwise fill the whole card. It shows at most 4 rows. So a task
  on a *daily* routine appears on the Tasks tab but **not** in "coming up this week".

### Side branches worth including

- Pet detail → routine card overflow menu → **Edit** → same Add routine form, this time with a real
  `routineId`, so it loads the routine, prefills the name, sets the toggle, retitles the screen
  "Edit routine" and relabels the button "Save changes".
- Pet detail → routine card menu → **Pause/Resume** (a one-tap toggle; a paused routine stops
  producing tasks and its alarms are withdrawn) and **Delete** (confirmation dialog which spells out
  that every task inside goes too, and points at pausing as the reversible alternative).
- Routine detail also has an **Active** switch and a **Delete routine** button, plus
  **swipe-left-to-delete** on any task row, with an **Undo** snackbar.
- Tasks tab → "+ Add task" reaches the same task form, but first has to ask which routine — see 2.3.3.

---

## 2.3.3 — Daily checklist: mark done, edit, delete (Figure 8)

### The screen

`checklistFragment` serves two jobs from one destination:
- reached from the **bottom bar** → no `petId` argument (defaults to `-1`) → shows **all** the
  account's pets and offers the **pet filter chips**;
- reached from **Pet detail** → a real `petId` → shows that one pet, hides the chips, and puts
  "Tasks for <pet>" in the toolbar.

Layout top to bottom: week strip (7 days) → progress card (date, "n of m done", segmented bar,
"Reset day") → category filter chips → pet filter chips (tab mode only) → the task list grouped into
**Morning / Afternoon / Evening / Anytime** sections, each header carrying its own "2/3".

### Main path

```
Tasks tab
  → today is selected on the week strip by default
  → (optional) tap another day on the strip
  → (optional) tap a pet chip
  → (optional) tap a category chip
  → the list recomputes in memory (no new database query)
```

Both filters and the day selection are held in the ViewModel, so they survive rotation.

### The four branches from a task card

**Branch A — Mark done** (tick the circle, tap anywhere on the card, or swipe right)

```
tap circle / tap card / swipe right
  → D: is the selected day today?
        NO  → the circle is disabled, the card is not clickable, and swipe-right is not
              even offered by the gesture handler. Nothing happens.
        YES → completion written (CareTaskRepository.setTaskCompleted)
                • the task row gets isCompleted + completedAt = now
                • a row is added to the care_completions history table
              → card redraws: strikethrough title, faded emoji, accent outline
              → section header count updates ("2/3" → "3/3")
              → progress card count and segmented bar update
              → Home's care streak updates (it reads the same history table)
              → the Tasks tab badge in the nav bar decrements
```

Swiping right also rebinds the row, because the card stays in the list and must slide back into place.

**Why "today only":** a repeating task keeps a single "last completed at" timestamp. Ticking it
while looking at Friday would stamp it with *now*, i.e. today — so other days are shown read-only
as a plan. The guard is enforced in `ChecklistViewModel.setTaskDone` as well as in the UI.

**Branch B — Delete** (swipe left)

```
swipe left
  → task deleted immediately (no confirmation dialog)
  → snackbar: "<task> deleted"  [UNDO]
      → UNDO tapped   → the whole task object is re-inserted with its original id → row returns
      → not tapped    → snackbar dismisses, deletion stands
                        (a sync tombstone was recorded, so it will not come back from another device)
```

Note for the chart: deleting a task removes it from **every** day, because a task is a repeating
template, not one day's item.

**Branch C — Edit a task**

There is **no edit route from the Tasks tab**. Tapping a card marks it done. To edit:

```
Pets tab → pet → Pet detail → tap the routine → Routine detail → tap the task
  → Add/Edit task form in edit mode (real taskId)
      • fields prefilled, title says "Edit task", a "Delete task" button appears
      → Save    → validated as in 2.3.2 D2 → written → screen closes
      → Delete  → confirmation dialog naming the task
                    → Cancel  → stay
                    → Delete  → removed → screen closes
```

Home's "coming up this week" rows also open **Routine detail**, which is the other way in.

**Branch D — Reset the day** (shake the phone, or tap "Reset day")

```
shake  (ShakeDetector: >2.2 g, 3 movements within 1 s)   OR   tap "Reset day"
  → D: is the selected day today AND is anything ticked?
        NO  → nothing happens at all (the button is also disabled in that case)
        YES → D: is a reset dialog already open?
                YES → do nothing (a continued shake cannot stack a second dialog)
                NO  → confirmation dialog: "Reset today's care for <pet names>?"
                        → Cancel → nothing changes
                        → Reset  → every ticked task *currently shown* is un-ticked
                                   (scoped to the active pet and category filters)
                                 → history rows for those ticks are removed
                                 → snackbar "Day reset"
                                 → progress, streak and badge all fall back
```

The shake listener is registered in `onResume` and unregistered in `onPause`, so it only runs while
the tab is on screen.

### Differs from the brief

- **"Tap card → Edit task" does not exist here.** Tapping a card toggles completion. Editing lives
  on Routine detail. Draw the edit branch starting from Pet detail → Routine detail, or draw the
  Tasks tab card tap as "toggle done".
- Swipe right is **conditional**: only on today, and only on a task that is not already done. On any
  other day or any completed task, the gesture is not registered at all.
- Swipe left has **no confirmation** — the undo snackbar is the safety net.
- The "streak" is not stored on the task; it is derived from the separate `care_completions` table
  and shown on **Home**, not on the Tasks tab.
- The tab badge shows **open tasks due today across all pets**, and it re-computes itself at
  midnight even if the app is left open.

### Empty states (useful terminal nodes)

- Account has no pets → "no pets yet" empty state; the week strip, filters and action row are hidden.
- Pets exist but nothing is due on the chosen day → "nothing scheduled" empty state.
- Everything on the day is done → the list still shows, but the **Delegate** button is disabled.

---

## 2.3.4 — SMS delegation (Figure 9)

This is the longest flow in the app, and the one with the most decision points. Draw it as two
lanes (SMS and Email) plus a shortcut lane from Profile.

### Entry

```
Tasks tab → "Delegate" button
  → D: is anything outstanding on the shown day?
        NO  → snackbar. Two different messages:
                • everything shown is ticked      → "All done!"
                • nothing was scheduled at all    → "Nothing to send"
              → flow ends. (The button is also disabled in this case; the check repeats
                in case the state changed between the tap and the handler.)
        YES → channel chooser dialog
```

**Channel chooser** — a list dialog with two items and a Cancel button:
`[ Text message ]  [ Email ]  [ Cancel ]`

Both options are **always offered**; whether a channel works is checked *after* it is chosen, so the
user gets an explanation rather than a missing option.

### Lane 1 — SMS

```
"Text message" chosen
  → D1: does this device have telephony? (packageManager FEATURE_TELEPHONY)
        NO  → snackbar "This device can't send text messages" → END
        YES ↓
  → D2: is READ_CONTACTS already granted?
        YES → straight to the picker
        NO  → rationale dialog "PetCare needs contacts access to choose who to send to"
                [ Not now ] → nothing happens → END
                [ Continue ] → system permission prompt
                    → D3: granted?
                          NO  → snackbar "Contacts permission denied"
                                  + an "Open settings" action ONLY if the refusal was
                                    permanent (shouldShowRequestPermissionRationale == false)
                                  → END
                          YES ↓
  → System contact picker (ActivityResultContracts.PickContact)
        → D4: did the user actually pick someone?
              NO (backed out) → nothing happens → END
              YES ↓
  → Read the contact (two ContentResolver queries, on a background thread)
        → D5: three possible outcomes
              • Found(name, number)   → continue
              • NoPhoneNumber(name)   → snackbar "<name> has no phone number saved" → END
              • Unreadable            → snackbar "That contact couldn't be read"     → END
  → Recipient stored in the ViewModel  (DelegationStage.Preview)
  → PREVIEW DIALOG — "Send to <name>?"
        Contents:
          • "4 remaining tasks for Max"           (a scope summary)
          • the full message body, verbatim
          • "This will be sent as 2 messages"     (SmsSender.partCount — 160 chars per SMS)
        Buttons: [ Cancel ] [ Send ]
        → Cancel, back button, or tap outside → stage returns to Idle → END
        → Send ↓
  → Stage moves to Sending (so the preview cannot reappear behind the next prompt)
  → D6: is SEND_SMS already granted?
        YES → send
        NO  → rationale dialog
                [ Not now ] → nothing happens; the recipient is left pending → END
                [ Continue ] → system permission prompt
                    → D7: granted?
                          NO  → the chosen recipient is DISCARDED (stage → Idle) so the user
                                restarts cleanly, plus a snackbar "SMS permission denied"
                                (+ "Open settings" if the refusal was permanent) → END
                          YES ↓
  → SmsSender.send(number, message)
        • message split with divideMessage; multipart send when >1 part
        → D8: accepted by the system?
              YES → snackbar "Sent to <name>"
              NO  → snackbar "Couldn't send the message"
        → stage → Idle → END
```

### Lane 2 — Email

```
"Email" chosen
  → PREVIEW DIALOG first — "Review this email"
        Contents:
          • the scope summary ("4 remaining tasks for Max")
          • the subject line
          • the full message body
          • a note explaining it opens as a draft
        Buttons: [ Cancel ] [ Continue ]
        → Cancel → END
        → Continue ↓
  → EmailComposer.compose  →  Intent.ACTION_SEND, type "message/rfc822",
                              EXTRA_SUBJECT + EXTRA_TEXT, wrapped in createChooser
        → D: was a mail app found?
              YES → the user's mail app opens with the draft.
                    They fill in the recipient and send it THEMSELVES, outside PetCare.
              NO (ActivityNotFoundException) → snackbar "No email app set up" → END
```

**No permission is requested anywhere on the email lane, and no recipient is chosen in PetCare.**
That is the whole reason the two lanes look so different: SMS sends the message itself, email hands
it over.

### Lane 3 — Shortcut from Profile

```
Profile screen → delegation contacts card → tap a saved contact
  → navigates to checklistFragment carrying delegateToName + delegateToPhone
  → D: is anything outstanding?
        NO  → snackbar "nothing to delegate" → END
        YES → the recipient is handed to the ViewModel exactly as the picker would have
              → jumps STRAIGHT TO THE SMS PREVIEW DIALOG
                (channel chooser and contact picker are skipped — both questions were
                 already answered)
              → from here on, identical to Lane 1 from the preview onwards:
                the SEND_SMS rationale, the permission and the confirmed send all still happen.
```

The same card also offers **+ Add contact** (name, country code, number, optional note) and a
**remove** button per row with its own confirmation dialog.

Only the first arrival triggers this — a rotation does not reopen the preview.

### The message itself (identical on both channels)

Built by `buildDelegationMessage`, from the **outstanding** tasks only (completed ones are left out
deliberately, so nobody repeats a dose or a feed):

```
Care for <pet names> — <date>

Morning:
- Breakfast at 8:30 AM
  Supplies: dry food, bowl
  Notes: half a scoop
- Medication at 9:00 AM

Evening:
- Walk at 6:00 PM
```

Pet names are appended to each line only when the checklist spans more than one pet. Email adds a
subject line naming the pets; SMS has no subject.

### Differs from the brief

- The **email lane also shows a preview**, and shows it *before* leaving the app. The brief implies
  email goes straight to the draft.
- The **order is: preview → SMS permission → send**, not permission → preview. The user reads the
  exact text and the message count before the app asks to be allowed to send.
- The SMS lane has an extra first check the brief doesn't mention: **does the device have telephony
  at all**. On a Wi-Fi tablet the flow ends there with an explanation.
- Reading the chosen contact has **three** outcomes, not two — "no phone number saved" is its own
  branch.
- Denial is never fatal to the screen: every refusal ends in a snackbar, and a *permanent* refusal
  adds an "Open settings" action. Draw the permanent/temporary split — it is real in the code
  (`shouldShowRequestPermissionRationale`).

---

## 2.3.5 — Save a place and book a visit (Figure 10)

### Adding a place

```
Places tab (placesFragment)
  → "+ ADD PLACE"   (either the pill in the saved-places heading, or the
                     full-width button, which is the only one shown when nothing is saved yet)
Add place screen (addPlaceFragment — no arguments; it only ever creates)
```

**On arrival, the screen asks for the location immediately.** This is the one place in the app that
breaks the "never prompt on arrival" rule, because the heading, subtitle and map all already explain
why. Only on first creation — a rotation does not re-ask.

```
On open:
  → D1: is fine/coarse location already granted?
        YES → request a fix straight away
        NO  → rationale dialog "PetCare needs your location to save where this place is"
                [ Not now ] → treated as denied (see D2's denied branch)
                [ Continue ] → system prompt (ACCESS_FINE_LOCATION + ACCESS_COARSE_LOCATION
                               requested together; EITHER grant is accepted)
                    → granted → request a fix
                    → denied  → status DENIED
  → D2: the fix request — four outcomes, decided in this order
        a) device has no location hardware   → UNSUPPORTED
        b) permission not held               → DENIED, "permission was denied"
        c) no fix within 10 s                → DENIED, "try again outdoors"
           (a cached fix is accepted only if it is under 30 s old, high accuracy requested)
        d) a fix arrived                     → FOUND, with coordinates and ±accuracy
```

The screen then shows a status chip over the map (LOCATING / LIVE GPS / DEMO LOCATION / PICKED /
none) and a dark card reading e.g. `27.6743° N, 85.3094° E · ±8 m · 0.1 km from home`.

**Three ways the position can be set — all three are equal branches:**

1. **Live GPS fix** — the default, from the request above.
2. **Drag the map** — the map card is a draggable picker. Dragging it drops a pin at the map's
   centre; the card relabels itself "your own pin" and drops the ±accuracy figure (a hand-placed
   point has no device uncertainty to report). A *successful* refresh clears the pin; a *failed*
   refresh leaves it alone.
3. **"Use demo location"** — only offered when the status is DENIED or UNSUPPORTED, i.e. when
   retrying cannot help. It sets a fixed stand-in position and is labelled "DEMO" everywhere it
   appears, so it can never pass for a real fix. This is what stops a refused permission being a
   dead end.

When there is no position at all but the demo is available, the map is still shown centred on a
neutral starting point at a wider zoom, purely so the user has something to drag from. That centre
is **never saved** — the save button stays disabled until a real coordinate or a dropped pin exists.

```
Fill the form:
  • Name          (required)
  • Category      chips: 🏥 Vet · ✂️ Grooming · 🌳 Park · 🏪 Shop
  • Note          (optional — a default note is used if left blank)
  → a live summary reads back "Name · Vet clinic" and the coordinates (or "waiting for location")

  → D3: can save?      (name is not blank AND coordinates exist AND not already saving)
        NO  → the button is disabled and its LABEL names the missing condition:
                • "Save place — waiting for location"  while a fix may still arrive
                • "Save place — drop a pin"            once the device has given up
        YES → "SAVE PLACE"
  → Place written to Room (hours and phone deliberately left unset; they can be filled in
    later from the full place form)
  → the new place's id and name are handed BACK to the Places tab through the previous
    back stack entry's SavedStateHandle
  → screen closes
Places tab
  → the new place is SELECTED, so the map re-centres on it and Directions now points at it
  → snackbar: "'<name>' saved to places"  [UNDO]
        → UNDO → the place is deleted again → a second brief snackbar confirms the undo
        → ignored → the save stands
```

### Booking a visit

```
Places tab, with a place selected (featured card showing)
  → "BOOK A VISIT"
  → D4: how many active routines exist for the pets in scope?
        0  → snackbar "Create a routine on a pet's page first" → END
             (a care task must belong to a routine; the routine is what says which days it runs)
        1  → go straight to the task form for that routine
        2+ → a list dialog "Which routine?" showing "Health checks · Bella",
             "Health checks · Max", … plus Cancel
               → Cancel → END
               → a routine chosen ↓
  → Add/Edit task form, opened in CREATE mode and PREFILLED:
        • title    = "Visit <place name>"
        • notes    = the place's subtitle, phone and opening hours, one per line
        • category = Vet visit
        • time     = still empty — the user must pick it
  → the user picks a time and taps Save
  → D5: validated exactly as in 2.3.2 D2 (title + time both required)
  → task written → screen closes → it now appears on the Tasks tab and drives a reminder
```

**Nothing is written when "Book a visit" is tapped.** The form opens filled in; the booking only
exists once the user saves it. Draw the prefill as a data flow into the form, not as a write.

A prefill never overwrites the user's own typing — only empty text fields are filled — and an
unrecognised category name is ignored rather than trusted.

### Side branches

**Long-press a saved place row → the full place form** (`addEditPlaceFragment`, real `placeId`):
name, area, category dropdown, phone (country code + number, validated), opening and closing times
(time pickers, with a "clear hours" button), notes, and a **capture location** button that
re-requests the GPS. It has a **Delete place** button:

```
Delete place → confirmation dialog naming the place
  → Cancel → stay on the form
  → Delete → removed → screen closes → the list has already updated itself
```

**Other actions on the Places tab**, each a short branch off the featured card:
- tap a saved row → select it → the page scrolls to the map, which re-centres
- category chips → filter the list (if a filter matches nothing, the map and card hide and a
  "no matches" line shows instead of a blank gap)
- **PIN / PINNED** toggle on the featured place
- **Directions ↗** and **Google maps ↗** → a Google Maps URL handed to the Maps app, or to the
  browser when Maps isn't installed; failure → snackbar "No maps app"
- tapping the phone block → `ACTION_DIAL` (the dialler opens pre-filled; the app never places the
  call itself and needs no phone permission); no dialler → snackbar
- the **GPS chip** on the card is tappable when location has not been granted — it reads
  "tap for distance" and is the only other place on the tab that can request the permission
- map layer button → toggles between drawn map and satellite imagery
- location denied here → distances simply stay hidden (everything else still works) + a snackbar,
  with "Open settings" if the refusal was permanent

### Differs from the brief

- **There is no search.** The three ways to set a position are the **live GPS fix**, **dragging the
  map to drop a pin**, and the **demo location** fallback. No address search box exists.
- **Adding and editing are two different screens.** `addPlaceFragment` only ever creates and has
  three fields (name, category, note). `addEditPlaceFragment` is the full form reached by
  long-pressing a row, and it is the only one that can edit or delete.
- The permission prompt on Add place happens **on arrival**, not after the user asks for the
  current location — the whole screen is about capturing where they are.
- After saving, the return to the Places tab carries an **Undo snackbar**, which is worth drawing as
  a decision: undo → the place is deleted again.
- "Book a visit" needs a routine, so it has the same **0 / 1 / many** routine-chooser decision as
  "+ Add task" on the Tasks tab. This is easy to miss and is the branch most likely to be marked as
  missing.

---

## Quick reference — the decision diamonds, by figure

| Figure | Decision | Branches |
|---|---|---|
| 7 | Routine name blank? | inline error & stay / save |
| 7 | Task title or time missing? | inline error(s) & stay / save |
| 7 | Notification permission (API 33+) | already held / not now / granted / denied / permanently denied |
| 8 | Is the selected day today? | ticking & reset allowed / read-only plan |
| 8 | Undo tapped on delete? | task restored / deletion stands |
| 8 | Reset confirmed? | ticks cleared / nothing |
| 9 | Anything outstanding? | all done / nothing scheduled / continue |
| 9 | SMS or Email? | two lanes |
| 9 | Device has telephony? | explain & end / continue |
| 9 | Contacts permission | held / not now / granted / denied / permanently denied |
| 9 | Contact picked? | backed out / picked |
| 9 | Contact readable? | found / no number / unreadable |
| 9 | Preview confirmed? | cancel / send |
| 9 | SMS permission | held / not now / granted / denied / permanently denied |
| 9 | Send accepted? | "Sent to <name>" / "Couldn't send" |
| 9 | Mail app installed? | draft opens / "no email app" |
| 10 | Location permission | held / not now / granted / denied / permanently denied |
| 10 | Fix outcome | unsupported / denied / timed out / found |
| 10 | Can save? | disabled with a reason / save |
| 10 | Undo tapped after save? | place removed / save stands |
| 10 | How many routines for "Book a visit"? | 0 explain / 1 straight through / 2+ chooser |
| 10 | Delete place confirmed? | cancel / deleted |
