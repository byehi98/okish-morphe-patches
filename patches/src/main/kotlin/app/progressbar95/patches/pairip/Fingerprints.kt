package app.progressbar95.patches.pairip

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

// ── Progressbar95 1.1111 — Google PAIRIP license/integrity SDK ─────────────────
//
// `com.pairip.*` is the Google Play-distribution integrity SDK Google injects
// at build time. It is NOT an emulator detector, NOT anti-tamper native code
// and NOT Frida/Xposed detection — the `isEmulator` / `isRooted` / `frida` /
// `xposed` strings elsewhere in the dex are Unity Ads + Audience Network SDK
// noise and were confirmed red herrings.
//
// Observed failure (Waydroid Android 13): the game boots fine (Houdini ARM
// translation, `libcorona.so`, OpenAL), then ~190 ms later PAIRIP launches its
// own activity and kills the process — `s.progressbar95: System.exit called,
// status: 0`. Clean exit, no crash, so it is invisible as a "crash" to the
// player and fatal to the app.
//
// Control flow (classes3):
//
//   LicenseClient.createCloseAppIntentOrExitIfAppInBackground()  (private)
//      ├─ !isForeground() → LicenseClient.exitAction.run()
//      │                     → LicenseClient$1.run() → System.exit(0)   ← site B
//      └─ isForeground()  → startActivity(LicenseActivity, NEW_TASK|BROUGHT_TO_FRONT|NO_HISTORY)
//                                → LicenseActivity.onStart()
//                                   → showPaywallAndCloseApp() / showErrorDialog()
//                                      → closeApp() → exitApp()
//                                         → finishAndRemoveTask()
//                                         → System.exit(0)                ← site A
//
// There are exactly TWO `System.exit` sites in all of PAIRIP, and the three
// fingerprints below cover them plus their shared entry point.
//
// NOTE: all three class names are stable Google SDK names — PAIRIP is never
// obfuscated — so `definingClass` alone is a sufficient anchor (the
// stable-SDK doctrine; no filters, no obfuscated-name dependence). `accessFlags`
// is an EXACT match in morphe-patcher, so the flags below are load-bearing.
//
// Verified against:
//   analysis/progressbar95/smali/classes3/com/pairip/licensecheck/LicenseActivity.smali
//   analysis/progressbar95/smali/classes3/com/pairip/licensecheck/LicenseClient$1.smali

/**
 * `LicenseActivity.onStart()V` — `.registers 3`, **public**, no params.
 *
 * PAIRIP's overlay entry point (`ActivityType.PAYWALL = 0` / `ERROR_DIALOG = 1`).
 * This is the method PAIRIP launches as a separate task, so stubbing it skips
 * the paywall, the error dialog AND the `System.exit` that both of them funnel
 * into via `closeApp()`.
 *
 * The stub must also call `finish()` — see the patch KDoc. Uses `p0` only
 * (`p0` = v2 here, inside the v0–v15 non-range window, so `{p0}` is a legal
 * register reference).
 */
object LicenseActivityOnStartFingerprint : Fingerprint(
    definingClass = "Lcom/pairip/licensecheck/LicenseActivity;",
    name = "onStart",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(),
)

/**
 * `LicenseActivity.exitApp()V` — `.registers 2`, **protected**, no params.
 *
 * `System.exit(0)` site A — the choke point both `closeApp()` and
 * `closeAllTasks()` funnel into on the foreground path. Belt-and-braces in
 * case `onStart` is ever entered through a route this stub does not cover.
 */
object LicenseActivityExitAppFingerprint : Fingerprint(
    definingClass = "Lcom/pairip/licensecheck/LicenseActivity;",
    name = "exitApp",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PROTECTED),
    parameters = listOf(),
)

/**
 * `LicenseClient$1.run()V` — `.registers 2`, **public**, no params.
 *
 * `System.exit(0)` site B — the `exitAction` `Runnable`, invoked on the
 * background path when PAIRIP finds the game is not in the foreground. Reached
 * independently of `LicenseActivity`, so it must be stubbed separately; leaving
 * it live means the game can still be killed by a background license check.
 */
object LicenseClientExitActionRunFingerprint : Fingerprint(
    definingClass = "Lcom/pairip/licensecheck/LicenseClient\$1;",
    name = "run",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(),
)