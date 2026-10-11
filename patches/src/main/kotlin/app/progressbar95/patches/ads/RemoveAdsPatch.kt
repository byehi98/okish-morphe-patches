package app.progressbar95.patches.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.progressbar95.patches.shared.Constants.COMPATIBILITY_PROGRESSBAR95

/**
 * Progressbar95 1.1110 · **Remove Ads & Free Rewards** (hunter T5+T6 combined).
 *
 * T5 and T6 touch the SAME method (`Show.invoke`), so they ship as ONE patch —
 * two separate patches stubbing the same entry would be order-dependent (one
 * unconditional return would silently shadow the other). Single insert per
 * method, no cross-patch ordering hazard:
 *
 *  1. `Show.invoke` @0 — dispatch `{phase="userReceivedReward",
 *     type="rewardedVideo"}` through the game's own Lua event path, then
 *     `return 0` before any ad object is touched. The map build mirrors
 *     `MaxActivityReward.onUserRewarded` verbatim (plagueinc
 *     RewardedVideoBypass pattern): same `HashMap` + `put("phase", …)` +
 *     `put("type", …)` + `-$$Nest$mdispatchLuaEvent` sequence, with the
 *     `adType` field read replaced by the constant the Lua store manager waits
 *     for. Every show request therefore grants the reward AND displays
 *     nothing — rewarded taps complete instantly, interstitial breaks become
 *     silent no-ops (a spurious reward event on a non-rewarded break is a
 *     harmless over-grant, never a crash: Lua just sets a flag).
 *  2. `IsLoaded.invoke` @0 — `return 0`, so Lua treats ads as unavailable and
 *     skips ad breaks gracefully instead of queueing them.
 *
 * Register budget: `Show.invoke` has `.registers 19`, so `p0` (= v17)
 * is OUTSIDE the v0–v15 window of non-range instructions — a plain
 * `iget-object v0, p0, …` assembles to an illegal register reference
 * (CLI: `Invalid register: v17`) and trips the verifier at runtime
 * (`VerifyError: … register v0 has type Undefined but expected
 * Reference: LuaLoader`). The first injected instruction is therefore a
 * `move-object/from16` copy of `p0` into low `v0`; everything after that
 * touches only v0..v3, which the original body writes before any read.
 * `IsLoaded.invoke` has `.registers 9` (p0 = v7, addressable) and its
 * insert uses only v0 anyway. Both inserts run before the original
 * bodies, which then become unreachable dead code (standard
 * short-circuit shape).
 *
 * Cross-version note: `-$$Nest$mdispatchLuaEvent` is a compiler-generated nest
 * accessor — stable for 1.1110 (verified in `MaxActivityReward.smali`) but
 * re-derive it from `onUserRewarded` if a future build renames it. The
 * fingerprints themselves anchor only on stable Lua signature strings.
 */
@Suppress("unused")
val progressbar95RemoveAdsPatch = bytecodePatch(
    name = "No Ads + Free Rewards",
    description = "Blocks all ads and gives reward-ad gifts instantly, no video needed.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_PROGRESSBAR95)

    execute {
        // T6+T5: grant the reward through the game's own event path, then
        // return 0 values before any ad is loaded or shown. NOTE: p0 must be
        // copied with /from16 first — Show.invoke has .registers 19 so p0 is
        // v17, illegal in a non-range iget (VerifyError fix).
        ApplovinShowFingerprint.method.addInstructions(0, """
            move-object/from16 v0, p0
            iget-object v0, v0, Lplugin/applovinMax/LuaLoader${'$'}Show;->this${'$'}0:Lplugin/applovinMax/LuaLoader;
            new-instance v1, Ljava/util/HashMap;
            invoke-direct {v1}, Ljava/util/HashMap;-><init>()V
            const-string v2, "phase"
            const-string v3, "userReceivedReward"
            invoke-interface {v1, v2, v3}, Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
            const-string v2, "type"
            const-string v3, "rewardedVideo"
            invoke-interface {v1, v2, v3}, Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
            invoke-static {v0, v1}, Lplugin/applovinMax/LuaLoader;->-${'$'}${'$'}Nest${'$'}mdispatchLuaEvent(Lplugin/applovinMax/LuaLoader;Ljava/util/Map;)V
            const/4 v0, 0x0
            return v0
        """.trimIndent())

        // T5: ads report as never loaded — Lua skips ad breaks gracefully.
        ApplovinIsLoadedFingerprint.method.addInstructions(0, """
            const/4 v0, 0x0
            return v0
        """.trimIndent())
    }
}
