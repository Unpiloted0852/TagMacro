# Signing and GitHub releases

TagMacro is published in two places:

- **F-Droid** builds the app from source and signs it with F-Droid's own key.
- **GitHub releases** carry an APK signed with your key, built by the Release workflow.

The two signatures differ, so Android treats them as separate sources: someone who installed
the GitHub APK must uninstall before switching to F-Droid's build, and vice versa. (Once builds
are confirmed reproducible, F-Droid can ship your GitHub-signed APK instead; see FDROID.md.)

## One-time setup

```
bash scripts/make-signing-key.sh
```

This creates `~/keystores/tagmacro-release.jks` with a random password saved next to it in
`tagmacro-release.password` (never printed), writes a git-ignored `keystore.properties` for
local signed builds, and, if the GitHub CLI is installed and signed in, stores the four
secrets the Release workflow needs. Otherwise it tells you which four secrets to add by hand.

**Back up both files somewhere safe and offline.** Without them you can never publish an
update that installs over the existing app.

## Making a release

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.
3. Commit, then publish a GitHub release with a new tag like `v1.1`.
4. The Release workflow builds, signs and attaches `TagMacro-v1.1.apk`, a `SHA256SUMS.txt`,
   and adds the APK hash and signing-certificate fingerprint to the release notes.
5. F-Droid picks up the new tag automatically (UpdateCheckMode: Tags).

Local signed build instead: `./gradlew assembleRelease` with `keystore.properties` present.
