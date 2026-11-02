package app.fancade.patches.ads

import app.fancade.patches.shared.Constants.COMPATIBILITY_FANCADE
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.all.misc.hex.HexPatchBuilder
import app.morphe.patches.all.misc.hex.hexPatch

/**
 * Fancade 1.15.24 (versionCode 184) — Remove ads & free rewards.
 *
 * ============================================================
 * SHAPE OF THE FEATURE
 * ============================================================
 * Fancade ships an AppLovin MAX ad stack whose whole surface is four thin
 * instance methods on `com.martinmagni.fancade.Fancade` (`classes5.dex`). Each
 * one just forwards to the SDK and then reports the outcome back into the
 * native engine (`libfancade.so`) through `ad_*_on_*` JNI callbacks. The
 * engine — not Java — owns the ad state machine and does the actual grant, so
 * the goal is to make Java hand the engine a *consistent* story:
 *
 *   - interstitials never load and never show;
 *   - rewarded ads report "loaded" without any SDK call, and "show" completes
 *     the rewarded lifecycle synchronously, so the engine pays out instantly.
 *
 * ============================================================
 * PART A — DEX (the four `Fancade` wrappers)
 * ============================================================
 * All four are unobfuscated instance methods with `.registers 4` and none has
 * an exception table, so a plain `addInstructions(0, …)` prepend is safe: the
 * injected body ends in `return*`, and the original body stays behind it as
 * unreachable dead code — exactly how `returnEarly` behaves. Nothing is
 * rebuilt or replaced, so the native `RegisterNatives`/JNI binding and the
 * original register frame are untouched.
 *
 *  1. `ad_interstitial_load_os(Z)V` → `return-void`. No request ever reaches
 *     MAX, so no interstitial can become available.
 *
 *  2. `ad_interstitial_show_os()Z` → `const/4 v0, 0x0 ; return v0`. Reports
 *     "nothing shown". Patching *show* as well as *load* is mandatory: with
 *     `ad_init_os` left alone the MAX objects exist, but a load that never
 *     happens would leave `getInstance` returning null and the original body
 *     NPE on `M`/`P`. Returning early skips the dereference entirely.
 *
 *  3. `ad_rewarded_load_os(Z)V` → tells the engine an ad is loaded, then
 *     returns:
 *         const/4 v0, 0x1
 *         invoke-static {v0}, …->ad_rewarded_on_loaded(Z)V
 *         return-void
 *     The engine's own `ad_rewarded_is_loaded()` gate reads the flag that
 *     `ad_rewarded_on_loaded` sets, so the reward button becomes available
 *     without the MAX SDK ever being asked for a video.
 *
 *  4. `ad_rewarded_show_os()Z` → completes the rewarded lifecycle in order and
 *     returns true (order matters):
 *         ad_rewarded_on_rewarded()            # sets the pending-reward flag
 *         ad_rewarded_on_showed(true, 0.0)     # state 7 -> done
 *         ad_rewarded_on_loaded(true)          # re-arm for the next tap
 *         return true
 *
 * ============================================================
 * WHY PART 4 WORKS (the engine poll)
 * ============================================================
 * `ad_rewarded_show_os` does not grant anything itself — it kicks off a native
 * flow. The native engine polls `ad_rewarded_is_showing()` (true while the
 * state machine sits in state 7) and, once that returns false, reads
 * `ad_rewarded_get_reward()` (the flag at `0x477a7b0`, set by
 * `ad_rewarded_on_rewarded`) and only then runs its own `ad_rewarded_reward()`
 * grant. So the minimal "instant payout" is: set the reward flag, then move
 * the state machine OUT of 7 — which is exactly what
 * `ad_rewarded_on_showed(true, 0.0)` does — and the engine's poll finishes on
 * its own next tick and pays out.
 *
 * `ad_rewarded_on_loaded(true)` afterwards re-arms the gate so the button stays
 * available for the next tap (`ad_rewarded_is_loaded` is also force-true in
 * Part B, but keeping the engine's own flag in sync avoids any UI that reads
 * the callback-driven copy).
 *
 * Register budget: `.registers 4` → v0–v3 locals, `p0` = v3 (these are
 * instance methods, so the receiver takes the last register). The injected
 * bodies use v0–v2 only. `ad_rewarded_on_showed(ZD)V` is `(boolean, double)`,
 * so the invoke list must be `{v0, v1, v2}` — v0 holds the boolean, v1:v2 the
 * wide double. No frame expansion is needed.
 *
 * ============================================================
 * PART B — NATIVE (`lib/arm64-v8a/libfancade.so`)
 * ============================================================
 * The engine is a custom, hand-written C++ engine — NOT IL2CPP, NOT Unity — and
 * it is **not stripped**: every symbol below is exported and visible through
 * `nm -D libfancade.so`, so each site can be re-derived on a future version by
 * name even if the bytes move.
 *
 * WHY `hexPatch` AND NOT `replaceWith`: the library is 45,921,576 B. Embedding
 * it in the `.mpp` would bloat the patch bundle by that much for a handful of
 * bytes. `hexPatch` edits `lib/arm64-v8a/libfancade.so` in place while the APK
 * is being assembled, and it throws a `PatchException` when an anchor is
 * missing — so a stale anchor fails the build instead of shipping an APK whose
 * native half silently does nothing.
 *
 * OFFSETS: the executable LOAD segment starts at offset 0 / vaddr 0, so file
 * offset == vaddr for this binary. All three anchors are 20 B and were
 * byte-scanned against the shipped `.so`; each occurs **exactly once**.
 * Replacements are the same length, so every surrounding instruction stays
 * byte-identical.
 *
 *  1. `ad_rewarded_is_loaded` @0x341bd8 → `mov w0, #1 ; ret`. This is the gate
 *     the UI (`shop_draw` / `draw_action` action ids 12/13) consults before
 *     offering "watch ad for gems", so the reward button is always there and
 *     always tappable.
 *
 *  2. `app_gems_for_ad_available` @0x34a3d0 → `mov w0, #1 ; ret`. Its real body
 *     checks "ad loaded" AND a 24 h cooldown (cmp/branch at 0x34a3d8–0x34a3f4).
 *     Forcing true removes the cooldown, so the gems-for-ad offer is repeatable.
 *
 *  3. `ad_rewarded_show` @0x341c48 → retarget `b.hi bail` into an unconditional
 *     `b 0x341c68`, i.e. always take the "state := 7 + call
 *     `ad_rewarded_show_os`" arm instead of bailing out when the current state
 *     is not the expected one. Branch math: `imm26 = (0x341c68 - 0x341c48) >> 2
 *     = 8`, opcode `0x14000000` → word `0x14000008` → LE bytes `08 00 00 14`.
 *     Only the last word of the anchor changes; llvm-objdump confirms it
 *     disassembles to `b 0x341c68 <ad_rewarded_show+0x60>`, the arm that sets
 *     state 7 and calls into the Java wrapper patched in Part A.
 *
 * Updating for a new version:
 *   `nm -D libfancade.so | rg 'ad_rewarded_is_loaded|app_gems_for_ad_available|
 *    ad_rewarded_show'`, dump the first bytes at each address, re-check the
 *   anchor is unique, and recompute the branch if `ad_rewarded_show` moved.
 *   See `analysis/fancade/possible-patches.md` §3 and §5.3.
 */
