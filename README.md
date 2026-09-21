# IslandCheck

A single-screen Android app that tests whether a device actually renders
Android 16 **Live Updates** (promoted ongoing notifications) — and, on Samsung,
whether the notification reaches the **Now Bar** (即時通知).

It exists because `canPostPromotedNotifications()` returning `true` does **not**
mean anything appears on screen. The framework guarantees the OS will accept and
promote your notification; it says nothing about whether the OEM's SystemUI
draws a chip. IslandCheck reads back what the OS actually did, so the difference
is visible instead of guessed at.

---

## Headline finding

A sideloaded, non-privileged, platform-unsigned app **can** appear in Samsung's
Now Bar. The recipe is the opposite of what the reverse-engineering guides
suggest:

1. Post an ordinary **AOSP promoted notification** — `setOngoing(true)`,
   `setRequestPromotedOngoing(true)`, a non-empty content title, a promotable
   style, **not** colorized.
2. Add **exactly one** Samsung extra:
   `android.ongoingActivityNoti.automation = true`
   (plus `...automationPackage = <your package>`).
3. **Send nothing else.** On One UI 8.5 the decorative extras actively break it.
4. **Background the app** — an entry stays in `Pending` while its own app is
   foreground.

```kotlin
val builder = NotificationCompat.Builder(context, CHANNEL_ID)
    .setOngoing(true)
    .setRequestPromotedOngoing(true)
    .setContentTitle("…")
    .setStyle(NotificationCompat.ProgressStyle()…)

// Gate on the RUNTIME capability, not on SDK_INT and not on manufacturer.
// SDK 36 is NOT sufficient: One UI 8.0 is plain Android 16 and reports
// canPostPromotedNotifications() == false, while One UI 8.5 (Android 16 QPR2)
// reports true. Only the runtime call distinguishes them.
val canPromote = Build.VERSION.SDK_INT >= 36 &&
        NotificationManagerCompat.from(context).canPostPromotedNotifications()

if (canPromote && Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
    builder.addExtras(Bundle().apply {
        putBoolean("android.ongoingActivityNoti.automation", true)
        putString("android.ongoingActivityNoti.automationPackage", context.packageName)
    })
}
```

Delete the `if` block and you still have a correct Android 16 app. The OEM
block is additive, never load-bearing.

## Verified device matrix

| Vendor | OS | Android | Device | Plain AOSP alone | + Samsung `automation` |
| --- | --- | --- | --- | --- | --- |
| AOSP | stock | 17 / SDK 37 | emulator | **works** — status-bar chip | n/a |
| **OPPO** | **ColorOS 16.0.10** | 16 / SDK 36 | **Find X9** | **works — no vendor code needed** | n/a |
| Xiaomi | HyperOS 2 | 15 / SDK 35 | POCO C85 (25078PC3EG) | no island — app reports "requires Android 16+" | n/a |
| **Xiaomi** | **HyperOS 3.0 (OS3.0.302.0)** | 16 / SDK 36 | **POCO C85 (25078PC3EG)** | **works — no vendor code needed** | n/a |
| Samsung | One UI 7.0 | 15 / SDK 35 | Galaxy S25 (SM-S931N) | no chip | fails — not in any list |
| Samsung | One UI 8.0 | 16 / SDK 36 | Galaxy Z Flip 6 | no chip | fails — capability false |
| Samsung | One UI 8.5 | 16 QPR2 / SDK 36 | Galaxy A26 5G (SM-A266B) | no chip | **works** — Showing |
| Samsung | One UI 8.5 | 16 QPR2 / SDK 36 | Galaxy S26 Ultra | no chip | **works** |
| Samsung | One UI 9.0 | 17 / SDK 37 | Galaxy A07 (SM-A075F) | no chip | **works** — Showing |

(Samsung card lane, `style=1`: fails on 7.0 and 8.5, works on 9.0. It is never
needed — `automation` alone works everywhere `style=1` does, and more.)

### ColorOS 16 and HyperOS 3 need nothing

**OPPO Find X9 on ColorOS 16.0.10 renders the island from a plain AOSP promoted
notification, with zero vendor-specific code** — confirmed in production by
tigerduck-app-android before 2.1.0. No private extras, no allowlist, no
reflection.

**Xiaomi POCO C85 on HyperOS 3 (Android 16) does the same.** IslandCheck reports
SUPPORTED, and the claim holds: the plain AOSP promoted notification really does
render as Xiaomi's island. Again, no vendor code.

That is the important result for the whole project: the AOSP path is the
product, and the Samsung block is a workaround for one vendor that predates the
standard. ColorOS and HyperOS both render the standard as-is, so the
`if (samsung)` block stays the only vendor-specific code in the codebase.

Note the Find X9 also returns `false` from `canPostPromotedNotifications()`
*while rendering correctly* — see the section below. It is the reason that API
must never gate posting.

### HyperOS 2 has no island to reach

