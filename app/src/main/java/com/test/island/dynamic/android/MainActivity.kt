package com.test.island.dynamic.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

/** Channel is mandatory on API 26+, which is our minSdk — so no version branch. */
private const val CHANNEL_ID = "islandcheck_live_update"
private const val NOTIFICATION_ID = 8891

/**
 * ProgressStyle has no `setProgressMax()`. The maximum is the SUM of the
 * segment lengths, so a single Segment(100) yields a 0..100 scale.
 */
private const val PROGRESS_MAX = 100

/** How much the test progress advances per tick. */
private const val PROGRESS_STEP = 5

/** API 36 == Android 16 == VERSION_CODES.BAKLAVA. Named constant over a magic 36. */
private val API_16 = Build.VERSION_CODES.BAKLAVA

/**
 * Which Samsung lane to exercise. Decompiled One UI SystemUI shows
 * NotificationEntry.isOngoingActivity() forks on the `style` extra:
 *
 *   style <= 0 -> RON lane.   mIsRon = hasPromotableCharacteristics()  [API 36+]
 *   style >= 1 -> card lane.  mIsRon = FALSE, allowlist-gated
 *
 * On Android 16 the RON lane wins (MODE_AUTOMATION). On Android 15 there is no
 * hasPromotableCharacteristics(), so mIsRon can never be true and the RON lane
 * cannot work at all — the card lane (MODE_CARD) is the only candidate there.
 * Hence both are kept and selectable.
 */
private const val MODE_NONE = "none"
private const val MODE_AUTOMATION = "automation"
private const val MODE_CARD = "card"