private const val LIB = "lib/arm64-v8a/libfancade.so"

private const val FANCADE_CLASS = "Lcom/martinmagni/fancade/Fancade;"

private const val PATCH_NAME = "Remove ads & free rewards"

/**
 * Native ad-decision sites in `lib/arm64-v8a/libfancade.so` (arm64-v8a only —
 * the XAPK ships no other ABI for this library).
 *
 * Every anchor is 20 B and occurs exactly once; each replacement is the same
 * length, so the instruction stream around it is preserved byte for byte.
 */
private val adSites: HexPatchBuilder.() -> Unit = {
    // ad_rewarded_is_loaded @0x341bd8 -> mov w0, #1 ; ret  (UI offers the reward button)
    "FD 7B BF A9 FD 03 00 91 78 27 0B 94 C8 21 02 B0 1F 00 00 72" asPatternTo
        "20 00 80 52 C0 03 5F D6 FD 03 00 91 C8 21 02 B0 1F 00 00 72" inFile LIB

    // app_gems_for_ad_available @0x34a3d0 -> mov w0, #1 ; ret  (no 24 h cooldown on gems-for-ad)
    "FD 7B BF A9 FD 03 00 91 62 10 0B 94 E8 03 00 2A E0 03 1F 2A" asPatternTo
        "20 00 80 52 C0 03 5F D6 62 10 0B 94 E8 03 00 2A E0 03 1F 2A" inFile LIB

    // ad_rewarded_show @0x341c48: b.hi bail -> b 0x341c68 (set state 7 + call ad_rewarded_show_os)
    "D3 21 02 B0 68 AE 47 B9 08 15 00 51 1F 05 00 71 68 07 00 54" asPatternTo
        "D3 21 02 B0 68 AE 47 B9 08 15 00 51 1F 05 00 71 08 00 00 14" inFile LIB
}

