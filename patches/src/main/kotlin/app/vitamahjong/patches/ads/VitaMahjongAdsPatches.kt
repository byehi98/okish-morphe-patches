package app.vitamahjong.patches.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.util.returnEarly
import app.vitamahjong.patches.shared.Constants.COMPATIBILITY_VITA_MAHJONG
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

private const val PATCH_NAME = "Vita Mahjong Remove Ads"

/**
 * Register budget of every method this patch replaces. Kept as constants so the
 * builder and the post-swap assertion below cannot drift apart.
 *
 * | # | Target | Frame |
 * |---|---|---|
 * | 1 | `vd/a.show()` | 5 regs, `p0 = v4` |
 * | 2 | `sd/a.show()` | 5 regs, `p0 = v4` |
 * | 3 | `AdModuleBridge.showBanner(…)V` | 13 regs (stock) |
 * | 4 | `kd/n.o(…)Z` | 7 regs (stock) |
 * | 5 | `kd/n.p(String)Z` | 5 regs (stock) |
 */
private const val REWARDED_REGISTERS = 5
private const val INTERSTITIAL_REGISTERS = 5

/**
 * Rewarded video: claim the reward and close the ad without ever showing one.
 *
 * `p0 = v4` (5-register frame, instance method, no parameters), so `v0` /
 * `v1` are free locals.
 *
 * The listener is read from the *protected* `Lqd/a;->c` field rather than via a
 * getter. `show()` can legitimately run before `setADListener`, in which case
 * the field is `null` — real, not theoretical — so the guard stays.
 *
 * Callback order is the SDK's own reward order, as emitted by the stock
 * `Lqd/a` helper bodies: `onADWillShow` → `onRewardedVideoCompleted` →
 * `onADClose`. Reordering them would close the ad before the reward is
 * credited and the C# layer would drop the payout.
 */
private val REWARDED_BODY = """
    iget-object v0, p0, Lqd/a;->c:Lcom/meevii/adsdk/common/IADListener;
    if-eqz v0, :morphe_no_listener
    invoke-virtual {p0}, Lqd/a;->g()Ljava/lang/String;
    move-result-object v1
    invoke-virtual {v0, v1}, Lcom/meevii/adsdk/common/IADListener;->onADWillShow(Ljava/lang/String;)V
    invoke-virtual {v0, v1}, Lcom/meevii/adsdk/common/IADListener;->onRewardedVideoCompleted(Ljava/lang/String;)V
    invoke-virtual {v0, v1}, Lcom/meevii/adsdk/common/IADListener;->onADClose(Ljava/lang/String;)V
    :morphe_no_listener
    const/4 v0, 0x0
    return-object v0
"""

/**
 * Interstitial: fire `onADWillShow` + `onADClose` and return "not shown".
 *
 * Same 5-register frame and same null-listener guard as [REWARDED_BODY], minus
 * `onRewardedVideoCompleted` — an interstitial has no reward stage in the MEeVii
 * listener contract.
 */
private val INTERSTITIAL_BODY = """
    iget-object v0, p0, Lqd/a;->c:Lcom/meevii/adsdk/common/IADListener;
    if-eqz v0, :morphe_no_listener
    invoke-virtual {p0}, Lqd/a;->g()Ljava/lang/String;
    move-result-object v1
    invoke-virtual {v0, v1}, Lcom/meevii/adsdk/common/IADListener;->onADWillShow(Ljava/lang/String;)V
    invoke-virtual {v0, v1}, Lcom/meevii/adsdk/common/IADListener;->onADClose(Ljava/lang/String;)V
    :morphe_no_listener
    const/4 v0, 0x0
    return-object v0
"""

/**
 * Replaces the **entire** implementation of [fingerprint]'s method with [body].
 *
 * `MutableMethod` exposes no `implementation` setter (`morphe-patcher` 1.5.2
 * only has a getter, and `MutableMethodImplementation.registerCount` is a
 * `final` dexlib2 field), so the whole method object is rebuilt and swapped into
 * its class:
 *
 *  * a fresh `MutableMethodImplementation(registers)` — **zero** try/catch
 *    items, because the stock `.catchall` is not copied;
 *  * **zero** leftover stock instructions, because nothing is copied;
 *  * **no** new method is added, because the original is removed from the
 *    class in the same transaction (only its body is gone).
 *
 * The signature, access flags and defining class are inherited from the
 * matched method, so the dex method reference does not change.
 *
 * The result is read back **out of the class** afterwards, which catches a swap
 * that silently did not land (the class would still hold the old 5-register
 * body with its `.catchall`) and a frame that does not match the injected smali
 * (which only shows up as a `VerifyError` on device otherwise).
 */