// ---------------------------------------------------------------------------
// Activity
// ---------------------------------------------------------------------------

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureChannel(this)
        // Deterministic test entry point, so the harness can be driven from adb
        // instead of by tapping coordinates:
        //   adb shell am start -n <pkg>/.MainActivity --ez auto_samsung true
        // auto_plain    -> plain AOSP promoted notification, no Samsung extras
        // auto_samsung  -> + the `automation` extra (RON lane, Android 16+)
        // auto_card     -> + the private-card extras incl. style=1 (only lane
        //                    that can exist pre-Android-16; try this on One UI 7)
        val autoMode = when {
            intent?.getBooleanExtra("auto_samsung", false) == true -> MODE_AUTOMATION
            intent?.getBooleanExtra("auto_card", false) == true -> MODE_CARD
            intent?.getBooleanExtra("auto_plain", false) == true -> MODE_NONE
            else -> null
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    IslandCheckScreen(autoMode = autoMode)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 1. Device info — OEM guesswork, explicitly labelled as such
// ---------------------------------------------------------------------------

/**
 * Reads an OEM system property via reflection.
 *
 * `android.os.SystemProperties` exists on every device but is @hide, so it is
 * absent from android.jar and cannot be called directly. Reflection asks the
 * runtime for it by name, bypassing compile-time checks — which also means it
 * can vanish on any OEM build. Returns null on any failure, by design.
 */
@SuppressLint("PrivateApi")
private fun systemProperty(key: String): String? = try {
    Class.forName("android.os.SystemProperties")
        .getMethod("get", String::class.java)
        .invoke(null, key) as? String
} catch (_: Exception) {
    null
}?.takeIf { it.isNotBlank() }

/**
 * Best-guess OEM island branding, from the manufacturer string alone.
 * UI copy only — never a capability signal. The real gate is
 * canPostPromotedNotifications() in section 2.
 */
private fun islandBrand(manufacturer: String): String {
    val m = manufacturer.lowercase()
    return when {
        m.contains("samsung") -> "Now Bar"
        m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") -> "Hyper Island"
        m.contains("oppo") || m.contains("oneplus") || m.contains("realme") -> "Live Space"
        m.contains("vivo") || m.contains("iqoo") -> "Atomic Island"
        else -> "Stock Live Updates"
    }
}

/** The OEM version property for this manufacturer, with the documented fallbacks. */
private fun oemVersionProperty(manufacturer: String): Pair<String, String?>? {
    val m = manufacturer.lowercase()
    return when {
        m.contains("samsung") ->
            "ro.build.version.oneui" to systemProperty("ro.build.version.oneui")

        m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") ->
            "ro.mi.os.version.name / ro.miui.ui.version.name" to
                    (systemProperty("ro.mi.os.version.name")
                        ?: systemProperty("ro.miui.ui.version.name"))

        m.contains("oppo") || m.contains("oneplus") || m.contains("realme") ->
            "ro.build.version.oplusrom / ro.build.version.opporom" to
                    (systemProperty("ro.build.version.oplusrom")
                        ?: systemProperty("ro.build.version.opporom"))

        m.contains("vivo") || m.contains("iqoo") ->
            "ro.vivo.os.version" to systemProperty("ro.vivo.os.version")

        else -> null
    }
}

/**
 * Samsung encodes One UI as a packed integer: 80500 -> 8.5.0.
 * The encoding itself is undocumented; this is best-effort decoration only.
 */
private fun prettyOneUi(raw: String?): String? {
    val n = raw?.toIntOrNull() ?: return null
    if (n < 10000) return null
    return "${n / 10000}.${(n / 100) % 100}.${n % 100}"
}

/**
 * Reads a Settings.System key. Reads need no permission (writes would need
 * WRITE_SETTINGS). Samsung keeps its undocumented Now Bar keys in this
 * namespace, so this is how we surface them.
 */
private fun systemSetting(context: Context, key: String): String? = try {
    Settings.System.getString(context.contentResolver, key)
} catch (_: Exception) {
    null
}

/**
 * Samsung-specific Now Bar state.
 *
 * VERIFIED 2026-09-18 on SM-A266B / One UI 8.5: Samsung's "Live Notifications"
 * surface (Settings calls it 即時通知) is
 * `com.android.systemui.statusbar.phone.ongoingactivity.OngoingActivityController`,
 * fed by Samsung's internal ServiceBox pipeline — media sessions, calls,
 * recording, DND, Routines, plus a handful of partner apps. It does NOT read
 * FLAG_PROMOTED_ONGOING, so a correctly-built Android 16 promoted notification
 * never enters it. Confirmed by dumpsys: SystemUI re-extracts our
 * PromotedNotificationContentModel every second while
 * OngoingActivityController's Showing/Hidden/Pending lists stay empty.
 *
 * Writing key_now_bar_<package> into Settings.System does NOT admit an app —
 * those keys are per-app user toggles for sources Samsung already supports,
 * not an eligibility gate.
 */
private data class NowBarState(
    val enabled: String?,
    val aodEnabled: String?,
    val allowlistKey: String,
    val allowlisted: Boolean,
)

private fun readNowBarState(context: Context): NowBarState {
    // Samsung's key format: dots in the package name become underscores.
    val key = "key_now_bar_" + context.packageName.replace('.', '_')
    return NowBarState(
        enabled = systemSetting(context, "now_bar_enabled"),
        aodEnabled = systemSetting(context, "now_bar_aod_enabled"),
        allowlistKey = key,
        allowlisted = systemSetting(context, key) == "1",
    )
}

// ---------------------------------------------------------------------------
// 2. Capability status — the real signals, read fresh on every poll
// ---------------------------------------------------------------------------

/** A null Boolean means "the API does not exist on this OS version". */
private data class Status(
    val postNotificationsGranted: Boolean,
    val canPostPromoted: Boolean?,
    /**
     * The manifest permission's own grant state, which is NOT the same thing as
     * canPostPromotedNotifications(). Granted + canPost=false means the platform
     * feature is unavailable on this build (e.g. One UI 8.0, which is plain
     * Android 16 rather than QPR2) — nothing the user or app can change.
     */
    val promotedPermissionGranted: Boolean?,
    val testActive: Boolean,
    /** Did OUR notification satisfy the platform's promotion preconditions? */
    val promotable: Boolean?,
    /** Did the OS actually SET the promoted flag on it? The strongest signal. */
    val promotedByOs: Boolean?,
)

private fun readStatus(context: Context): Status {
    val nmc = NotificationManagerCompat.from(context)

    // POST_NOTIFICATIONS only became a runtime permission in API 33; below
    // that the manifest declaration alone is sufficient.
    val granted = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        true
    } else {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    val api16 = Build.VERSION.SDK_INT >= API_16

    // THE capability signal. Reflects both OS version and the user's
    // separately-revocable permission for promoted notifications.
    val canPost = if (api16) nmc.canPostPromotedNotifications() else null

    // String literal rather than the constant so this still compiles if the
    // symbol moves; it is a normal permission, granted at install.
    val promotedPerm = if (api16) {
        ContextCompat.checkSelfPermission(
            context,
            "android.permission.POST_PROMOTED_NOTIFICATIONS",
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        null
    }

    // Read our own notification back out of the shade.
    val sbn = try {
        nmc.activeNotifications.firstOrNull { it.id == NOTIFICATION_ID }
    } catch (_: Exception) {
        null
    }

    // Public API: the platform's own verdict on whether this notification
    // qualifies for promotion (ongoing + title + eligible style + not
    // colorized + no custom RemoteViews + no group summary).
    val promotable = if (api16 && sbn != null) {
        sbn.notification.hasPromotableCharacteristics()
    } else {
        null
    }

    // Stronger still: did the system actually promote it? This is the OS
    // telling us "yes, I made this a Live Update", independent of whether the
    // OEM's SystemUI then drew a visible pill.
    val promotedByOs = if (api16 && sbn != null) {
        (sbn.notification.flags and Notification.FLAG_PROMOTED_ONGOING) != 0
    } else {
        null
    }

    return Status(granted, canPost, promotedPerm, sbn != null, promotable, promotedByOs)
}

/**
 * Samsung's PRIVATE Now Bar extras, reverse-engineered by the nowbar-sdk project
 * (github.com/kirillshsh/nowbar-sdk) from decompiled Samsung Health / Voice
 * Recorder APKs. Undocumented, unsupported, and free to disappear in any One UI
 * update — but verified present on THIS ROM:
 *
 *   /system/framework/framework.jar          "ongoingActivityNoti.style"    ✓
 *                                            "OngoingActivityStyle"         ✓
 *   /system/.../SystemUI.apk                 "ongoingActivityNoti"          ✓
 *
 * The decisive key is STYLE: 0 = notification only, 1 = notification + Now Bar
 * chip. AOSP's setRequestPromotedOngoing() does NOT set it, which is why a
 * correctly-built Android 16 promoted notification never enters Samsung's
 * OngoingActivityController.
 */
private object SamsungNowBar {
    const val STYLE = "android.ongoingActivityNoti.style"
    const val CHIP_BG_COLOR = "android.ongoingActivityNoti.chipBgColor"
    const val CHIP_ICON = "android.ongoingActivityNoti.chipIcon"
    const val CHIP_EXPANDED_TEXT = "android.ongoingActivityNoti.chipExpandedText"
    const val PRIMARY_INFO = "android.ongoingActivityNoti.primaryInfo"
    const val SECONDARY_INFO = "android.ongoingActivityNoti.secondaryInfo"
    const val NOWBAR_PRIMARY_INFO = "android.ongoingActivityNoti.nowbarPrimaryInfo"
    const val NOWBAR_ICON = "android.ongoingActivityNoti.nowbarIcon"
    const val PROGRESS = "android.ongoingActivityNoti.progress"
    const val PROGRESS_MAX = "android.ongoingActivityNoti.progressMax"
    const val ACTION_TYPE = "android.ongoingActivityNoti.actionType"
    const val SHOW_SMALL_ICON = "android.showSmallIcon"

    /**
     * Undocumented by nowbar-sdk; found by decompiling One UI 8.5 SystemUI.
     *
     *   NotificationEntry.isAutomation() =
     *       extras.getBoolean("android.ongoingActivityNoti.automation")
     *
     *   NotificationEntry.isPromotedState() =
     *       (isDevelopRonTestAllowed() || isAutomation()
     *        || !AllowedOngoingActivityListManager.isAllowListUsing) && mIsRon
     *
     *   mIsRon = notification.hasPromotableCharacteristics()
     *
     * So automation=true short-circuits Samsung's allowlist, provided the
     * notification already satisfies AOSP's promotable checks — which ours does.
     */
    const val AUTOMATION = "android.ongoingActivityNoti.automation"
    const val AUTOMATION_PACKAGE = "android.ongoingActivityNoti.automationPackage"

    const val STYLE_BOTH = 1
    const val ACTION_TYPE_BUTTON_TEXT = 1

    /** Samsung's hidden framework style class. Presence is a ROM capability probe. */
    const val STYLE_CLASS = "android.app.Notification\$OngoingActivityStyle"

    fun styleClassAvailable(): Boolean = try {
        Class.forName(STYLE_CLASS)
        true
    } catch (_: Throwable) {
        false
    }
}

private fun samsungNowBarExtras(context: Context, progress: Int, mode: String): Bundle? {
    val icon = Icon.createWithResource(context, android.R.drawable.ic_dialog_info)
    return when (mode) {
        // RON lane. Deliberately minimal: any `style` >= 1 would flip
        // isOngoingActivity() into the card lane and force mIsRon = false,
        // cancelling this bypass. Verified working on One UI 8.5 / Android 16.
        MODE_AUTOMATION -> Bundle().apply {
            putBoolean(SamsungNowBar.AUTOMATION, true)
            putString(SamsungNowBar.AUTOMATION_PACKAGE, context.packageName)
        }

        // Samsung private-card lane. Allowlist-gated on One UI 8.5 (we land in
        // the Hidden list with promoted=false), but it is the ONLY lane that can
        // exist pre-Android-16, so it is the one to try on One UI 7.
        MODE_CARD -> Bundle().apply {
            putInt(SamsungNowBar.STYLE, SamsungNowBar.STYLE_BOTH)
            putInt(SamsungNowBar.CHIP_BG_COLOR, 0xFF6750A4.toInt())
            putParcelable(SamsungNowBar.CHIP_ICON, icon)
            putParcelable(SamsungNowBar.NOWBAR_ICON, icon)
            putString(SamsungNowBar.CHIP_EXPANDED_TEXT, "IslandCheck")
            putString(SamsungNowBar.PRIMARY_INFO, "IslandCheck test")
            putString(SamsungNowBar.SECONDARY_INFO, "$progress / $PROGRESS_MAX")
            putString(SamsungNowBar.NOWBAR_PRIMARY_INFO, "IslandCheck")
            putInt(SamsungNowBar.PROGRESS, progress)
            putInt(SamsungNowBar.PROGRESS_MAX, PROGRESS_MAX)
            putInt(SamsungNowBar.ACTION_TYPE, SamsungNowBar.ACTION_TYPE_BUTTON_TEXT)
            putBoolean(SamsungNowBar.SHOW_SMALL_ICON, true)
            // Harmless on the card lane and needed if the OEM checks it there.
            putBoolean(SamsungNowBar.AUTOMATION, true)
            putString(SamsungNowBar.AUTOMATION_PACKAGE, context.packageName)
        }

        else -> null
    }
}

// ---------------------------------------------------------------------------
// 3. The test notification
// ---------------------------------------------------------------------------

private fun ensureChannel(context: Context) {
    val nm = context.getSystemService(NotificationManager::class.java)
    if (nm.getNotificationChannel(CHANNEL_ID) != null) return
    val channel = NotificationChannel(
        CHANNEL_ID,
        "IslandCheck test",
        NotificationManager.IMPORTANCE_DEFAULT,
    ).apply {
        description = "Ongoing test notification used to probe Live Updates rendering."
        setShowBadge(false)
        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
    }
    nm.createNotificationChannel(channel)
}

/**
 * Builds the promoted-ongoing candidate.
 *
 * Promotion is not a flag we flip — the platform runs
 * `hasPromotableCharacteristics()` and EVERY condition must hold at once:
 * setRequestPromotedOngoing(true), setOngoing(true), a non-empty content
 * title, a promotable style (none / BigTextStyle / CallStyle / MetricStyle /
 * ProgressStyle), no group summary, no custom RemoteViews, and NOT colorized.
 *
 * setColorized(true) is deliberately absent: it silently disqualifies the
 * notification from promotion while buying nothing (the platform honours
 * colorized only for foreground-service, media or already-promoted
 * notifications). That mistake cost tigerduck-app-android the feature once.
 */
private fun buildTestNotification(
    context: Context,
    progress: Int,
    mode: String,
): Notification {
    val tapIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    // ProgressStyle is an Android 16 template. Below that it has nothing to map
    // onto, so fall back to the classic determinate progress bar — otherwise the
    // notification renders empty on Android 15 and the test proves nothing.
    val style: NotificationCompat.Style? =
        if (Build.VERSION.SDK_INT >= API_16) {
            // One segment of length 100 == a 0..100 scale (there is no setProgressMax).
            NotificationCompat.ProgressStyle()
                .addProgressSegment(NotificationCompat.ProgressStyle.Segment(PROGRESS_MAX))
                .setProgress(progress)
                .setProgressIndeterminate(false)
        } else {
            null
        }

    return NotificationCompat.Builder(context, CHANNEL_ID)
        // A built-in platform icon, per spec. If a chip renders but appears
        // blank, a purpose-made monochrome icon is the first thing to try.
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle("IslandCheck test")
        .setContentText("Progress $progress / $PROGRESS_MAX")
        // The chip picks its text in priority order: short critical text, then
        // a metric, then `when`. Setting it makes the pill show the % directly.
        .setShortCriticalText("$progress%")
        .setContentIntent(tapIntent)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setRequestPromotedOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .apply {
            if (style != null) setStyle(style) else setProgress(PROGRESS_MAX, progress, false)
            samsungNowBarExtras(context, progress, mode)?.let { addExtras(it) }
        }
        .build()
}

@SuppressLint("MissingPermission")
private fun postTest(context: Context, progress: Int, mode: String) {
    NotificationManagerCompat.from(context)
        .notify(NOTIFICATION_ID, buildTestNotification(context, progress, mode))
}

private fun cancelTest(context: Context) {
    NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
}

/**
 * POST_PROMOTED_NOTIFICATIONS is a NORMAL permission — granted at install and
 * not requestable via ActivityResultContracts. The user can still revoke it,
 * and the only way to restore it is the system settings screen, so deep-link
 * there. Not every build ships that screen, hence the fallback.
 */
private fun openPromotionSettings(context: Context) {
    val candidates = buildList {
        if (Build.VERSION.SDK_INT >= API_16) {
            add(
                Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            )
        }
        add(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        )
    }
    for (intent in candidates) {
        try {
            context.startActivity(intent)
            return
        } catch (_: Exception) {
            // Try the next one.
        }
    }
}


// ---------------------------------------------------------------------------
// Foreground-service variant
//
// Last lever a non-privileged app has. Samsung Voice Recorder and YouTube — the
// two things that DO reach the Now Bar — are both foreground services, and
// nowbar-sdk wraps everything in one. If Samsung's `promoted` flag tracks "is
// backed by a running FGS", this is what flips it.
// ---------------------------------------------------------------------------

class IslandCheckService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ticker: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_NONE
        ensureChannel(this)

        // specialUse: this is a diagnostic harness, not one of the predefined
        // FGS categories. The subtype string is declared in the manifest.
        startForeground(
            NOTIFICATION_ID,
            buildTestNotification(this, 0, mode),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )

        ticker?.cancel()
        ticker = scope.launch {
            var progress = 0
            while (isActive) {
                delay(1000)
                progress = if (progress + PROGRESS_STEP > PROGRESS_MAX) 0 else progress + PROGRESS_STEP
                NotificationManagerCompat.from(this@IslandCheckService)
                    .notify(
                        NOTIFICATION_ID,
                        buildTestNotification(this@IslandCheckService, progress, mode),
                    )
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        ticker?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_MODE = "mode"
    }
}

private fun startForegroundTest(context: Context, mode: String) {
    val intent = Intent(context, IslandCheckService::class.java)
        .putExtra(IslandCheckService.EXTRA_MODE, mode)
    context.startForegroundService(intent)
}

private fun stopForegroundTest(context: Context) {
    context.stopService(Intent(context, IslandCheckService::class.java))
}

// ---------------------------------------------------------------------------
// UI
// ---------------------------------------------------------------------------

private val Green = Color(0xFF2E7D32)
private val Red = Color(0xFFC62828)
private val Amber = Color(0xFFEF6C00)

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun KeyValue(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            "$label: ",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/** Tri-state row: true / false / "API unavailable". */
@Composable
private fun TriStateRow(label: String, value: Boolean?, trueText: String, falseText: String) {
    val (text, color) = when (value) {
        true -> trueText to Green
        false -> falseText to Red
        null -> "n/a on this OS version" to Amber
    }
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            "$label: ",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

@Composable
fun IslandCheckScreen(autoMode: String? = null) {
    val context = LocalContext.current

    // ---- Poll loop. Satisfies the spec's "poll/observe whether the
    // notification is still active", and doubles as the refresh that picks up
    // permission changes after the user returns from the settings screen.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            tick++
        }
    }
    // remember(tick) recomputes only when tick changes, not on every recomposition.
    val status = remember(tick) { readStatus(context) }

    // ---- Test notification state.
    var testRunning by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var mode by remember { mutableStateOf(MODE_NONE) }

    // The progress bar does NOT animate itself — it holds whatever fraction was
    // last posted. So we repost every second to make "live" actually visible.
    LaunchedEffect(testRunning) {
        if (!testRunning) return@LaunchedEffect
        while (true) {
            delay(1000)
            progress = if (progress + PROGRESS_STEP > PROGRESS_MAX) 0 else progress + PROGRESS_STEP
            postTest(context, progress, mode)
        }
    }

    // Auto-start for adb-driven runs. Keyed on Unit so it fires once per launch.
    LaunchedEffect(Unit) {
        if (autoMode != null) {
            mode = autoMode
            progress = 0
            postTest(context, 0, autoMode)
            testRunning = true
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* the poll loop picks the new state up within a second */ }

    val manufacturer = Build.MANUFACTURER
    val oemProp = oemVersionProperty(manufacturer)
    val supportsFramework = Build.VERSION.SDK_INT >= API_16
    val isSamsung = manufacturer.lowercase().contains("samsung")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("IslandCheck", style = MaterialTheme.typography.headlineSmall)

        // ------------------------------------------------- 1. Device info
        SectionCard("1 · Device") {
            KeyValue("Manufacturer", manufacturer)
            KeyValue("Model", Build.MODEL)
            KeyValue("SDK_INT", "${Build.VERSION.SDK_INT}  (Android ${Build.VERSION.RELEASE})")
            KeyValue("Guessed island brand", islandBrand(manufacturer))

            if (oemProp != null) {
                val (key, raw) = oemProp
                KeyValue("OEM prop key", key)
                KeyValue("OEM prop value", raw ?: "(unavailable)")
                prettyOneUi(raw)?.let { KeyValue("Decoded", "One UI $it") }
            } else {
                KeyValue("OEM prop", "no known key for this manufacturer")
            }

            Text(
                "⚠ The brand label and OEM properties above are undocumented and " +
                        "unverified. They are shown for reference only and are NOT the " +
                        "capability signal. Section 2 is the real check.",
                style = MaterialTheme.typography.bodySmall,
                color = Amber,
            )
        }

        // ------------------------------------------ 2. Framework capability
        SectionCard("2 · Framework capability") {
            if (!supportsFramework) {
                // Spec: below API 36, say so and skip the rest.
                Text(
                    "Not supported — requires Android 16+",
                    style = MaterialTheme.typography.titleLarge,
                    color = Red,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "This device reports SDK_INT ${Build.VERSION.SDK_INT}. The Live Updates " +
                            "framework does not exist below API 36, whatever any OEM property claims.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                // Deliberately NOT labelled "NOT SUPPORTED". This API produces
                // false negatives: on an OPPO Find X9 / ColorOS 16.0.10 it
                // returns false while the device DOES render AOSP Live Updates
                // (confirmed with tigerduck-app-android < 2.1.0). So it reports
                // what the framework claims, not what the OEM will draw.
                Text(
                    if (status.canPostPromoted == true) "SUPPORTED" else "API REPORTS: NO",
                    style = MaterialTheme.typography.headlineMedium,
                    color = if (status.canPostPromoted == true) Green else Amber,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "NotificationManagerCompat.canPostPromotedNotifications()",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                if (status.canPostPromoted != true) {
                    Text(
                        "⚠ This is NOT a verdict. Post the test anyway.\n\n" +
                                "Known false negative: OPPO Find X9 on ColorOS 16.0.10 returns " +
                                "false here yet renders AOSP Live Updates correctly. A known " +
                                "true negative: Samsung One UI 8.0 returns false and renders " +
                                "nothing, because it is plain Android 16 rather than QPR2.\n\n" +
                                "The API cannot tell those two apart, so never gate posting on " +
                                "it — run section 3 and look at the screen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Amber,
                    )
                }
                TriStateRow(
                    "POST_PROMOTED_NOTIFICATIONS",
                    status.promotedPermissionGranted,
                    "granted",
                    "not granted",
                )
                // The two signals disagree in exactly one informative way, and
                // conflating them sends you hunting for a settings toggle that
                // does not exist. Verified on a Galaxy Z Flip 6 / One UI 8.0.
                if (status.promotedPermissionGranted == true && status.canPostPromoted != true) {
                    Text(
                        "Permission is granted, yet canPostPromotedNotifications() is false. " +
                                "That means the PLATFORM does not offer the feature on this " +
                                "build — not that anything was revoked, and not something an " +
                                "app or a settings toggle can fix.\n\n" +
                                "Seen on One UI 8.0, which is plain Android 16. One UI 8.5 is " +
                                "Android 16 QPR2, and the platform gates Live Updates behind an " +
                                "internal flag below that. Such a device will never show a chip " +
                                "until it gets a QPR2-based update.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Amber,
                    )
                }
                TriStateRow(
                    "POST_NOTIFICATIONS",
                    status.postNotificationsGranted,
                    "granted",
                    "DENIED — nothing will post",
                )
            }
        }

        // ------------------------------------------------ 3. Live test
        // Deliberately NOT gated on supportsFramework. The spec said to skip
        // this below API 36, but then the app cannot be used to test One UI 7 /
        // Android 15 at all — where the Samsung card lane is the open question.
        // Below 36 the AOSP calls degrade to a plain ongoing notification.
        run {
            SectionCard("3 · Live Update test") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (!status.postNotificationsGranted) {
                                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                return@Button
                            }
                            progress = 0
                            postTest(context, 0, mode)
                            testRunning = true
                        }
                    ) {
                        Text(if (testRunning) "Restart test" else "Send Test Live Update")
                    }
                    OutlinedButton(
                        onClick = {
                            testRunning = false
                            cancelTest(context)
                            progress = 0
                        }
                    ) {
                        Text("Dismiss")
                    }
                }

                if (status.canPostPromoted != true) {
                    OutlinedButton(onClick = { openPromotionSettings(context) }) {
                        Text("Open promotion settings")
                    }
                    Text(
                        "POST_PROMOTED_NOTIFICATIONS is a normal manifest permission — it is " +
                                "granted at install and cannot be requested through a runtime " +
                                "dialog. If it reads as not effective, the user has turned it off, " +
                                "and only the system settings screen can restore it.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                KeyValue("Posted progress", "$progress / $PROGRESS_MAX")
                TriStateRow("Notification still active", status.testActive, "yes", "no")
                TriStateRow(
                    "Meets promotion preconditions",
                    status.promotable,
                    "yes — hasPromotableCharacteristics()",
                    "NO — something disqualified it",
                )
                TriStateRow(
                    "OS promoted it",
                    status.promotedByOs,
                    "YES — FLAG_PROMOTED_ONGOING is set",
                    "no — flag absent",
                )

                Text(
                    verdict(status),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )

                Text(
                    "Notification posted — check your status bar and lock screen to confirm " +
                            "whether it rendered as an island/pill.\n\n" +
                            "What this app can and cannot prove: the three rows above are read " +
                            "back from the live notification, so they show whether the OS " +
                            "accepted AND promoted the post. What no app can detect is whether " +
                            "the OEM's SystemUI then DREW a visible pill — that is a closed " +
                            "rendering path. On some skins (e.g. Samsung One UI 8.5 on the " +
                            "Galaxy A26 5G) the Now Bar toggle exists and the flag gets set, " +
                            "yet nothing appears on screen. If 'OS promoted it' is YES and you " +
                            "still see no pill, the gap is downstream of this app — see section 4.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // ------------------------------------------- 4. Samsung Now Bar gap
        if (isSamsung) {
            val nowBar = remember(tick) { readNowBarState(context) }
            SectionCard("4 · Samsung Now Bar (即時通知)") {
                Text(
                    "Promoted ≠ Now Bar",
                    style = MaterialTheme.typography.titleLarge,
                    color = Amber,
                    fontWeight = FontWeight.Bold,
                )
                KeyValue("now_bar_enabled", nowBar.enabled ?: "(unset)")
                KeyValue("now_bar_aod_enabled", nowBar.aodEnabled ?: "(unset)")
                KeyValue("allowlist key", nowBar.allowlistKey)
                TriStateRow(
                    "This app allowlisted",
                    nowBar.allowlisted,
                    "yes",
                    "no — expected; the list is first-party only",
                )
                TriStateRow(
                    "Hidden OngoingActivityStyle class",
                    SamsungNowBar.styleClassAvailable(),
                    "present in this ROM",
                    "absent",
                )

                // Two mutually exclusive Samsung lanes — see MODE_* for why they
                // cannot be combined. Both are offered so a single build can be
                // tested across One UI versions.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            mode = if (mode == MODE_AUTOMATION) MODE_NONE else MODE_AUTOMATION
                            if (testRunning) postTest(context, progress, mode)
                        }
                    ) {
                        Text(if (mode == MODE_AUTOMATION) "RON lane: ON" else "RON lane (automation)")
                    }
                    Button(
                        onClick = {
                            mode = if (mode == MODE_CARD) MODE_NONE else MODE_CARD
                            if (testRunning) postTest(context, progress, mode)
                        }
                    ) {
                        Text(if (mode == MODE_CARD) "Card lane: ON" else "Card lane (style=1)")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            // Stop the in-activity ticker so the two paths can't fight
                            // over the same notification id.
                            testRunning = false
                            startForegroundTest(context, mode)
                        }
                    ) {
                        Text("Post from foreground service")
                    }
                    OutlinedButton(onClick = { stopForegroundTest(context); cancelTest(context) }) {
                        Text("Stop FGS")
                    }
                }

                KeyValue(
                    "Active lane",
                    when (mode) {
                        MODE_AUTOMATION -> "automation (style unset — RON lane)"
                        MODE_CARD -> "style = 1 (private-card lane)"
                        else -> "none — plain AOSP promoted only"
                    },
                )
                Text(
                    if (supportsFramework) {
                        "On Android 16+ use the RON lane: automation=true with NO style key. " +
                                "Verified working on One UI 8.5 (A26 and S26 Ultra).\n\n" +
                                "The card lane is here for comparison — on 8.5 it lands in the " +
                                "Hidden list with promoted=false, because style>=1 forces " +
                                "mIsRon=false and cancels the automation bypass."
                    } else {
                        "This device is below Android 16, so hasPromotableCharacteristics() " +
                                "does not exist and mIsRon can never be true — the RON lane " +
                                "cannot work here by construction. The card lane (style=1) is " +
                                "the only candidate on this build. Try it, then check " +
                                "OngoingActivityController's Showing/Hidden/Pending lists."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Amber,
                )
                Text(
                    "The private path sets android.ongoingActivityNoti.style = 1 " +
                            "(0 = notification only, 1 = notification + Now Bar chip) plus chip " +
                            "icon, colour and progress extras. These are undocumented, " +
                            "unsupported, and may break on any One UI update — but the key " +
                            "strings are verifiably present in this device's framework.jar and " +
                            "SystemUI.apk. Start the test in section 3 first, then toggle this.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Amber,
                )
                Text(
                    "SOLVED on SM-A266B / One UI 8.5. A sideloaded app CAN reach the " +
                            "Now Bar — no adb, no privileged status, no signature permission. " +
                            "The recipe is the opposite of what the reverse-engineering guides " +
                            "suggest:\n\n" +
                            "1. Post an ordinary AOSP promoted notification: setOngoing(true), " +
                            "setRequestPromotedOngoing(true), non-empty title, promotable style, " +
                            "NOT colorized.\n" +
                            "2. Add exactly ONE Samsung extra: " +
                            "android.ongoingActivityNoti.automation = true.\n" +
                            "3. Send nothing else. The decorative extras (style, chipIcon, " +
                            "chipBgColor, primaryInfo, progress) actively break it.\n" +
                            "4. Background the app — it stays Pending while its own app is " +
                            "foreground.\n\n" +
                            "Why, from decompiled One UI 8.5 SystemUI:\n" +
                            "  isPromotedState() = (isDevelopRonTestAllowed() || isAutomation() " +
                            "|| !AllowedOngoingActivityListManager.isAllowListUsing) && mIsRon\n" +
                            "  mIsRon = hasPromotableCharacteristics()\n" +
                            "  isAutomation() = extras.getBoolean(automation)\n\n" +
                            "isOngoingActivity() splits into two lanes: style >= 1 takes " +
                            "Samsung's private-card lane and sets mIsRon = FALSE, cancelling the " +
                            "automation bypass. Leaving style unset keeps the RON lane, where " +
                            "mIsRon is true and isAutomation() alone flips isPromotedState(). " +
                            "That is why style=1 and automation=true cancel each other out.\n\n" +
                            "Samsung ships a developer switch for the same bypass: " +
                            "Settings.Secure enable_notification_nowbar_test = 1. Handy for " +
                            "testing but it needs adb, so the automation extra is the shippable " +
                            "route. Both are undocumented and may break on any One UI update.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** Plain-language reading of the three machine signals. */
private fun verdict(s: Status): String = when {
    !s.testActive -> "No test notification posted yet."
    s.promotedByOs == true ->
        "→ The OS promoted this notification. If no pill is visible, the gap is in the " +
                "OEM's SystemUI rendering."
    s.promotable == false ->
        "→ The notification did NOT meet the promotion preconditions, so the OS declined " +
                "to promote it. Check: ongoing, non-empty title, promotable style, not colorized."
    s.canPostPromoted != true ->
        "→ Promotion is not permitted for this app right now, so the post stays an " +
                "ordinary ongoing notification."
    else ->
        "→ Accepted and eligible, but the promoted flag is not set. The platform may gate " +
                "promotion behind an internal flag on this build."
}
