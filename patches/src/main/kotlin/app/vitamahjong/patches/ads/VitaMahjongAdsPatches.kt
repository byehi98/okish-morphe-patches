package app.vitamahjong.patches.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.util.returnEarly
import app.vitamahjong.patches.shared.Constants.COMPATIBILITY_VITA_MAHJONG
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

/**
 * Vita Mahjong — Remove Ads (3.42.1 / versionCode 1913).
 *
 * One toggle covering all ad surfaces:
 *
 *  1. Rewarded ads (`Lvd/a.show()Lyd/i;`) — fire `onADWillShow`,
 *     `onRewardedVideoCompleted`, `onADClose` immediately and return null.
 *     Rewards are credited without a video loading or playing.
 *  2. Interstitials (`Lsd/a.show()Lyd/i;`) — fire `onADWillShow` + `onADClose`
 *     immediately and return null. No full-screen/app-open ad, and gameplay
 *     resumes at once.
 *  3. Banners (`AdModuleBridge.showBanner(...)`) — `return-void` at entry, so
 *     the banner view/Runnable is never created and no banner request is made.
 *  4. Readiness gates (`Lkd/n.o(...)Z`, `Lkd/n.p(String)Z`) — forced to `true`
 *     so ad-gated buttons stay usable offline or when no placement config/fill
 *     exists.
 *
 * WHY THE HELPER METHODS FOR `show()`:
 * Both `show()` methods carry a try/catch table. Morphe's instruction-tree
 * writer drops injected *labeled* blocks when merging into a method that has
 * catches (see `app/missiles/patches/billing/MissilesBillingBypassPatch.kt`).
 * The callback logic (including the null-listener `if-eqz`) is therefore moved
 * into brand-new helper methods with no exception table, and `show()` is
 * replaced with a straight-line `invoke-direct` + `return-object null`.
 *
 * The helpers are instance methods on `Lvd/a` / `Lsd/a` so the protected
 * `Lqd/a;->c` listener field is read through a `this` reference of the subclass
 * type, exactly like the existing callback bodies. A null listener is guarded.
 */
@Suppress("unused")
val vitaMahjongRemoveAdsPatch = bytecodePatch(
    name = "Vita Mahjong Remove Ads",
    description = "Removes banners and interstitials and makes rewarded ads pay out instantly without a video. Ad-gated buttons stay usable even when no ad is filled or the placement config is missing.",
    default = true
) {
    compatibleWith(COMPATIBILITY_VITA_MAHJONG)

    execute {
        // ══ 1. Rewarded: instant reward, no video ═════════════════════════════
        // .registers 3 → locals v0/v1, p0=v2 (instance method, no params).
        val morpheReward = ImmutableMethod(
            "Lvd/a;",
            "morpheReward",
            emptyList(),
            "V",
            AccessFlags.PRIVATE.value,
            null,
            null,
            MutableMethodImplementation(3)
        ).toMutable().apply {
            addInstructionsWithLabels(0, """
                # v0 = listener; null-safe (show() may run before setADListener)
                iget-object v0, p0, Lqd/a;->c:Lcom/meevii/adsdk/common/IADListener;
                if-eqz v0, :morphe_done
                # v1 = placement id (public Lqd/a.g())
                invoke-virtual {p0}, Lqd/a;->g()Ljava/lang/String;
                move-result-object v1
                invoke-virtual {v0, v1}, Lcom/meevii/adsdk/common/IADListener;->onADWillShow(Ljava/lang/String;)V
                invoke-virtual {v0, v1}, Lcom/meevii/adsdk/common/IADListener;->onRewardedVideoCompleted(Ljava/lang/String;)V
                invoke-virtual {v0, v1}, Lcom/meevii/adsdk/common/IADListener;->onADClose(Ljava/lang/String;)V
                :morphe_done
                return-void
            """.trimIndent())
        }
        RewardedShowFingerprint.classDef.methods.add(morpheReward)

        // Straight-line entry replacement — no labels, safe with show()'s catch table.
        RewardedShowFingerprint.method.addInstructions(0, """
            invoke-direct {p0}, Lvd/a;->morpheReward()V
            const/4 v0, 0x0
            return-object v0
        """.trimIndent())

        // ══ 2. Interstitials: skip, immediately close ═════════════════════════
        // .registers 3 → locals v0/v1, p0=v2 (instance method, no params).
        val morpheInter = ImmutableMethod(
            "Lsd/a;",
            "morpheInter",
            emptyList(),
            "V",
            AccessFlags.PRIVATE.value,
            null,
            null,
            MutableMethodImplementation(3)
        ).toMutable().apply {
            addInstructionsWithLabels(0, """
                # v0 = listener; null-safe
                iget-object v0, p0, Lqd/a;->c:Lcom/meevii/adsdk/common/IADListener;
                if-eqz v0, :morphe_done
                # v1 = placement id
                invoke-virtual {p0}, Lqd/a;->g()Ljava/lang/String;
                move-result-object v1
                invoke-virtual {v0, v1}, Lcom/meevii/adsdk/common/IADListener;->onADWillShow(Ljava/lang/String;)V
                invoke-virtual {v0, v1}, Lcom/meevii/adsdk/common/IADListener;->onADClose(Ljava/lang/String;)V
                :morphe_done
                return-void
            """.trimIndent())
        }
        InterstitialShowFingerprint.classDef.methods.add(morpheInter)

        // Straight-line entry replacement — no labels, safe with show()'s catch table.
        InterstitialShowFingerprint.method.addInstructions(0, """
            invoke-direct {p0}, Lsd/a;->morpheInter()V
            const/4 v0, 0x0
            return-object v0
        """.trimIndent())

        // ══ 3. Banners: never create/show the banner view ═════════════════════
        // showBanner has .registers 13 and no exception table; entry return is safe.
        ShowBannerFingerprint.method.returnEarly()

        // ══ 4. Readiness/validity gates: always true ══════════════════════════
        // o(...)Z has .registers 7, p(String)Z has .registers 5; both have a v0.
        AdReadyFingerprint.method.returnEarly(true)
        AdValidFingerprint.method.returnEarly(true)
    }
}