private fun replaceWholeBody(
    classDef: MutableClass,
    original: MutableMethod,
    registers: Int,
    body: String,
    usesLabels: Boolean = false
) {
    val replacement = ImmutableMethod(
        original.definingClass,
        original.name,
        emptyList(),
        original.returnType,
        original.accessFlags,
        null,
        null,
        MutableMethodImplementation(registers)
    ).toMutable().apply {
        if (usesLabels) {
            addInstructionsWithLabels(0, body.trimIndent())
        } else {
            addInstructions(0, body.trimIndent())
        }
    }

    val methodCountBefore = classDef.methods.size
    classDef.methods.apply {
        if (!remove(original)) {
            throw PatchException(
                "$PATCH_NAME: could not remove ${original.name} from" +
                    " ${original.definingClass} — the whole-body replacement would silently" +
                    " not apply."
            )
        }
        add(replacement)
    }

    val landed = classDef.methods.firstOrNull {
        it.name == original.name && it.returnType == original.returnType &&
            it.parameterTypes.isEmpty()
    } ?: throw PatchException(
        "$PATCH_NAME: ${original.definingClass}->${original.name} is missing after the" +
            " whole-body replacement."
    )

    if (landed.implementation?.registerCount != registers) {
        throw PatchException(
            "$PATCH_NAME: ${original.definingClass}->${original.name} has .registers" +
                " ${landed.implementation?.registerCount}, expected $registers. The injected" +
                " body addresses registers that do not exist in that frame and would fail dex" +
                " verification on device."
        )
    }

    if (landed.implementation?.tryBlocks?.isNotEmpty() == true) {
        throw PatchException(
            "$PATCH_NAME: ${original.definingClass}->${original.name} still carries a" +
                " try/catch item after the whole-body replacement — the stock handler would" +
                " remain reachable."
        )
    }

    if (classDef.methods.size != methodCountBefore) {
        throw PatchException(
            "$PATCH_NAME: ${original.definingClass} gained or lost methods during the" +
                " replacement of ${original.name}; the patch must not add methods."
        )
    }
}

/**
 * Vita Mahjong — Remove Ads
 * (`com.vitastudio.mahjong` 3.44.1 / versionCode 1927, XAPK).
 *
 * One toggle covering every ad surface, with **whole-method replacement** and
 * **no helper methods**:
 *
 * 1. **Rewarded** (`Lvd/a.show()Lyd/i;`) — emit the reward callbacks in stock
 *    order and return `null`; no video loads and none plays.
 * 2. **Interstitial** (`Lsd/a.show()Lyd/i;`) — emit `onADWillShow` +
 *    `onADClose` and return `null`; no full-screen/app-open ad.
 * 3. **Banners** (`AdModuleBridge.showBanner(…)V`) — `return-void` at entry, so
 *    the banner Runnable is never even constructed.
 * 4. **Gates** (`Lkd/n.o(…)Z` = `isReady`, `Lkd/n.p(String)Z` = `isValid`) —
 *    forced `true` so the C# layer still enables ad-gated buttons when the
 *    placement config is missing or nothing was filled (now the normal state).
 *
 * ## Call chain being cut
 *
 * ```
 * Unity C#
 *   → AdModuleBridge.show
 *     → u.run
 *       → AdModuleBridge.m
 *         → oc/b.r
 *           → kd/g.o
 *             → kd/n.B            (isReady / isValid — both forced true here)
 *               → md/c.show
 *                 → lld/a.show     (→ Lvd/a.show / Lsd/a.show — replaced here)
 * ```
 *
 * The show methods are the last Java frames before the MEeVii adapter actually
 * presents an Activity, so replacing them removes the display without touching
 * loaders, mediation selection or the reward bookkeeping above.
 *
 * ## Why returning `null` from `show()` is safe
 *
 * `null` is the SDK's **own no-fill value**, not an invention: the stock body
 * does `const/4 v1, 0x0` … `if-nez v0, :cond_b` … `return-object v1` when
 * `h()` reports nothing to show, and the catch handler ends with `return-object
 * v1` as well. Every caller (`md/c.show`, `lld/a.show`, the C# bridge) already
 * handles a null show result. The `Lyd/i;` object is a *session handle*, not a
 * signal the game waits on.
 *
 * ## Why the callbacks are still emitted
 *
 * Returning a bare `null` without callbacks leaves the C# layer waiting for the
 * close event and the "watch ad" button stuck in its loading state. The injected
 * bodies replay exactly the listener calls the SDK would have made, in the SDK's
 * order:
 *
 *  * rewarded: `onADWillShow` → `onRewardedVideoCompleted` → `onADClose`
 *  * interstitial: `onADWillShow` → `onADClose`
 *
 * ## Threading
 *
 * Nothing here changes threads. The Unity bridge already posts every ad action
 * through `UnityUtil.runOnUiThread` (`AdModuleBridge.showBanner` does it
 * directly; `AdModuleBridge.show` → `u.run` does the same for the full-screen
 * formats), so `show()` and therefore these fake callbacks always run on the UI
 * thread — the same thread the stock MEeVii SDK would have used. No
 * `Handler`, `runOnUiThread` or `post` is needed in the injected body.
 *
 * ## Deliberately NOT touched (DO NOT list)
 *
 *  * **Do not stub `oc/b.r`** — it is the shared mediation/load dispatcher; a
 *    no-op there breaks non-ad paths that share it.
 *  * **Do not patch `md/c.show`** — it is the format dispatcher shared by
 *    rewarded *and* interstitial; replacing it there loses the per-format
 *    callback ordering.
 *  * **Do not touch `kd/n.B`** — the C#-visible gate entry point; forcing it
 *    directly bypasses placement bookkeeping for every ad type at once.
 *  * **Do not fake `onADLoaded`** — a load event without a real ad object makes
 *    the SDK hand out a `null` session to callers that do not null-check, and
 *    leaves stale entries in the placement cache.
 *  * **Do not add helper methods** (`morpheReward`, `morpheInter`, …) — the
 *    whole point of this rewrite is that neither `Lvd/a;` nor `Lsd/a;` grows a
 *    method, so no `.catch`/dead-code scaffolding is needed.
 *  * **Do not call the SDK's JNI/native ad player directly** — irrelevant here
 *    and unsafe: the native player assumes a loaded ad object.
 */
