package app.crazygames.patches.tamper

import app.crazygames.patches.shared.Constants.COMPATIBILITY_CRAZYGAMES
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/**
 * CrazyGames 1.7.14 — disable the installer-name tamper check.
 *
 * WHY: `CrazyGamesApplication.onCreate()` reads the package that installed the
 * app, hashes it with a salt and looks the result up in a one-entry blacklist
 * (`Lrr;->a`). The entry is
 * `sha256("ddf3401cac0d92a767306e43ec35eaeb" + "app.morphe.manager")`, so an APK
 * installed by **Morphe Manager** finishes every task and calls
 * `Process.killProcess(Process.myPid())` during Application.onCreate — a silent
 * SIGKILL with no stack trace:
 *
 *   ApplicationExitInfo: reason=2 (SIGNALED) subreason=0 (UNKNOWN) status=9
 *   logcat:              I Process : Sending signal. PID: <pid> SIG: 9
 *
 * The same APK installed over adb / morphe-cli runs fine, because
 * `getInstallerPackageName()` returns null there and the check is skipped
 * entirely. That is the whole CLI-vs-Manager difference.
 *
 * HOW: the check result lands in a register via `move-result` immediately after
 * `invoke-interface {..}, Ljava/util/Set;->contains(Ljava/lang/Object;)Z`, then
 * `if-eqz <result>, :cond_xx` consumes it (taken → skip the kill). Pinning that
 * `move-result` to `const/4 <result>, 0x0` makes the blacklisted branch always
 * fall through to normal startup. Both instructions touch one register, so the
 * method stays verifier-safe, and the rest of `onCreate` (Adjust, Capacitor,
 * Firebase, ...) runs untouched.
 */
@Suppress("unused")
val crazyGamesInstallerTamperPatch = bytecodePatch(
    name = "Bypass installer tamper check",
    description = "Disables the installer-package blacklist in CrazyGamesApplication.onCreate and " +
        "in the portal's document-start bundle. Without it, an APK installed by Morphe Manager " +
        "(installer app.morphe.manager) is SIGKILLed during startup and, if it survives, shows " +
        "the \"Time to update\" wall. adb-installed builds are unaffected. No gameplay impact.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_CRAZYGAMES)

    // ── Web half ────────────────────────────────────────────────────────────
    // The portal repeats the same blacklist in JS
    // (assets/public/_next/static/chunks/<chunk>.js):
    //
    //   K = new Set(["b5a5c4ad...4632"])                      // sha256(salt + "app.morphe.manager")
    //   H(e) { return K.has(await sha256(salt + e)) }
    //   F()  { return H((await Utilities.getInstallSource()).source) }
    //   ...
    //   Promise.all([App.getInfo(), F()]).then(([t, a]) => {
    //     a || !isVersionAtLeast(t.version, "1.7.3")
    //       ? (window.__cgForceUpdateNeeded = true,
    //          window.dispatchEvent(new CustomEvent("cg_force_update_needed", ...)))
    //       : ...
    //   })
    //
    // and a component that opens the "forceUpdate" drawer when the flag/event
    // appears. Because MINIMUM_APP_VERSION is 1.7.3, the version branch is
    // fine; only the installer branch can trigger, so suppressing the flag and
    // the event is sufficient and does not depend on chunk file names (which
    // are hashed per build). Runs in the top-level portal document only, never
    // in game iframes.
    dependsOn(
        resourcePatch(
            name = "Bypass installer tamper check (web)",
            description = "Neutralises the portal's installer-blacklist force-update flag " +
                "through the existing document-start script.",
            default = false,
        ) {
            compatibleWith(COMPATIBILITY_CRAZYGAMES)

            execute {
                val asset = get("assets/ExternalPaymentScript.js", true)
                val original = asset.readText()
                val marker = "/* crazygames-installer-tamper:v1 */"

                if (original.contains(marker)) {
                    println("CrazyGames tamper: web shim already present")
                    return@execute
                }

                asset.appendText("\n\n$marker\n$TAMPER_SHIM\n")
                println(
                    "CrazyGames tamper: appended ${TAMPER_SHIM.length} B install-source shim " +
                        "to assets/ExternalPaymentScript.js",
                )
            }
        },
    )

    execute {
        val method = InstallerTamperFingerprint.method
        val instructions = method.implementation?.instructions
            ?: error("CrazyGames installer tamper: onCreate has no implementation")

        // Set membership result: invoke-interface Set;->contains(Object)Z + move-result.
        val containsIndex = instructions.indexOfFirst { instruction ->
            if (instruction.opcode != Opcode.INVOKE_INTERFACE) return@indexOfFirst false
            val reference = (instruction as ReferenceInstruction).reference as? MethodReference
                ?: return@indexOfFirst false
            reference.definingClass == "Ljava/util/Set;" &&
                reference.name == "contains" &&
                reference.returnType == "Z"
        }

        check(containsIndex >= 0) {
            "CrazyGames installer tamper: Set->contains not found in onCreate — " +
                "the check was restructured, review the fingerprint"
        }

        val moveResultIndex = (containsIndex + 1 until instructions.size)
            .firstOrNull { index -> instructions[index].opcode == Opcode.MOVE_RESULT }
            ?: error(
                "CrazyGames installer tamper: no move-result after Set->contains — " +
                    "unexpected bytecode layout",
            )

        val resultRegister =
            method.getInstruction<OneRegisterInstruction>(moveResultIndex).registerA

        // matchResult = false → `if-eqz` skips finishAndRemoveTask/killProcess.
        method.replaceInstruction(moveResultIndex, "const/4 v$resultRegister, 0x0")
    }
}

/**
 * Document-start shim. The application of `__cgForceUpdateNeeded` is swallowed
 * (getter stays false) and the `cg_force_update_needed` event is dropped before
 * the portal's listener can open the force-update drawer. Scoped to the
 * top-level document so game iframes are untouched; every other event and
 * property assignment passes through unchanged.
 */
private const val TAMPER_SHIM = """
(function () {
  "use strict";
  try {
    if (window.top !== window) return;
  } catch (e) {
    return;
  }

  try {
    Object.defineProperty(window, "__cgForceUpdateNeeded", {
      configurable: true,
      get: function () { return false; },
      set: function () { /* installer-blacklist flag swallowed */ }
    });
  } catch (e) {}

  try {
    var originalDispatch = window.dispatchEvent;
    window.dispatchEvent = function (event) {
      if (event && event.type === "cg_force_update_needed") {
        console.log("[cg-tamper-patch] suppressed force-update event");
        return true;
      }
      return originalDispatch.call(window, event);
    };
  } catch (e) {}
})();
"""