/**
 * Looks up one of the `Fancade` ad wrappers by its exact, unobfuscated
 * descriptor and throws [PatchException] if it is gone.
 *
 * Looked up by name + parameter types + return type (never by index and never
 * by obfuscated name — there are none in this class) so a version bump that
 * changes the wrapper surface fails the build loudly instead of patching the
 * wrong overload.
 */
private fun MutableClass.requireAdMethod(
    name: String,
    parameterTypes: List<String>,
    returnType: String
): MutableMethod = methods.firstOrNull {
    it.name == name &&
        it.parameterTypes.map(CharSequence::toString) == parameterTypes &&
        it.returnType == returnType
} ?: throw PatchException(
    "$PATCH_NAME: $name(${parameterTypes.joinToString("")})$returnType is no longer present in" +
        " $type — unsupported Fancade version?"
)

/**
 * Fancade — interstitial removal plus instant rewarded payouts.
 *
 * The two halves are one listing entry: `dependsOn(hexPatch(…))` runs the
 * native edits first (they only touch `lib/arm64-v8a/libfancade.so`), then the
 * DEX hooks run (they only touch `classes5.dex`). The artifacts are disjoint,
 * so their relative order is irrelevant, but the native gate patches must be
 * present for the DEX half to be reachable from the UI at all.
 */
@Suppress("unused")
val fancadeNoAdsFreeRewardsPatch = bytecodePatch(
    name = "Remove ads & free rewards",
    description = "Interstitial ads no longer show, and reward ads pay out instantly — " +
        "tap the reward button and the coins/gems are granted without watching a video. " +
        "The \"watch ad for gems\" button is always available.",
    default = true
) {
    compatibleWith(COMPATIBILITY_FANCADE)

    dependsOn(hexPatch(block = adSites))

    execute {
        val fancade = mutableClassDefBy(FANCADE_CLASS)

        // ── 1. Interstitials never load ──────────────────────────────────────
        fancade.requireAdMethod(
            name = "ad_interstitial_load_os",
            parameterTypes = listOf("Z"),
            returnType = "V"
        ).addInstructions(
            0,
            """
            return-void
            """.trimIndent()
        )

        // ── 2. Interstitials never show (and never dereference the null ad) ──
        fancade.requireAdMethod(
            name = "ad_interstitial_show_os",
            parameterTypes = emptyList(),
            returnType = "Z"
        ).addInstructions(
            0,
            """
            const/4 v0, 0x0
            return v0
            """.trimIndent()
        )

        // ── 3. Rewarded load: just tell the engine an ad is loaded ───────────
        fancade.requireAdMethod(
            name = "ad_rewarded_load_os",
            parameterTypes = listOf("Z"),
            returnType = "V"
        ).addInstructions(
            0,
            """
            const/4 v0, 0x1
            invoke-static {v0}, $FANCADE_CLASS->ad_rewarded_on_loaded(Z)V
            return-void
            """.trimIndent()
        )

        // ── 4. Rewarded show: complete the lifecycle synchronously ───────────
        // Order is load-bearing — see the KDoc "WHY PART 4 WORKS". The engine
        // polls is_showing() (state 7), then get_reward() (flag set by
        // on_rewarded), then grants; on_showed(true, 0.0) is what leaves 7.
        val rewardedShow = fancade.requireAdMethod(
            name = "ad_rewarded_show_os",
            parameterTypes = emptyList(),
            returnType = "Z"
        )

        // The injected body writes v0 (boolean) and v1:v2 (wide double). The
        // frame is `.registers 4` in 1.15.24; a version that shrank it would
        // fail dex verification on device, so assert it here instead.
        val registerCount = rewardedShow.implementation?.registerCount ?: 0
        if (registerCount < 4) {
            throw PatchException(
                "$PATCH_NAME: ad_rewarded_show_os()Z has .registers $registerCount," +
                    " expected at least 4 — the injected body addresses v0–v2 and would fail" +
                    " dex verification on device. Unsupported Fancade version?"
            )
        }

        rewardedShow.addInstructions(
            0,
            """
            invoke-static {}, $FANCADE_CLASS->ad_rewarded_on_rewarded()V
            const/4 v0, 0x1
            const-wide/16 v1, 0x0
            invoke-static {v0, v1, v2}, $FANCADE_CLASS->ad_rewarded_on_showed(ZD)V
            const/4 v0, 0x1
            invoke-static {v0}, $FANCADE_CLASS->ad_rewarded_on_loaded(Z)V
            const/4 v0, 0x1
            return v0
            """.trimIndent()
        )
    }
}
