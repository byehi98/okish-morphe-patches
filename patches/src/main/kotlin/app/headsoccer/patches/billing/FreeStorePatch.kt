package app.headsoccer.patches.billing

import app.headsoccer.patches.shared.Constants.COMPATIBILITY_HEAD_SOCCER
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.returnEarly

/**
 * Head Soccer 7.1.6 · **Free Store** — every shop purchase grants instantly.
 *
 * cocos2d-x (`com.dnddream.headsoccer.android`, versionCode 345). **DEX-only — no native edit
 * at all.** Everything below lives in the single 7.5 MB `classes.dex`; `libgame.so` is left
 * byte-identical.
 *
 * ============================================================================
 * WHAT IS FORCED
 * ============================================================================
 * `headsoccer.onBuyPoint(int)V` — smali `headsoccer.smali:4771`, `.registers 8` — has its body
 * replaced. It is the **only** funnel every store row reaches:
 *
 * ```
 * native Store::Buy / Store::BuyCharacter / Store::BuyPresnet        (libgame.so)
 *   → headsoccer.JavaSelectItemFunc(int)                             JNI, static, headsoccer.smali:780
 *     → te.handler.post(headsoccer$10)  ─────────────────────────────► GL thread
 *       → headsoccer$10.run()  → te.onBuyPoint(int)                  headsoccer$10.smali:48
 * ```
 *
 * The replacement reproduces the game's own grant chain instead of launching Play Billing:
 *
 * ```
 * headsoccer.billingCompleteIAP(int)              // points + presents   → headsoccer$45 → headsoccer$45$1
 * headsoccer.billingCompleteIAPNonConsumable(ii)  // the 4 characters    → headsoccer$44 → headsoccer$44$1
 *   └─ both end at  access$29 → headsoccer.PurchasedFunc(int)   (private native, headsoccer.smali:835)
 *      → native Store::Purchased() → MenuLayer::SaveMyPoint() / SelectLayer::UnlockCharacter()
 *        → SavePlayerUnlock()   ← writes the game's OWN save file
 * ```
 *
 * `PurchasedFunc` is the same native entry the real purchase path calls, so the unlock lands in
 * exactly the state a genuine purchase produces — **and it persists across restarts**, because
 * that state *is* the native save file. There is no Java-side "isPremium" flag that a restart
 * could reset.
 *
 * ============================================================================
 * THE INDEX MAPPING IS NOT OPTIONAL — read this before "simplifying"
 * ============================================================================
 * The shipped method is an 8-way `if` chain that translates native's tag into a `MySku` ordinal
 * **and then hands the resulting SKU string to Play.** Native can only pass `0…7, 11, 12, 31,
 * 32, 33`; the `31/32/33` tags are remapped to ordinals `8/9/10` (PRESENT1-3) *inside* the method.
 * The injection below reproduces that mapping verbatim, then grants:
 *
 * | native tag `p1` | `MySku` ordinal | enum | grant entry point |
 * |---|---|---|---|
 * | `0…5`   | `p1`    | POINT1-6          | `billingCompleteIAP` |
 * | `6`     | `6`     | CHAR_DEVIL        | `billingCompleteIAPNonConsumable` |
 * | `7`     | `7`     | CHAR_MONK         | `billingCompleteIAPNonConsumable` |
 * | `11`    | `11`    | CHAR_HENOS        | `billingCompleteIAPNonConsumable` |
 * | `12`    | `12`    | CHAR_NORTHKOREA   | `billingCompleteIAPNonConsumable` |
 * | `31`    | `8`     | PRESENT1          | `billingCompleteIAP` |
 * | `32`    | `9`     | PRESENT2          | `billingCompleteIAP` |
 * | `33`    | `10`    | PRESENT3          | `billingCompleteIAP` |
 * | anything else | — | (unreachable) | **returns immediately — no grant** |
 *
 * **Do NOT collapse this to a single unconditional call.** `targets.md` T-01 suggests a uniform
 * `billingCompleteIAPNonConsumable(i, 0)` because its second parameter `p1`/`iType` is declared
 * and never read (verified: `headsoccer$44.<init>(I)V` stores only `val$iIndex`). That observation
 * is **correct but misleading** — the two entry points are NOT interchangeable in the other
 * direction:
 *
 * * `headsoccer$44$1.run()` (behind `…NonConsumable`) compares the resolved SKU against
 *   **CHAR_DEVIL / CHAR_MONK / CHAR_HENOS / CHAR_NORTHKOREA only**, and falls through to
 *   `return-void` when none matches — so handing it a point pack grants **nothing**.
 * * `headsoccer$45$1.run()` (behind `billingCompleteIAP`) matches POINT1-6 + PRESENT1-3 and
 *   likewise falls through for the characters — handing it a character grants **nothing**.
 *
 * Hence the patch **dispatches on `MySku.isNonConsumableType(int)`** (smali `MySku.smali:125`,
 * which consults the `NonConsumableSKUs` set the app itself builds in `<clinit>` from those same
 * four constants) instead of hardcoding a second copy of the character list. That keeps the
 * consumable/non-consumable split correct even if the developer ever reorders or adds a SKU, and
 * it is the same predicate `MyBillingImpl` uses to decide which entry point a real purchase takes.
 *
 * The bounds guard is deliberate: native passes tags, not ordinals, and `31/32/33` are
 * out-of-range for `getSkuSize()` (13). Any tag outside the table returns without granting rather
 * than throwing `IndexOutOfBoundsException` inside `MySku.SKUs.get()`.
 *
 * ============================================================================
 * PLAY BILLING AND RECEIPT VERIFICATION ARE BYPASSED ENTIRETELY
 * ============================================================================
 * Nothing downstream of the old `launchBillingFlow` is reached, so this patch is independent of
 * the DEX billing internals, the `billing-8.0.0` obfuscated `zz*` names, `MySku`'s
 * consumable/non-consumable split as configured in the Play Console, the Play app being
 * installed, a Google account, and the network.
 *
 * The companion `verifyPurchase → true` hook (T-02) is therefore **redundant for this flow** and
 * is kept only as a safety net for the app's *own* purchase-restoration path
 * (`refreshPurchasesAsync()` → `processPurchaseList` → `isSignatureValid`), which still runs on
 * every launch and would otherwise log `"Invalid signature on purchase"` for anything Play hands
 * back. It costs three instructions and cannot affect the grant path above.
 *
 * There is **no verification to defeat** on the grant path: `PurchasedFunc` is native and takes
 * only an int — no receipt, no signature, no server call.
 *
 * ============================================================================
 * REGISTER BUDGET & INJECTION SAFETY
 * ============================================================================
 * `onBuyPoint` has `.registers 8` and **no exception table** (verified: zero `.catch` entries in
 * smali 4771-4935). Locals `v0…v5` are free; `p0` (this `headsoccer`) and `p1` (the native tag)
 * are read-only inputs. The injected block uses `v0` (ordinal) and `v1` (boolean/scratch) only —
 * comfortably inside the 4-bit `35c` range.
 *
 * With no catches, morphe's instruction-tree writer merges labeled blocks intact. (The
 * BurritoBison/Missiles "labeled injection truncates a catch-tabled method" caveat — documented in
 * `CrossyRoadFreeStorePatch.kt` — does not apply.) The original 8-branch body stays in place below
 * the injected `return-void` as unreachable dead code, which is the standard short-circuit shape.
 *
 * `onBuyPoint` already arrives on the **GL thread** (via `te.handler.post(headsoccer$10)`), and
 * both grant entry points re-dispatch through `te.mGLView.queueEvent(...)` + `handler.post(...)`
 * internally — so calling them from here preserves the game's own threading exactly. No extra
 * queueing is added (the HillClimb patch's warning about re-queueing applies only when the target
 * is already the engine's JNI entry; this one is not).
 *
 * ============================================================================
 * FINGERPRINT ROBUSTNESS — and a deliberate fallback ladder
 * ============================================================================
 * `OnBuyPointFingerprint` pins the two app-private names `MySku.getSkuByIndex` +
 * `MyBillingImpl.launchBillingFlow`. They are debug-style names the app has shipped with for
 * years (it has never been ProGuarded — see `TAG = "Headsoccer-Billing4"`), but they are *app*
 * names and therefore not guaranteed the way third-party names are.
 *
 * If a future build renames them the fingerprint fails **loudly and writes nothing**, which is the
 * intended failure mode. When re-deriving, the version-independent anchor is the constant prologue
 * `const/16 v5, 0xc` + `const/16 v4, 0xb` + `const/4 v3, 0x7` + `const/4 v1, 0x6` + `const/4 v2,
 * 0x0` (smali 4776-4784) — that five-constant sequence exists in exactly one method in the APK.
 *
 * `VerifyPurchaseFingerprint` is built on `android.text.TextUtils.isEmpty` (never obfuscated)
 * plus two same-class calls that co-occur in `verifyPurchase` and nowhere else. The
 * `string("IABUtil/Security")` alternative was rejected because that literal also occurs in
 * `generatePublicKey` and `verify`.
 *
 * ============================================================================
 * CAVEAT — read this before reporting a bug
 * ============================================================================
 * * This is effectively **"first tap of any not-yet-owned offer ⇒ owned"**, exactly like Head
 *   Basketball's N2. Rows the game already considers owned are blocked by the game's OWN UI logic
 *   (`SelectLayer::EnableButton` / `Store::clickButton` grey the buttons), not by anything bypassed
 *   here — re-buying an owned offer is a no-op in the game's own logic.
 * * Native gates `Store::Buy*` on `m_bEnableBuy` (`Store+0x1C1`), set by `Store::EnableBuy(true)`
 *   from `Store::setPrice` and cleared by `Store::CheckPrice` / `Store::StartPurchased`. That is
 *   **native UI state, not an entitlement check**. If the store button never appears on a device
 *   with no Play Store (so prices were never fetched), the cause is that gate, not this patch —
 *   `Store::EnableBuy → always true` is the optional follow-up (targets.md T-05), reachable only
 *   via a native edit because the method has no Java caller.
 * * Rows that additionally gate on `Store::IsEnoughPoint` show affordability from the real balance;
 *   a currency patch would be needed to turn those green too. Out of scope here.
 *
 * ============================================================================
 * DELIVERY
 * ============================================================================
 * One `bytecodePatch` val, `default = true`, independently selectable. Pure DEX — **no
 * `rawResourcePatch`, no `dependsOn`, no `ResourceMode.RAW_ONLY`**, so `libgame.so` is never even
 * extracted. `listApkEntries("lib/")` is irrelevant to this patch (and would return empty for
 * DEFLATE-compressed libs anyway — the Crossy Road trap).
 *
 * Disjoint from `../ads/FreeAdRewardsPatch.kt`, which touches `ShowAds()` and the same
 * `headsoccer` class: **different methods, no shared offsets**, so enabling both is safe and
 * order-independent.
 *
 * ============================================================================
 * DO NOT
 * ============================================================================
 * * **Never bump `versionCode` (345).** The OBB is named
 *   `main.342.com.dnddream.headsoccer.android.obb` — cocos2d-x resolves the expansion as
 *   `%s/main.%d.%s.obb`, so a bump makes the OBB unfindable and the game unplayable on any device
 *   holding the official OBB.
 * * Do **not** replace the whole mapping with one unconditional `billingCompleteIAP*` call — see
 *   "THE INDEX MAPPING IS NOT OPTIONAL". The two grant entry points silently no-op for the wrong
 *   SKU class, so this produces a patch that appears applied and grants only some items.
 * * Do **not** stub `headsoccer$44.run()` / `headsoccer$45.run()` themselves. They are the grant
 *   funnels: `run()` → `handler.post($44$1/$45$1)` → `access$29` → `PurchasedFunc`. Stubbing them
 *   deletes the entire purchase pipeline. (Head Basketball's `FreeStorePatch.kt` KDoc records the
 *   same trap for its `CompleteIAP`.)
 * * Do **not** stub `PurchasedFunc` or `Store::Purchased` — that IS the grant, and it is what makes
 *   the unlock persist.
 * * Do not touch `assets/billing.properties` (50 B: `version=8.0.0 / client=billing /
 *   billing_client=8.0.0`). It is the Play Billing AAR build marker and must keep matching the
 *   manifest's `play.billingclient.version` meta-data, or `startConnection` fails for the app's
 *   own restore path.
 * * Do not delete `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE` or the
 *   `SampleDownloaderService` / `SampleAlarmReceiver` components — the OBB fetcher needs them.
 * * Do not attempt a Play-Billing-level forge (`BillingClientImpl.launchBillingFlow` /
 *   `acknowledgePurchase` / `consumeAsync` / `queryProductDetailsAsync`, the Crossy Road shape).
 *   It is pure overhead here: the grant is fully reachable from `onBuyPoint` without ever creating
 *   a `BillingClient`, and faking a catalog would additionally need `acknowledge`/`consume` to keep
 *   the real service from rejecting the fake token.
 *
 * Measured anchors: `analysis/head-soccer/notes/targets.md` §T-01, §T-02, §T-07 and
 * `analysis/head-soccer/notes/purchase-flow.md` §1.
 */
