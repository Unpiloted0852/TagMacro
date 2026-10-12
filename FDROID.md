# Publishing on F-Droid

## Ready in this repository

- **Licence:** GPL-3.0-or-later (`LICENSE`), shown on the app's About card.
- **No proprietary code:** the app module has no dependencies beyond the Android framework and
  Kotlin's standard library; no Google Play Services, Firebase, analytics or ads.
- **No network access** (no INTERNET permission), so no tracking anti-feature.
- **No dependency-metadata block** in the APK (`dependenciesInfo` is off), which F-Droid rejects.
- **Unsigned release builds** when no signing key is configured, as F-Droid's build server needs.
- **Store listing** in `fastlane/metadata/android/en-US/`: title, short and full description,
  changelog for versionCode 1, 512 px icon, 1024 × 500 feature graphic.
- **Build recipe** in `fdroid/dev.tagmacro.yml` (categories from fdroiddata's current
  `config/categories.yml`; source, issues, changelog and donation links filled in).
- **CI** (`.github/workflows/build.yml`) builds every push, so a broken build shows up on GitHub
  before F-Droid tries it.

## Submitting

1. Push this repository to https://github.com/Unpiloted0852/TagMacro and tag the first release
   `v1.0` (the recipe's `commit:`).
2. On GitLab, fork https://gitlab.com/fdroid/fdroiddata, create a branch `dev.tagmacro`, add
   `fdroid/dev.tagmacro.yml` from here as `metadata/dev.tagmacro.yml`, and push.
3. The fork's CI runs `fdroid lint` and a test build. Fix anything it reports.
4. Open a merge request to fdroiddata titled "New app: TagMacro", using the "App inclusion"
   template, and answer reviewers' questions. Reviews can take days to weeks.

## Optional later

- **Screenshots:** add `fastlane/metadata/android/en-US/images/phoneScreenshots/1.png`, `2.png`…
  They're picked up from the next release.
- **Reproducible builds:** already enabled. The recipe has `Binaries:` and
  `AllowedAPKSigningKeys:`, so F-Droid ships your signed GitHub APK once its own build matches.
  Every release must stay reproducible, or F-Droid won't publish it.

## Anti-feature labels reviewers might add

F-Droid labels apps that "promote or depend entirely on" a proprietary network service or need a
non-free app. TagMacro works fully without Signal or WhatsApp, but its WhatsApp actions may lead
reviewers to add NonFreeNet or NonFreeDep. That doesn't block inclusion; it's shown on the app page.