@Suppress("unused")
val vitaMahjongRemoveAdsPatch = bytecodePatch(
    name = PATCH_NAME,
    description = "Removes banners and interstitials and makes rewarded ads pay out instantly without a video. Ad-gated buttons stay usable even when no ad is filled or the placement config is missing.",
    default = true
) {
    compatibleWith(COMPATIBILITY_VITA_MAHJONG)

    execute {
        // ── Fail loudly rather than ship an APK with a silently skipped hook ──
        RewardedShowFingerprint.matchAllOrNull()
            .requireExactlyOne("Lvd/a;->show()Lyd/i;", PATCH_NAME)
        InterstitialShowFingerprint.matchAllOrNull()
            .requireExactlyOne("Lsd/a;->show()Lyd/i;", PATCH_NAME)
        ShowBannerFingerprint.matchAllOrNull()
            .requireExactlyOne(
                "AdModuleBridge.showBanner(String,int,int,String,boolean,String)V",
                PATCH_NAME
            )
        AdReadyFingerprint.matchAllOrNull()
            .requireExactlyOne("Lkd/n;->o(String,String,String)Z", PATCH_NAME)
        AdValidFingerprint.matchAllOrNull()
            .requireExactlyOne("Lkd/n;->p(String)Z", PATCH_NAME)

        // ══ 1. Rewarded: instant reward, no video ════════════════════════════
        replaceWholeBody(
            classDef = RewardedShowFingerprint.classDef,
            original = RewardedShowFingerprint.method,
            registers = REWARDED_REGISTERS,
            body = REWARDED_BODY,
            usesLabels = true
        )

        // ══ 2. Interstitials: skip, immediately close ════════════════════════
        replaceWholeBody(
            classDef = InterstitialShowFingerprint.classDef,
            original = InterstitialShowFingerprint.method,
            registers = INTERSTITIAL_REGISTERS,
            body = INTERSTITIAL_BODY,
            usesLabels = true
        )

        // ══ 3. Banners: never create/show the banner view ════════════════════
        // `.registers 13`, no catch table, straight-line body → entry return is
        // safe and leaves only unreachable stock code (no handler to enter).
        ShowBannerFingerprint.method.returnEarly()

        // ══ 4. Readiness/validity gates: always true ═════════════════════════
        // o(...)Z has `.registers 7`, p(String)Z has `.registers 5`; both have
        // a spare local, so `returnEarly(true)` needs no frame change.
        AdReadyFingerprint.method.returnEarly(true)
        AdValidFingerprint.method.returnEarly(true)
    }
}
