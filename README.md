# Pacer

A battery-first running app for a Google Pixel, for sideloading only. It tracks pace in miles (min/mi), runs interval workouts with voice and haptic cues, and keeps working with the screen off and the app swiped away. It costs $0 to build and run: GitHub Actions builds it, and maps come from OpenFreeMap (no key, no account).

- [Install on your phone](#install-on-your-phone)
- [First run](#first-run-permissions)
- [Repository secrets](#repository-secrets)
- [Uploading to Strava](#uploading-to-strava)
- [Battery](#battery)
- [Testing at your desk (mock location)](#testing-at-your-desk-mock-location)
- [Screen-off test](#screen-off-test)
- [Building](#building)

## Install on your phone

Every push to `main` builds a signed APK and attaches it to the release tagged **latest**, replacing the previous file.

1. On the Pixel, open **https://github.com/arkanemystic/custom-run-app/releases/tag/latest** in Chrome and tap **pacer.apk**.
2. The first time, Android asks to allow installs from Chrome: tap **Settings**, turn on **Allow from this source**, then go back.
3. Tap **Install**. If Play Protect warns about an unknown app, choose **Install anyway**.

Later builds install over the old one and keep your history, because every build is signed with the same key (see [Repository secrets](#repository-secrets)). **Don't uninstall Pacer to update it.** Uninstalling deletes your runs and presets.

## First run (permissions)

1. Open Pacer and tap **Allow location**. Choose **While using the app** and keep **Precise** on. Background location is not needed: the run is tracked by a foreground service you start yourself.
2. Tap **Start**. Android asks to allow notifications. Allow it so you get the ongoing run notification with Pause/Resume/Stop. The run still works if you deny it, but you won't see the notification.
3. If Battery Saver is on and would limit location with the screen off, Pacer warns you before the run starts.

> ⚠️ **Don't run with Extreme Battery Saver on.** It pauses apps that aren't on its allow list, including foreground services, so tracking and cues stop. Normal Battery Saver is OK (Pacer warns you about it), but location may be throttled with the screen off.

If Android ever kills a run (you'd see a gap or cues stop), exempt Pacer from battery optimization: **Settings > Apps > Pacer > App battery usage > Unrestricted**. It isn't requested by default because a foreground service normally doesn't need it. If the app process does get killed mid-run, reopening Pacer resumes the run from its last save (at most 30 s of track can be lost).

## Repository secrets

There is one set of secrets: the fixed signing key that lets each new APK install over the last one. Create it once, from a clone of this repo on any machine with `openssl` and the `gh` CLI (logged in):

```sh
./scripts/make-keystore.sh
```

This sets three repository secrets without printing them:

| Secret | What it is |
|---|---|
| `DEBUG_KEYSTORE_BASE64` | the PKCS12 keystore, base64-encoded |
| `DEBUG_KEYSTORE_PASSWORD` | its password |
| `DEBUG_KEY_ALIAS` | the key alias (`pacer`) |

The script also keeps the key in `~/.pacer-signing/`. **Back that folder up.** Running the script again re-uploads the same key, so you can restore the secrets later. If the key is lost, the next APK won't install over the old one.

Without these secrets CI still builds and tests, but it won't publish to the `latest` release.

## Uploading to Strava

Pacer doesn't use the Strava API, because creating an API application now costs money. Upload by hand instead:

1. On the run summary (after Stop, or from **History**), tap **Export GPX**. Android's save screen opens; pick **Downloads** and tap **Save**. (**Share GPX…** sends the file to an app instead.)
2. Tap **Upload to Strava**. This opens strava.com/upload/select. Sign in if asked, choose the file, and save.

The GPX file is GPX 1.1 with a timestamp and elevation on every point. Each pause starts a new track segment, so Strava doesn't count paused time as moving. The description summarizes the workout, splits and run-interval paces.

## Battery

Battery use is the top priority after pace accuracy. What Pacer does:

- **GPS only at full power while running.**
  - While running: high accuracy, 1 fix per second. Pace needs this.
  - While paused: balanced accuracy, every 10 s, delivered in batches.
  - On the home screen: balanced accuracy, every 5 s, only while that screen is visible.
  - After Stop: no location requests at all.
- **One partial wake lock, only while running.** It's released on pause and stop, has a 10-minute timeout as a safety net, and is renewed once a minute. It keeps interval changes and cues on time with the screen off.
- **One timer tick per second, inside the service**, scheduled for the next whole second of moving time. There are no polling loops in the UI. Screens use `collectAsStateWithLifecycle`, so nothing redraws while the screen is off or the app is in the background.
- **Notification updates are throttled.** It refreshes every second while the screen is on, every 15 s while it's off, and immediately on a cue.
- **Database writes are batched.** Track points are saved every 30 s and on pause/stop, not on every fix.
- **No network during a run**, except map tiles while the Map tab is open. There are no analytics. The map exists only while its tab is showing, its lifecycle follows the app's, and it doesn't start its own location request. The position dot comes from the run's own fixes. Tiles are kept in MapLibre's built-in disk cache, so revisiting an area doesn't refetch them.
- **True black dark theme**, so OLED pixels stay off. The screen is never forced on.
- **arm64-only APK** (only the code your Pixel needs).

**How to check:** after a run, open **Settings > Battery > Battery usage**, find **Pacer**, and look at foreground versus background use. Nearly all of it should be background, from GPS while the screen was off. For a 30-minute run, expect roughly what other GPS run trackers use. GPS dominates and can't be avoided at 1 Hz.

## Testing at your desk (mock location)

Every installed build is a debug build, so **Settings > Testing > Mock location** is available. It's off by default. With it on, the next run uses fake fixes instead of GPS:

- **Synthetic loop:** laps of a 400 m loop near your last known location, at the pace you set (±15 s steps).
- **GPX replay:** tap **Replay a GPX file instead…** and pick any GPX file. It's replayed at its own timing and loops at the end.

Turn it off again before a real run. The live screen looks normal, so check Settings if numbers look suspiciously steady.

## Screen-off test

I couldn't run Pacer on a device or emulator during development (no hardware virtualization in the build machine). Instead, the screen-off path is checked by a unit test, `RunSessionTest`. It drives the same `RunSession` the service uses with 1 Hz fixes, ticks scheduled the way the service schedules them (with timing jitter), and a 90 s pause. It asserts that every interval start and 3-2-1 countdown fires within 100 ms of schedule, that the track has no gaps over 1.1 s outside the pause, and that distance walked while paused is excluded.

To check it on the phone (about 35 minutes, at a desk):

1. **Settings > Testing:** turn on **Mock location**, set **Synthetic loop pace** to 10:00, and make sure Voice, Haptics and Coaching are on.
2. On the home screen select **Run/Walk 8x1:00**, connect headphones and play some music, then tap **Start**.
3. Press the power button to turn the screen off. Optionally swipe Pacer away in Recents first and then turn the screen off.
4. Listen. You should hear:
   - "Warm up, 5 minutes" at the start.
   - "3, 2, 1" then "Run, one minute, target ten minutes per mile" at 5:00, with three short buzzes.
   - "Walk, one minute 30 seconds, target sixteen minutes per mile" at 6:00, with one long buzz.
   - During walks, "Slow down. ten flat" about every 20 s after the first 15 s, because the mock runs at 10:00 and the walk target is 16:00. This also tests coaching.
   - The music dips while Pacer speaks and comes back right after.
5. Halfway through, wake the screen briefly, tap **Pause** in the notification, wait a minute, tap **Resume**, and turn the screen off again. Interval times should continue from where they stopped.
6. After "Workout complete" (~31 min), open Pacer, hold **Stop**, and on the summary check:
   - **GPS: N points, longest gap X s**: about 1 point per second of moving time, with the longest gap ≤ 2 s.
   - The interval list has all 18 intervals.

For a real-world check, do one actual run with the screen off and look at the same "longest gap" line.

## Building

Builds run in GitHub Actions (`.github/workflows/build.yml`) on every push: `./gradlew test`, then `./gradlew assembleDebug` on JDK 17. The APK is uploaded as a workflow artifact, and on `main` it's also published to the `latest` release.

To build locally you'd need JDK 17+ and the Android SDK (`./gradlew test assembleDebug`). It isn't required.

**Stack:**
- Kotlin, Jetpack Compose and Material 3, in a single module with manual dependency injection.
- Fused Location Provider for GPS.
- MapLibre Native with OpenFreeMap's `dark` style for the map.
- Room for runs, track points and presets; DataStore for settings.
- Android TextToSpeech for voice and Vibrator for haptics.

All run logic (GPS filtering, pace, mile splits, intervals, coaching, GPX) is plain Kotlin in `app/src/main/java/dev/pacer/core` and unit tested.

**Coaching note:** at walking pace, ±15 s/mi is only about 1.5% of speed, close to GPS speed noise. That's why walks have their own tolerance (default ±30 s/mi) and coaching compares a 20-second average rather than the live number.
