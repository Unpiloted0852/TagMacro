# TagMacro

[![Build](https://github.com/Unpiloted0852/TagMacro/actions/workflows/build.yml/badge.svg)](https://github.com/Unpiloted0852/TagMacro/actions/workflows/build.yml)

An NFC automation app for Android with Signal message sending, built to be auditable:
no internet permission, no third-party libraries, and no AndroidX. Everything uses the
Android framework and the Kotlin standard library.

## What it does

**Macros** (like MacroDroid / NFC Tasks)
- Triggers: a tag written by the app (stores a random 128-bit code), any tag or card
  matched by serial number, or manual (Run button, home screen shortcut, another macro).
- Conditions: ask before running, cooldown, time-of-day window (overnight works), days of the week,
  stop at first failure.
- Messaging and calls: send an SMS (straight away, or opened ready to send), call a number (or
  open it in the dialer), send a Signal message (by chat name), send a WhatsApp message (by phone
  number), start sharing WhatsApp live location for 15 minutes, 1 hour or 8 hours.
- Directions to an address or coordinates, in Google Maps or any maps app.
- Journal: a "Record it in the journal" action notes when something happened (fed the cat, took
  a tablet, arrived at work). The Log tab's Journal view shows the last time for each, all
  entries, and exports CSV. The journal is included in backups.
- Actions: wait, open an app, open a link (web, tel:, geo:…), flashlight,
  vibrate, on-screen message, speak text aloud, copy to clipboard, set an alarm, start a timer,
  Do Not Disturb, run another macro.
- Device settings: screen brightness (level or adaptive), screen timeout, auto-rotate, ringer mode
  (sound / vibrate / silent), volume for media, ringtone, notifications, alarms or calls, media
  play/pause/next/previous, lock the screen, and quick panels for Wi-Fi, internet, NFC and volume.
- Not possible for any non-system app on current Android: switching Wi-Fi (blocked since
  Android 10), Bluetooth (blocked since Android 13), mobile data, airplane mode or location.
  The settings-panel action opens the control for you to tap instead.
- Text placeholders, inserted with one tap: `{time}`, `{date}`, `{location}` (a map link to where
  you are when the macro runs), `{battery}`, `{tag}` (serial number), `{macro}`.
- Templates for new macros (quiet time, bedtime, home safe, share live location, "I'm here",
  in the car, kitchen timer). Anything left to fill in is marked "Needs setup", and a macro can't
  be saved until it's complete.
- Contact picker for phone numbers and Signal chat names; local numbers are converted to
  international format using your SIM's country. Time picker for alarms. Searchable app picker.
- Copy actions, duplicate macros (the copy gets its own tag code), and a confirmation before a
  test run sends real messages or shares a real location.
- Home screen shortcuts, test runs, a run log (last 300), JSON backup export/import.

**Tag tools** (like NFC Tools)
- Read: serial number, chip family, technologies, capacity, used bytes, lock state, every record,
  and which macros the tag runs.
- Write several records at once: link, Wi-Fi network, contact card, map location, text, phone,
  SMS, email, open an app, run a TagMacro macro. Shows the size and which sticker size it fits.
- Wi-Fi tags use the Wi-Fi Alliance format (`application/vnd.wfa.wsc`). Android phones offer to
  join; iPhones can't join from Wi-Fi tags.
- Map location: "Use my current location", or paste a Google Maps / Apple Maps link or
  "lat, lon". Short share links (maps.app.goo.gl/…) can't be read, because expanding them needs
  the internet; open the link in a browser first and copy the full address.
- Erase, format, and permanently lock (requires typing LOCK).
- A tag you've just read or written in the app never runs its old macro or the new one: the app
  asks Android (`NfcAdapter.ignore`) not to deliver it again until it has been lifted away for a
  second, and the trigger screen also skips it until then. By default, tag taps are also ignored
  entirely while TagMacro is open (Settings → Tags); use Test run instead.

## Build it

1. Unzip, then in Android Studio choose **File → Open** and select the `TagMacro` folder
   (the one containing `settings.gradle.kts`). Don't open the `app` folder or a parent folder.
2. Let Gradle sync. The first sync downloads Gradle 8.13, the Android Gradle plugin 8.13.2 and
   Kotlin 2.2.20, and may prompt you to install Android SDK Platform 36.
3. If Studio offers to upgrade the Android Gradle plugin, you can accept or decline; either works.
4. Connect your phone with USB debugging on and press **Run**, or use
   **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.

Build setup: `settings.gradle.kts`, `build.gradle.kts`, `app/build.gradle.kts`.
There is no `dependencies` block, so nothing third-party is compiled into the app.
Gradle 8.14.6, Android Gradle plugin 8.13.2, Kotlin 2.2.20. The wrapper files come from
Gradle's official v8.14.6 release.

### If Gradle sync fails

Build from a terminal first. It bypasses Android Studio's sync layer and prints the real error:

```
cd TagMacro
./gradlew assembleDebug --stacktrace        # macOS / Linux
gradlew.bat assembleDebug --stacktrace      # Windows
```

"Cannot use connection to Gradle distribution … as it has been stopped" is an Android Studio
sync error, not a problem in the build files. It means the connection to Gradle was already shut
down, usually after an interrupted download or a stopped sync. In order:

1. **File → Sync Project with Gradle Files** again. It's often transient.
2. Stop stale daemons: `./gradlew --stop`, then restart Android Studio.
3. Delete the half-downloaded distribution and let it fetch again:
   - macOS / Linux: `rm -rf ~/.gradle/wrapper/dists/gradle-8.14.6-bin`
   - Windows: delete `%USERPROFILE%\.gradle\wrapper\dists\gradle-8.14.6-bin`
4. **File → Invalidate Caches… → Invalidate and Restart**.
5. Check the JDK: **Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK**.
   Gradle 8.14.6 runs on JDK 17 through 24. If yours is newer, pick the bundled JDK or JDK 17.

## First-time setup on the phone

1. Open TagMacro → Settings → **Open accessibility settings** → turn on
   "TagMacro Signal sender". Only needed for Signal actions.
   If Android says it's a *restricted setting*: Settings tab → **Open App info** → ⋮ menu →
   **Allow restricted settings**, then try again.
2. Grant the other permissions from the Settings tab only if you use the actions that need them:
   - Do Not Disturb access: Do Not Disturb, ringer to or from Silent, ringtone/notification volume 0.
   - Modify system settings: brightness, screen timeout, auto-rotate.
   - Display over other apps: macros that open another app more than once.
   - The accessibility service also enables Lock the screen.

## Test it safely

1. New macro → name it "Test" → trigger "NFC tag written by this app".
2. Add action **Send a Signal message**, chat name `Note to Self`, message `Test at {time}`.
3. Save → **Write trigger to a tag** → hold a sticker to the phone.
4. Lock the phone, unlock it, go to the home screen, and tap the sticker.
5. Check the Log tab. If it failed, it names the step it got stuck on.

If sending stalls, go to Settings → **Capture the next Signal screen**, open a Signal chat,
come back, and look for the send button's `desc="…"` or `id=…`. Put that in
"Send button labels" or "Send button view IDs".

## WhatsApp

- Chats are opened with WhatsApp's click-to-chat link (`https://wa.me/<number>?text=…`), which
  pre-fills the message. TagMacro only presses send once it sees its own text in the box, which
  proves the right chat opened.
- Live location opens the chat with a random marker in the text box, removes it once seen, then
  goes Attach → Location → Share live location → duration → optional comment → Send, and finally
  checks the chat shows "Stop sharing". The log names the step if any of these fails.
- Grant WhatsApp location permission once by hand first. The service only works inside WhatsApp and
  never clicks on system prompts, so it waits and eventually stops if one appears.
- WhatsApp's labels are editable under Settings → WhatsApp for non-English phones or after a
  WhatsApp redesign. Use the capture tool to see what the current screens call things.
- WhatsApp's Terms of Service forbid auto-messaging. Each send here starts with you tapping a tag,
  but the terms don't make an exception for that, so there's some risk to your account.

Test safely: make a macro that shares live location for 15 minutes with your own number or a
willing friend, run it with the phone unlocked, and read the Log tab.

## Download, support, licence

- Signed APKs: [GitHub releases](https://github.com/Unpiloted0852/TagMacro/releases).
  F-Droid: see `FDROID.md` for the submission status.
- Support development: [ko-fi.com/unpiloted0852](https://ko-fi.com/unpiloted0852)
- Licence: GPL-3.0-or-later (`LICENSE`).
- Releasing and signing: `SIGNING.md`.

## Auditing checklist

- `AndroidManifest.xml`: the full permission list is NFC, VIBRATE, ACCESS_NOTIFICATION_POLICY,
  SET_ALARM, WRITE_SETTINGS, SYSTEM_ALERT_WINDOW, location (fine and coarse), SEND_SMS and
  CALL_PHONE. The last four are asked for only when you use a feature that needs them. No INTERNET, so
  Android blocks all network access. Location is requested only when you use a location feature,
  and read only through Android's own LocationManager (`Locator.kt`), with no Google libraries. WRITE_SETTINGS is optional and only used for brightness, timeout and auto-rotate.
  SYSTEM_ALERT_WINDOW is optional and inactive until you grant it; the app never draws an overlay.
  Search the code for `TYPE_APPLICATION_OVERLAY` or `WindowManager` and you'll find no uses.
- `res/xml/accessibility_config.xml`: events are limited to `org.thoughtcrime.securesms` and
  `com.whatsapp` (or `com.whatsapp.w4b` if you pick WhatsApp Business in Settings).
- `SignalService.kt`: the only code that reads the screen. It returns immediately unless a
  send job or a one-off capture is active, ignores any window that isn't Signal or WhatsApp, and
  waits rather than acting when another window (such as a system prompt) is in front.
- `Store.kt`: all storage is in the app's private files directory; `allowBackup="false"`.
- `app/build.gradle.kts`: has no dependencies block, so nothing third-party ships in the app.

| File | Role |
|---|---|
| `Model.kt` | Macro, action, settings and log data, JSON conversion |
| `Store.kt` | Private file storage, backup, enabling the serial-number listener |
| `Specs.kt` | Action types, their fields and validation |
| `Runner.kt` | Runs macros: conditions, placeholders, each action |
| `SignalService.kt` | Accessibility service that operates Signal |
| `NfcTools.kt` | Reading, writing, erasing, formatting and locking tags |
| `TriggerActivity.kt` | Invisible screen that receives tag taps and shortcuts |
| `MainActivity.kt`, `Ui.kt` | The app's screens |

## Known limits

- Signal automation depends on Signal's screen layout. A Signal update can break it; the capture
  tool and the label/ID settings are there to fix that without code changes.
- Setting the message replaces any unsent draft in that chat.
- The phone must be unlocked for Android to read tags, and Signal actions need the screen on.
- Serial numbers can be read by anyone and some tags can be cloned. Use app-written tags for
  anything sensitive.
- Since Android 10, an app with no visible window can't open another app, and the block is silent:
  the call returns normally and only Logcat records it. A macro that opens another app more than
  once (several alarms, several timers) therefore needs **Display over other apps**, granted from
  the Settings tab. An enabled accessibility service also counts as an exemption. TagMacro keeps its
  invisible trigger screen up for the whole run and pauses between launches, which covers many
  cases on its own, but the permission is what makes it reliable.
- Macros run one at a time; a long Wait delays any macro triggered after it.
