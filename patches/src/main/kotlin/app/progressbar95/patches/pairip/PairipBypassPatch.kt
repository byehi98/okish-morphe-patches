package app.progressbar95.patches.pairip

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.progressbar95.patches.shared.Constants.COMPATIBILITY_PROGRESSBAR95

/**
 * Progressbar95 1.1111 · **PAIRIP bypass**.
 *
 * PAIRIP (`com.pairip.*`) is Google's Play-distribution integrity/license SDK,
 * injected into the APK at build time. It is not an emulator detector, not
 * anti-tamper native code and not Frida/Xposed detection — the `isEmulator` /
 * `isRooted` / `frida` / `xposed` strings elsewhere in the dex are Unity Ads +
 * Audience Network SDK noise (confirmed red herrings). What it *does* do is
 * refuse to run a build that is not signed by the original Play key: on a
 * re-signed/patched install the license check fails, PAIRIP starts a
 * `LicenseActivity` overlay on top of the game and then calls `System.exit(0)`
 * ~190 ms later. Observed on Waydroid Android 13 as
 * `s.progressbar95: System.exit called, status: 0` — a *clean* exit, so it
 * never looks like a crash and the game just disappears on launch.
 *
 * There are exactly TWO `System.exit` sites in all of PAIRIP, reached by two
 * independent paths:
 *
 * ```
 * LicenseClient.createCloseAppIntentOrExitIfAppInBackground()   (private)
 *    ├─ isForeground()  → startActivity(LicenseActivity, …)
 *    │                       → onStart() → showPaywallAndCloseApp() / showErrorDialog()
 *    │                          → closeApp() → exitApp()
 *    │                             → finishAndRemoveTask() + System.exit(0)   ← site A
 *    └─ !isForeground() → LicenseClient.exitAction.run()
 *                           → LicenseClient$1.run() → System.exit(0)        ← site B
 * ```
 *
 * This patch stubs all three:
 *
 *  1. `LicenseActivity.onStart()V` — `finish()` then `return-void`. Skips the
 *     paywall/error-dialog check entirely. The `finish()` is **required**, not
 *     cosmetic: PAIRIP starts `LicenseActivity` as its own task with
 *     `FLAG_ACTIVITY_NEW_TASK | BROUGHT_TO_FRONT | NO_HISTORY`, and
 *     `LicenseActivity` declares no theme override — i.e. it inherits the
 *     system default, which is **opaque**. A bare `return-void` here would
 *     leave an invisible-but-present black overlay on top of the game, so the
 *     activity is dismissed as well as neutered.
 *  2. `LicenseActivity.exitApp()V` — `return-void`, dropping site A's
 *     `System.exit(0)` and `finishAndRemoveTask()`.
 *  3. `LicenseClient$1.run()V` — `return-void`, dropping site B.
 *
 * Hooks 2 and 3 are **belt-and-braces**. Stubbing `onStart` alone is *probably*
 * enough — every visible PAIRIP route to `closeApp()` runs through it — but
 * `closeApp()`/`closeAllTasks()` are reachable from PAIRIP's internal state
 * machine and from the deferred `exitAction` Runnable, and neither is
 * guaranteed to stay behind `onStart` across SDK revisions. Since all three
 * inserts are a few bytes of straight-line code with no side effects, pinning
 * both `System.exit` sites directly costs nothing and removes the "silent
 * version-dependent re-exit" failure mode entirely.
 *
 * Register budget: all three inserts are branch-free and touch no locals —
 *  `onStart` (`.registers 3`, p0 = v2) uses only `p0`; `exitApp`
 *  (`.registers 2`) and `LicenseClient$1.run` (`.registers 2`) use no
 *  registers at all. `p0` on `onStart` is v2, inside the v0–v15 window, so
 *  `{p0}` in a non-range `invoke-virtual` is a legal register reference. Every
 *  insert is straight-line, so there is no label/branch to invalidate and
 *  exception tables are a non-issue (`onStart` has no try/catch; the
 *  try/catch-bearing `closeAllTasks` is not patched). Original bodies stay in
 *  place as unreachable dead code — the standard short-circuit shape.
 *
 * `default = true`: without this the game hard-exits ~190 ms after every
 * launch on a patched install, so this patch is not optional.
 *
 * Known remaining gap (not addressed here): the Corona LVL gate
 * (`CoronaProvider.licensing.google.LuaLoader`) sends a licensing request that
 * never returns on a sideloaded build — neither `allow` nor `dontAllow` fires,
 * so the sibling `license` package's `LicenseBypassPatch` (which only
 * redirects `dontAllow` → `allow`) never gets its redirect. If the game still
 * hangs after this bypass lands,
 * hook `LuaLoader;->verify(LuaState;)I` to dispatch a licensed verdict
 * directly — and set `fListener` via `CoronaLua.newRef(L, 1)` *before* calling
 * the private `callLuaCallback(Z,String,Z,String)`, because the dispatched
 * task reads `fListener`. Verify on-device before adding.
 */
@Suppress("unused")
val progressbar95PairipBypassPatch = bytecodePatch(
    name = "Skip Play License Check",
    description = "Stops Google Play's PAIRIP check from showing an overlay and force-closing the game.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_PROGRESSBAR95)

    execute {
        // Entry point: skip the paywall/error-dialog check AND dismiss the
        // opaque overlay activity PAIRIP started on top of the game.
        //
        // `invoke-super` FIRST is mandatory, not cosmetic: Activity.performStart()
        // cross-checks that the override chained to super and throws
        // SuperNotCalledException otherwise (verified on-device — it killed the
        // whole process before this line was added). Skipping the original
        // super call is what drops the rest of the PAIRIP check body.
        LicenseActivityOnStartFingerprint.method.addInstructions(0, """
            invoke-super {p0}, Landroid/app/Activity;->onStart()V
            invoke-virtual {p0}, Lcom/pairip/licensecheck/LicenseActivity;->finish()V
            return-void
        """.trimIndent())

        // System.exit(0) site A — foreground path, via closeApp() → exitApp().
        LicenseActivityExitAppFingerprint.method.addInstructions(0, """
            return-void
        """.trimIndent())

        // System.exit(0) site B — background path, via the exitAction Runnable.
        LicenseClientExitActionRunFingerprint.method.addInstructions(0, """
            return-void
        """.trimIndent())
    }
}