The same POCO C85 was tested on HyperOS 2 (Android 15) before its upgrade.
IslandCheck reports **Not supported — requires Android 16+**, and that is
accurate: nothing IslandCheck posts triggers an island. It is the same
structural failure as One UI 7.0. The Live Updates framework does not exist
below API 36, so there is nothing for the app to opt into. The same hardware
went from unsupported to working through the HyperOS 3 update, not through
anything an app can do.

**Don't be fooled by the Clock app on HyperOS 2.** A running timer, minimised,
shows a pill in the status bar that looks like the island. It is **not** the
surface this app tests. Android 15 has no Live Updates framework, so that pill
is a Xiaomi-proprietary surface driven by Xiaomi's own Clock app. A third-party
AOSP promoted notification cannot reach it. Seeing that pill on a HyperOS 2
device says nothing about whether your app's notification will render.

### What HyperOS 3 puts in the island

HyperOS does not use the AOSP status-bar chip. It draws Live Updates in its own
island and fills it by its own rules. These rules come from decompiling
HyperOS's island code on the POCO C85 (HyperOS 3.0.302) while building
tigerduck-app-android. They have not been re-derived with IslandCheck.

1. It **discards any Xiaomi-specific island parameters** the app sends and
   builds the layout itself from the standard notification fields.
2. The right-hand side shows the **first non-empty** of:
   `shortCriticalText` → `contentTitle` → `subText` → `contentText`.
3. It **never reads the countdown timer** (`when` + chronometer).

The AOSP chip, as on Pixel and Samsung, tries `shortCriticalText` first and then
falls back to the timer. The timer is the only thing that ticks without a
re-post. So the two diverge exactly where a countdown needs them to agree:

| Notification sets | AOSP / Samsung chip | HyperOS island |
| --- | --- | --- |
| `shortCriticalText` | that text | that text |
| no `shortCriticalText`, countdown chronometer | live countdown | **`contentTitle`** — timer ignored |

**The trap:** the usual countdown recipe leaves `shortCriticalText` empty on
purpose, so the chip falls through to the self-ticking chronometer. On HyperOS
the island falls through to the title instead. In tigerduck-app-android the
island showed the notification's title where the countdown should have been.

**The fix, on HyperOS 3+ only:** set `shortCriticalText` to the minutes left,
rounded up (`"47m"`, `"43 分鐘"`). HyperOS never redraws it by itself, so
**re-post the notification each time the minute changes**. Leave every other
OEM on the chronometer, which ticks for free.

IslandCheck always sets `shortCriticalText` (`"42%"`), so it never hits the
fallback.

**Detecting the version:** read `ro.mi.os.version.code`. It is `3` on HyperOS
3.0 (`ro.mi.os.version.name` = `OS3.0`). Treat anything below 3 as having no
island, which matches the HyperOS 2 result above. On HyperOS 3+,
`canPostPromotedNotifications()` is trustworthy. The decompile shows HyperOS
uses that same check to decide which notifications reach the island, so on
HyperOS the API is a real verdict. On ColorOS it is not (see below).

### Samsung needs One UI 8.5 or later

**The QPR level matters more than the API level.** One UI 8.5 is based on
Android 16 **QPR2**; One UI 8.0 is plain Android 16. On a Galaxy Z Flip 6 running
One UI 8.0 the app reports NOT SUPPORTED — `canPostPromotedNotifications()`
returns `false` despite `SDK_INT == 36`, because the platform gates Live Updates
behind an internal flag below QPR2. Nothing app-side can change that.

So any Android 16 Galaxy that never receives One UI 8.5 — the Galaxy S22 series,
Z Fold 4, Z Flip 4, and anything still on 8.0 — will never show a chip. **Never
branch on `SDK_INT` alone** — but do not gate *posting* on
`canPostPromotedNotifications()` either (see below).

Note also that the permission being *granted* is a different signal from the
capability being *available*: on One UI 8.0 `POST_PROMOTED_NOTIFICATIONS` is
granted and the capability is still false. The app reports both separately so
the two are not confused for a revoked permission.

## `canPostPromotedNotifications()` fails in both directions

It is a useful diagnostic and a bad gate:

| Device | API says | Actually renders? |
| --- | --- | --- |
| OPPO Find X9 / ColorOS 16.0.10 | `false` | **yes** — false negative |
| Samsung One UI 8.0 / Android 16 | `false` | no — true negative |
| Samsung One UI 8.5, One UI 9.0 | `true` | yes |
| Xiaomi POCO C85 / HyperOS 3 | `true` | yes — the island gates on this same check |

The API cannot tell those two `false` cases apart, so:

**Always post the notification.** If the OEM won't promote it, it degrades to an
ordinary ongoing notification and costs nothing. Gating on this API instead
silently removes the feature on devices that would have rendered it — which is
exactly what happened on the Find X9.

## Why it works

From decompiled One UI SystemUI (`jadx` on `/system/system_ext/priv-app/SystemUI/SystemUI.apk`):