@Suppress("unused")
val headSoccerFreeStorePatch = bytecodePatch(
    name = "Free Store",
    description = "Everything in the shop is free. Tap an item and you get it right away — " +
        "points, characters and presents — with no Google Play payment screen and nothing charged.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_HEAD_SOCCER)

    execute {
        // ── Primary: intercept the store tap and grant through the game's own
        //    purchase-complete funnels, reproducing the shipped tag→ordinal map. ──
        OnBuyPointFingerprint.method.addInstructions(0, """
            # p0 = this (headsoccer), p1 = native tag (0…7, 11, 12, 31, 32, 33)
            # v0 = MySku ordinal, v1 = scratch
            const/4 v0, 0x0

            # 31 → 8 (PRESENT1)
            const/16 v1, 0x1f
            if-ne p1, v1, :hs_not_31
            const/16 v0, 0x8
            goto :hs_dispatch

            # 32 → 9 (PRESENT2)
            :hs_not_31
            const/16 v1, 0x20
            if-ne p1, v1, :hs_not_32
            const/16 v0, 0x9
            goto :hs_dispatch

            # 33 → 10 (PRESENT3)
            :hs_not_32
            const/16 v1, 0x21
            if-ne p1, v1, :hs_plain
            const/16 v0, 0xa
            goto :hs_dispatch

            # 0…7, 11, 12 already are their own ordinal
            :hs_plain
            move v0, p1

            # Reject anything outside the shipped tag table rather than letting
            # MySku.getSkuByIndex() throw IndexOutOfBoundsException.
            const/16 v1, 0xd
            if-ge v0, v1, :hs_done
            if-ltz v0, :hs_done

            # Consumable (points, presents) → billingCompleteIAP
            # Non-consumable (the 4 characters) → billingCompleteIAPNonConsumable
            # The predicate is the app's OWN split, not a second hardcoded copy of it.
            :hs_dispatch
            invoke-static {v0}, Lcom/dnddream/headsoccer/android/MySku;->isNonConsumableType(I)Z
            move-result v1
            if-eqz v1, :hs_consumable

            # iType (2nd arg) is declared and never read by headsoccer$44.<init>(I)V,
            # so 0 is the correct "unknown type" value here.
            const/4 v1, 0x0
            invoke-static {v0, v1}, Lcom/dnddream/headsoccer/android/headsoccer;->billingCompleteIAPNonConsumable(II)V
            goto :hs_done

            :hs_consumable
            invoke-static {v0}, Lcom/dnddream/headsoccer/android/headsoccer;->billingCompleteIAP(I)V

            :hs_done
            return-void
            nop
        """.trimIndent())

        // ── Companion (T-02): the legacy LVL RSA/SHA1withRSA receipt check.
        //    Redundant for the grant path above (which never reaches Play Billing)
        //    but keeps the app's own per-launch restore from logging a failure.
        //    The embedded key is the Play Console licence key, NOT the APK cert,
        //    so forcing the return value is the only correct bypass.
        VerifyPurchaseFingerprint.method.returnEarly(true)
    }
}