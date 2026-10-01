# Diagrams

Source files for the two report diagrams. **Nothing here is part of the app build** — this folder sits
outside `app/src/`, so Gradle never compiles, packages or lints it. Editing or deleting these files
cannot affect the APK.

| File | Diagram |
|---|---|
| `app-navigation-map.drawio.xml` | App navigation map — the three screen layers (authentication, hub, features) |
| `auth-flowchart.drawio.xml` | Authentication flow — sign-in, Google, registration, 2FA, recovery codes, password reset |

## Opening them

Either way works at <https://app.diagrams.net>:

1. **File → Open From → Device**, and pick the `.drawio.xml` file. Recommended — it keeps the page
   name and lets you save back over the same file.
2. **Extras → Edit Diagram…**, then paste the file's contents over what's in the box and press OK.
   If it complains, make sure you pasted from `<mxGraphModel>` to `</mxGraphModel>` rather than the
   whole file.

## Keeping them honest

Both diagrams were drawn from the code, not from memory. If you change any of the following, the
diagrams need updating too:

- `app/src/main/res/navigation/nav_graph.xml` — every screen and action on the navigation map
- `app/src/main/java/com/prash/petcare/ui/common/FloatingNavBar.kt` — which five tabs the bar has
- `app/src/main/java/com/prash/petcare/ui/auth/AuthViewModel.kt` — the sign-in, Google and
  registration branches, and the lockout
- `app/src/main/java/com/prash/petcare/data/repository/TwoFactorRepository.kt` and
  `data/local/dao/RecoveryCodeDao.kt` — the three recovery-code outcomes and the single-use rule
- `app/src/main/java/com/prash/petcare/data/auth/FirebasePasswordResetRepository.kt` — the reset branch