```java
// NotificationEntry
isAutomation()   = extras.getBoolean("android.ongoingActivityNoti.automation");
mIsRon           = notification.hasPromotableCharacteristics();   // API 36+

isPromotedState() {
    if ((SettingsHelper.isDevelopRonTestAllowed()      // Settings.Secure
                                                       // enable_notification_nowbar_test = 1
         || isAutomation()
         || !AllowedOngoingActivityListManager.isAllowListUsing) && mIsRon) return true;
    return mIsPromoted;
}

isOngoingActivity() {
    if (extras.getInt("android.ongoingActivityNoti.style", 0) <= 0) return mIsRon;  // RON lane
    mIsRon = FALSE;                                                  // private-card lane
    return true;
}
```

`OngoingActivityDataHelper` only adds an entry to the **Showing** list when
`isPromotedState()` is true.

**The trap:** on One UI 8.5, `style >= 1` takes the private-card lane and sets
`mIsRon = false`, which cancels the `automation` bypass — the bypass requires
`mIsRon`. Every guide leads with `style = 1`, which is precisely what blocks the
working path. (One UI 9.0 relaxed this: both lanes reach Showing there.)

Samsung also ships a developer switch for the same bypass —
`Settings.Secure enable_notification_nowbar_test = 1` — which is useful for
testing but needs adb, so the `automation` extra is the shippable route.

## Why Google apps appear in 即時通知

Three independent channels, not one API:

| App | Channel | Evidence |
| --- | --- | --- |
| YouTube | **MediaSession** (public API); SystemUI renders the card itself | YouTube's APK contains **0** `ongoingActivityNoti` strings and `POST_NOTIFICATIONS: granted=false`, yet still shows |
| Google Maps | Samsung private extras | Maps' APK contains 3 `ongoingActivityNoti` strings |
| Google app stocks/weather | **SmartSpace** | `com.samsung.android.smartsuggestions/.extservice.services.CustomSmartspaceService`, `MANAGE_SMARTSPACE` granted |

`FACE_WIDGET` / `SERVICEBOX_REMOTEVIEWS` (both `signature|privileged`) gate
Samsung's **RemoteViews custom card** surface — they are *not* what gates
ordinary Showing-list entry.

## Build and run

Requires Android Studio's JDK (AGP rejects newer JDKs) and an Android 16+ device
or emulator.

```bash
./gradlew :app:assembleDebug

./debug/install.sh --samsung --home      # install + post via the automation lane
./debug/nowbar-probe.sh                  # read back what the OS did
```

> `gradle.properties` pins `org.gradle.java.home` to Android Studio's bundled
> JDK. That path is machine-specific — change or remove it on another machine.
> `compileSdk` is 37 rather than the 36 the app targets, because core-ktx 1.19.0
> and compose-bom 2026.09.00 publish `minCompileSdk 37`. `targetSdk` stays 36.

## `debug/`

| Script | Purpose |
| --- | --- |
| `install.sh` | Build → install → launch. `--plain` / `--samsung` / `--card` pick the lane; `--home` backgrounds the app |
| `nowbar-probe.sh` | Reads notification flags, Samsung Showing/Hidden/Pending, AOSP surfaces, then prints a verdict. `--watch N` to follow |
| `build-apk.sh` | Stages a traceable APK in `debug/apk/` for Firebase Test Lab |
| `rtl.sh` | Drives a Samsung Remote Test Lab device over RDB, reconnecting when the tunnel drops |
| `_lib.sh` | Shared helpers — device picker, install retry, timeouts |

The test modes are driven by intent extras, so a run is reproducible without
tapping screen coordinates:

```bash
adb shell am start -n com.test.island.dynamic.android/.MainActivity --ez auto_samsung true
```

## Caveats

- `android.ongoingActivityNoti.automation` is **undocumented** and appears to be
  intended for Samsung's own automation tooling. It survived One UI 8.5 → 9.0,
  but it can disappear in any update.
- Setting Bundle extras is *not* a hidden-API call, so it is not subject to
  Android's hidden-API blocklist. Reflecting on
  `android.app.Notification$OngoingActivityStyle` would be — this app uses that
  class only as a presence probe.
- `AllowedOngoingActivityListManager` reads `allowedList`, `isAllowListUsing`
  and `blockedRONAppList` from `INotificationManager`, i.e. framework-supplied.
  A package on `blockedRONAppList` is rejected before `mIsRon` is even assigned,
  so Samsung retains a kill switch.
- `isNowBarVisible` is **not** a per-app signal — it reads `true` at a clean
  baseline with nothing posted. Use Showing-list membership.
- Findings are from specific devices on specific builds. Re-verify after OS
  updates; the app is the tool for doing that.

## Not affiliated with Samsung

This is a diagnostic harness built by reading publicly readable device state
(`dumpsys`, `getprop`) and decompiling on-device system APKs for
interoperability. It ships no Samsung code.
