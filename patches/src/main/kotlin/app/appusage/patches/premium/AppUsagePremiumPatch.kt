package app.appusage.patches.premium

import app.appusage.patches.shared.Constants.COMPATIBILITY_APP_USAGE
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/**
 * Unlock the PRO features of App Usage 6.19 without buying anything.
 *
 * Purchase verification is not worth emulating: the Play purchase token is
 * checked with two hardcoded MD5 digests plus an RSA check, and a replay of a
 * real token is the only thing that would pass. The entitlement is also not
 * stored — it is four in-memory AtomicBoolean flags in `Lgln` that are
 * recomputed from the licensing service on every launch. So this patch forces
 * the *outcome* instead of the *cause*:
 *
 *   1. Lgln->驄(Context)Z  →  return true      (isPro)
 *   2. Lgln->鷛(Context)Z  →  return false     (free-build / paywall gate)
 *   3. Lisc->躤(Activity)V →  return-void      (the "Upgrade to PRO" dialog)
 *   4. Lbob->onCreate()V   →  drop the unconditional `襭.set(true)` so the
 *                             "free version" master lock stays false
 *
 * Why (4) is needed even though (1) already answers `true`: `襭` (jadx
 * `gln.f36050`) is not only read through `驄`. ~30 UI sites read the field
 * directly (`if (gln.f36050.get())` → show the PRO-only placeholder / paywall),
 * e.g. all four widget configuration windows, `AboutWnd`, `AdFreeRewardedVideoWnd`
 * and `FindAppsWnd`. `Lbob.onCreate` (the Application) flips it to true on every
 * process start; the only other writers (`LicWnd.躚`, `hor.<init>`,
 * `AppOpts2Wnd.onDestroy`) sit behind the certificate hash check and are already
 * dead once "Spoof original signature" is applied. `LicWnd.躚` additionally
 * returns early because it calls `驄()` first, which this patch makes return true.
 *
 * The `癰` (purchased) and `驄`/`鷛` (trial / sync) flags are deliberately left
 * alone: `m13413 == true` short-circuits every `m13413(...) || 癰.get() || 驄.get()`
 * chain, so faking a purchase would only add state that the licensing service
 * can contradict later (and `AppLogWnd` would start printing "PRO license: yes").
 *
 * Injections 1–3 are `return` prepends at index 0 and are verifier-safe: all
 * three methods have `.registers 4` / `.registers 12`, no try/catch region and no
 * monitor-enter before the first instruction, so no local or parameter has to be
 * re-established. Injection 4 is a single `invoke-virtual` removal (the call
 * returns void, so no `move-result` is orphaned), which avoids touching the `v6`
 * register that the rest of `onCreate` reuses as the literal `1`.
 */
@Suppress("unused")
val appUsagePremiumPatch = bytecodePatch(
    name = "Unlock premium",
    description = "Unlocks the PRO entitlement: isPro returns true, the free-build/paywall gate " +
        "returns false, the \"Upgrade to PRO\" dialog is disabled and the free-version master flag " +
        "is never set at startup (which also removes the PRO-only placeholders and limits in the " +
        "widget configuration, usage history, export and backup windows).",
) {
    compatibleWith(COMPATIBILITY_APP_USAGE)

    execute {
        // 1. `Lgln;->驄(Context)Z` (isPro) → true.
        IsProFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x1
                return v0
            """,
        )

        // 2. `Lgln;->鷛(Context)Z` (free build / paywall gate) → false.
        //    Also makes the field copy of the same check agree: the first branch
        //    of this method returns `襭.get()`, which is false after edit 4.
        IsFreeBuildFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return v0
            """,
        )

        // 3. `Lisc;->躤(Activity)V` ("Upgrade to PRO" dialog) → no-op.
        ShowPurchaseDialogFingerprint.method.addInstructions(0, "return-void")

        // 4. Remove the unconditional `襭.set(true)` from Application.onCreate.
        //    It is the only AtomicBoolean.write in the method:
        //      sget-object v7, Lgln;->豅:Lgln;
        //      iget-object v7, v7, Lgln;->襭:Ljava/util/concurrent/atomic/AtomicBoolean;
        //      invoke-virtual {v7, v6}, Ljava/util/concurrent/atomic/AtomicBoolean;->set(Z)V
        val onCreate = ApplicationOnCreateFingerprint.method
        val setIndex = onCreate.indexOfFirstInstructionOrThrow {
            getReference<MethodReference>()?.let { reference ->
                reference.definingClass == "Ljava/util/concurrent/atomic/AtomicBoolean;" &&
                    reference.name == "set" &&
                    reference.parameterTypes.singleOrNull()?.toString() == "Z"
            } == true
        }
        onCreate.removeInstruction(setIndex)
    }
}
