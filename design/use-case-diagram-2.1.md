__# Use Case Diagram — Section 2.1 (drawing instructions)

**This file is notes only. Nothing here is code and nothing here affects the app.**

It is the full recipe for the use case diagram that goes in the report at **Section 2.1, directly
after Table 2.1**. Everything below is written so the diagram can be drawn without going back to
the code — every oval, every line, every label and every colour is listed explicitly.

Suggested figure caption:

> **Figure 1 — PetCare use case diagram.** Core brief requirements (blue), desirable feature (green)
> and additional features (grey).

---

## 0. Before you start — the four rules a marker looks for

1. **A use case is something the user gets a result from**, named *verb + noun* ("Record
   vaccination"), never a screen name ("Pets tab") and never a button ("Tap save").
2. **Actors sit outside the system boundary box, use cases sit inside it.** Nothing crosses that
   rule.
3. **Actor ↔ use case lines are plain solid lines with no arrowheads** (they are *associations*,
   not flow). Only `«include»` and `«extend»` lines are dashed with an open arrowhead.
4. **`«include»` and `«extend»` point in opposite directions.** Getting this backwards is the single
   most common lost mark:
   - `«include»` — base use case ──▷ included use case ("Log in *always* does Verify password")
   - `«extend»` — extending use case ──▷ base use case ("Use recovery code *sometimes* adds to Log in")

---

## 1. Canvas and layout

Page: **A4 landscape**, or a portrait box roughly **1600 × 1100 px** if drawing digitally.

```
┌──────────────┐        ┌─────────────────────────────────────────┐        ┌───────────────┐
│              │        │                PetCare                  │        │               │
│   Pet owner  │        │                                         │        │   Delegate    │
│    (stick    │────────│   ACCOUNT ──── PETS ──── CARE ────       │────────│   (stick      │
│    figure)   │  many  │   DELEGATION ──── PLACES ──── SYSTEM     │        │    figure)    │
│              │  lines │   (ovals, grouped top to bottom)         │        │               │
│              │        │                                         │        │   Firebase    │
│              │        │                                         │        │  «system»     │
│              │        │                                         │        │               │
│              │        │                                         │        │ Android system│
│              │        │                                         │        │  «system»     │
└──────────────┘        └─────────────────────────────────────────┘        └───────────────┘
   LEFT ~12%                      CENTRE ~60%                                 RIGHT ~25%
```

- The **system boundary** is a plain rectangle with the label **`PetCare`** at the top-centre,
  inside the box. No rounded corners, no shadow.
- **Pet owner** is on the **left**, vertically centred — it has the most lines, so give it room.
- **Delegate**, **Firebase**, **Android system** are stacked down the **right** edge, in that order
  top to bottom (that order keeps their lines short and stops them crossing).
- Draw the two system actors as stick figures with the stereotype **`«system»`** above the name, or
  as a rectangle with `«system»` inside — pick one style and use it for both.
- Leave ~60 px of white space between the boundary box and every actor. Lines should be short and
  straight; nothing should pass *through* an oval.

### Vertical order of the groups inside the box (top → bottom)

| # | Group | Why it sits here |
|---|---|---|
| 1 | **Account** | Top-right area, closest to Firebase |
| 2 | **Pets** | |
| 3 | **Care** | Largest group — give it the most vertical room |
| 4 | **Delegation** | Right-hand side, closest to Delegate |
| 5 | **Places** | |
| 6 | **System** | Bottom-right area, closest to Android system |

Group headings are **optional** — if you include them, write them as small grey italic text
(e.g. *Account*) floating above each cluster, **not** as boxes, so they are clearly not use cases.

---

## 2. Actors (4 total)

| Actor | Type | One-line description for the report text |
|---|---|---|
| **Pet owner** | Primary, human | The signed-in user who owns the pets and does almost everything in the app |
| **Delegate** | Secondary, human | A family member, friend or sitter who is sent a day's checklist by SMS or email; never signs in |
| **Firebase** | Secondary, system | External cloud service handling account sign-in, password reset and the cloud backup mirror |
| **Android system** | Secondary, system | The device platform that fires the scheduled alarm and shows the reminder notification |

Notes worth writing in the paragraph under the figure:

- The **Delegate is outside the system** because they never open PetCare — they only *receive* the
  message. That is why their only line is to **Delegate checklist**.
- **Firebase** and **Android system** are actors, not use cases, because they are things outside the
  app that the app interacts with.

---

## 3. Use cases — the ovals

There are **24 ovals** in total: **17 main** use cases plus **7 supporting** ones that exist only
because an `«include»` or `«extend»` arrow points at or from them. Draw all 24.

### 3.1 Main use cases (17) — colour by group

| # | Use case | Group | Colour | What it means (one line) |
|---|---|---|---|---|
| 1 | Register | Account | Blue (core) | Create an account with email and password |
| 2 | Log in | Account | Blue (core) | Sign in to an existing account |
| 3 | Reset password | Account | Blue (core) | Request a reset link/code for a forgotten password |
| 4 | Manage pets | Pets | Blue (core) | Add, view, edit and delete pet profiles |
| 5 | Record vaccination | Pets | Blue (core) | Log a vaccination with its date and next-due date |
| 6 | Record expense | Pets | Blue (core) | Log money spent on a pet, by category |
| 7 | Create routine | Care | Blue (core) | Create a reusable daily or weekly care routine for a pet |
| 8 | Add / edit / delete task | Care | Blue (core) | Maintain the care tasks inside a routine |
| 9 | Mark task done | Care | Blue (core) | Tick off a task on today's checklist |
| 10 | Reset day | Care | Blue (core) | Clear today's ticks and start the checklist again |
| 11 | Delegate checklist | Delegation | Grey (extra) | Send today's checklist to someone else to carry out |
| 12 | Save place | Places | Green (desirable) | Save a vet, groomer, park or shop with its location |
| 13 | Book a visit | Places | Green (desirable) | Arrange a visit to a saved place |
| 14 | Send reminder | System | Blue (core) | Notify the owner that a task is due |
| 15 | Back up data | System | Grey (extra) | Mirror the account's data to the cloud so it survives a new phone |

> That table lists 15 rows because rows 4 and 8 each cover a family of actions. If your Table 2.1
> splits them out (e.g. "Add pet", "Edit pet", "Delete pet"), keep the **diagram** at the grouped
> wording — a use case diagram is meant to stay readable, and CRUD detail belongs in the table, not
> in ovals.

### 3.2 Supporting use cases (7) — the targets of «include» / «extend»

These are still use cases and still get ovals inside the box. Draw them **smaller** and place them
**next to their base use case**, so each dashed arrow is short.

| # | Use case | Attached to | Relationship | Colour |
|---|---|---|---|---|
| 16 | Verify password | Log in | included by Log in | Blue (core) |
| 17 | Pick contact | Delegate checklist | included by Delegate checklist | Grey (extra) |
| 18 | Preview message | Delegate checklist | included by Delegate checklist | Grey (extra) |
| 19 | Get location | Save place | included by Save place | Green (desirable) |
| 20 | Create task | Book a visit | included by Book a visit | Green (desirable) |
| 21 | Use recovery code | Log in | extends Log in | Grey (extra) |
| 22 | Send by email | Delegate checklist | extends Delegate checklist | Grey (extra) |

> Colour a supporting oval the same colour as the use case it serves, **except** the two
> `«extend»` ones — those are optional extras, so they are grey.

**Oval style (all 24):** same size where possible (about **160 × 60 px**), 1 px dark outline, text
centred, sentence case. Do not number the ovals on the diagram itself — numbering belongs in
Table 2.1.

---

## 4. Relationships — the dashed arrows

Draw each of these as a **dashed line with an open (thin, unfilled) arrowhead**, with the
stereotype label sitting on the middle of the line.

### 4.1 «include» — the base use case *always* does this

| From (base) | ──▷ | To (included) | Label |
|---|---|---|---|
| Log in | ──▷ | Verify password | `«include»` |
| Delegate checklist | ──▷ | Pick contact | `«include»` |
| Delegate checklist | ──▷ | Preview message | `«include»` |
| Save place | ──▷ | Get location | `«include»` |
| Book a visit | ──▷ | Create task | `«include»` |

Read each one as: *"You cannot log in without verifying the password."*

### 4.2 «extend» — an optional variation that *sometimes* adds to the base

| From (extending) | ──▷ | To (base) | Label |
|---|---|---|---|
| Use recovery code | ──▷ | Log in | `«extend»` |
| Send by email | ──▷ | Delegate checklist | `«extend»` |

Read each one as: *"Sometimes, instead of the normal route, the user uses a recovery code — and that
adds to Log in."*

⚠️ **The arrowhead on an `«extend»` points at the base use case (Log in / Delegate checklist), which
is the opposite direction to `«include»`.** Check both of these twice before exporting.

Optional extension points (only if your report style uses them): write
`extension points: alternative sign-in` inside the **Log in** oval's lower half, and
`extension points: send channel` inside **Delegate checklist**. Skip this if it makes the ovals
crowded — it is not required.

---

## 5. Actor association lines (solid, no arrowheads)

### Pet owner → 15 lines

Connect **Pet owner** to every user-facing main use case:

1. Register
2. Log in
3. Reset password
4. Manage pets
5. Record vaccination
6. Record expense
7. Create routine
8. Add / edit / delete task
9. Mark task done
10. Reset day
11. Delegate checklist
12. Save place
13. Book a visit
14. Back up data
15. *(Send reminder — see note below)*

**Send reminder:** the owner *receives* the reminder rather than starting it, so the honest choice
is to connect **Android system** to it and **not** Pet owner. If your tutor prefers every use case
to touch the primary actor, add the Pet owner line too and label it **"receives"**. Pick one and say
which in the paragraph under the figure.

Do **not** draw Pet owner lines to the 7 supporting use cases (Verify password, Pick contact,
Preview message, Get location, Create task, Use recovery code, Send by email) — they are reached
through their base use case, and extra lines there is a classic mistake.

To stop the left side turning into a fan of 15 crossing lines, bring the lines out of the actor as a
small **bundle** that spreads once it is inside the boundary, and keep the vertical order of the
ovals the same as the list above.

### Delegate → 1 line

| From | To | Line label |
|---|---|---|
| Delegate | Delegate checklist | **"receives SMS"** |

### Firebase → 4 lines

| From | To |
|---|---|
| Firebase | Register |
| Firebase | Log in |
| Firebase | Reset password |
| Firebase | Back up data |

### Android system → 1 line

| From | To |
|---|---|
| Android system | Send reminder |

**Total lines to draw:** 15 (or 14) associations from Pet owner + 1 Delegate + 4 Firebase +
1 Android system = **21**, plus **7** dashed relationship arrows = **28 lines**.

---

## 6. Colours and legend

Three fills, all pale so the black text stays readable in a printed, possibly greyscale, report.

| Meaning | Fill | Outline | Suggested hex |
|---|---|---|---|
| Core brief requirement | Pale blue | Darker blue | fill `#DAE8FC`, stroke `#6C8EBF` |
| Desirable feature (Places) | Pale green | Darker green | fill `#D5E8D4`, stroke `#82B366` |
| Additional feature (my extras) | Pale grey | Mid grey | fill `#F5F5F5`, stroke `#999999` |

Actors stay **white / uncoloured**. The boundary box stays **white with a thin dark outline**.

**Legend box** — a small rectangle in the **bottom-left corner inside or just below** the diagram,
outside the system boundary, containing three small filled swatches:

```
┌───────────────────────────────┐
│ Legend                        │
│  ▪ blue   Core requirement    │
│  ▪ green  Desirable feature   │
│  ▪ grey   Additional feature  │
└───────────────────────────────┘
```

Greyscale safety: because blue and green print as similar greys, the *shape* of the legend swatches
should differ if you expect a black-and-white print — or simply add a one-word tag in the corner of
each oval. Optional.

---

## 7. Step-by-step drawing order (draw.io / diagrams.net)

Working in this order avoids most of the re-arranging:

1. **New blank diagram**, page A4 landscape. In the shape search box, enable the **UML** shape
   library (left panel → *More Shapes* → tick **UML**).
2. Drag a **rectangle** onto the middle of the canvas, resize it to about 60% of the page width and
   90% of the height. Double-click it, type `PetCare`. In *Format → Text*, set the label position to
   **Top**, alignment **Centre**. Right-click → *Edit Style*, and make sure it has
   `verticalAlign=top;` so the title sits inside the top edge.
3. Right-click the boundary → **Edit → Send to Back**, so ovals sit on top of it and can be moved
   independently.
4. Drag **4 Actor** shapes (search "actor"). Name them. Put Pet owner on the left; Delegate,
   Firebase, Android system down the right. For the two system actors, prefix the label with
   `«system»` and a line break (`Shift+Enter` inside the label).
5. Drag in the **17 main ovals** (search "ellipse" or the UML "Use Case" shape). Place them in the
   six groups, top to bottom, per §1. Select all of them and use
   **Arrange → Align → Center** and **Distribute → Vertical** to line the columns up.
6. Drag in the **7 supporting ovals**, each beside its base, sized a little smaller.
7. **Colour them**: select all the core ones together → *Format → Style* → fill `#DAE8FC`, line
   `#6C8EBF`. Repeat for the green group and the grey group. Colouring in three bulk selections is
   far faster than one at a time.
8. **Association lines**: hover an actor's edge until blue arrows appear, drag to the oval. Then
   select all the new lines and in *Format → Style* set **Line end: None** so the arrowheads
   disappear. Line style: solid.
9. **«include» / «extend» arrows**: draw the 7 dashed lines from §4, watching the direction. Select
   them, then *Format → Style* → **Dashed** on, **Line end: Open Arrow**. Double-click each line and
   type `«include»` or `«extend»`. (Type the guillemets as `«` `»`; if the keyboard will not produce
   them, `<<include>>` is accepted but looks less professional.)
10. **Legend**: a small rectangle bottom-left with three tiny coloured squares and their labels.
11. **Tidy**: *Arrange* → nudge anything overlapping; make sure no line crosses an oval and no label
    sits on top of another label.
12. **Export**: *File → Export as → PNG*, **zoom 300%**, **transparent background off**,
    **border width 10**. Save as `design/diagrams/use-case-diagram.png`, and also keep the editable
    source as `design/diagrams/use-case-diagram.drawio.xml` (that matches how the navigation map and
    auth flowchart are already stored in this repo).

---

## 8. Checklist before you paste it into the report

- [ ] All 24 ovals inside the boundary box; all 4 actors outside it
- [ ] Boundary box labelled `PetCare`
- [ ] Every oval named *verb + noun*, no screen names, no button names
- [ ] Actor lines are **solid with no arrowheads**
- [ ] All 7 relationship lines are **dashed with open arrowheads** and carry a `«include»` or
      `«extend»` label
- [ ] 5 `«include»` arrows point **away from** the base use case
- [ ] 2 `«extend»` arrows point **towards** the base use case (opposite direction)
- [ ] Delegate has exactly **one** line, labelled "receives SMS"
- [ ] Firebase has exactly **four** lines
- [ ] Android system has exactly **one** line
- [ ] No actor line goes to a supporting use case
- [ ] Three colours used consistently, and a legend explains them
- [ ] No lines cross an oval; nothing overlaps
- [ ] Figure numbered and captioned, and referred to by number in the Section 2.1 text
- [ ] Every use case in the diagram also appears in Table 2.1, with the same wording

---

## 9. Plain-text wire sketch (rough placement reference)

Not to scale — just the relative positions, so the finished diagram matches the plan.

```
                    ┌─────────────────────── PetCare ────────────────────────┐
                    │                                                        │
                    │  Account                                               │        ┌──────────┐
                    │     (Register)                                         │────────│ Delegate │
   ┌─────────┐      │     (Log in) ⇢«include»⇢ (Verify password)              │        └──────────┘
   │         │      │        ⇠«extend»⇠ (Use recovery code)                   │            ▲
   │   Pet   │      │     (Reset password)                                   │            │ "receives SMS"
   │  owner  │══════│                                                        │            │
   │         │ many │  Pets                                                  │        ┌──────────┐
   │  (stick │ lines│     (Manage pets)  (Record vaccination)                │────────│ Firebase │
   │  figure)│      │     (Record expense)                                   │        │ «system» │
   │         │      │                                                        │        └──────────┘
   └─────────┘      │  Care                                                  │
                    │     (Create routine)  (Add / edit / delete task)       │
                    │     (Mark task done)  (Reset day)                      │
                    │                                                        │
                    │  Delegation                                            │
                    │     (Delegate checklist) ⇢«include»⇢ (Pick contact)     │
                    │            │             ⇢«include»⇢ (Preview message)  │
                    │            ⇠«extend»⇠ (Send by email)                   │
                    │                                                        │
                    │  Places                                                │
                    │     (Save place)   ⇢«include»⇢ (Get location)           │
                    │     (Book a visit) ⇢«include»⇢ (Create task)            │
                    │                                                        │        ┌────────────────┐
                    │  System                                                │────────│ Android system │
                    │     (Send reminder)   (Back up data)                   │        │   «system»     │
                    └────────────────────────────────────────────────────────┘        └────────────────┘

   Legend:  ▪ blue = core requirement   ▪ green = desirable feature   ▪ grey = additional feature
```

---

## 10. Sentences you can adapt for the paragraph under the figure

> Figure 1 shows the use case diagram for PetCare. The **pet owner** is the primary actor and
> interacts with every user-facing use case. The **delegate** is modelled as a separate actor
> because they never sign in to the app — they only receive the day's checklist as an SMS or email,
> which is why their single association is to *Delegate checklist*. **Firebase** and the **Android
> system** are system actors: Firebase carries out account sign-in, password reset and the cloud
> backup, while the Android system fires the scheduled alarm behind *Send reminder*.
>
> Two relationship types are used. `«include»` marks behaviour that always happens as part of a
> larger use case — *Log in* always includes *Verify password*, and *Save place* always includes
> *Get location*. `«extend»` marks optional behaviour that only sometimes occurs — *Use recovery
> code* extends *Log in* when the usual credentials are unavailable, and *Send by email* extends
> *Delegate checklist* when SMS is not the chosen channel.
>
> Colour separates the three tiers of requirement: blue for the core requirements of the brief,
> green for the desirable *Places* feature, and grey for the additional features added beyond the
> brief.__
