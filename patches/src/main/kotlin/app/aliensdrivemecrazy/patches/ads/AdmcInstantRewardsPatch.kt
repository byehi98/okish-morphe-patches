package app.aliensdrivemecrazy.patches.ads

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.rawResourcePatch
import app.aliensdrivemecrazy.patches.shared.Constants.COMPATIBILITY_ADMC
import kotlin.io.readBytes
import kotlin.io.writeBytes

/**
 * Aliens Drive Me Crazy — Instant rewards (native il2cpp, 3.2.10-only)
 *
 * Reward buttons (btnAdmoRew.OnClick, BtnWatchAds.OnClick) call
 * MyIronSource.ShowRewardAds(MyIronSource.RewardType Ktora)  (VA 0x1846C0C):
 *
 *   - availability gate: tbz w0,#0,skip (skips if no ad loaded)
 *   - MinutyOdOstReklamy = CoIleMinutReklama   (+0x28 <- +0x2C)
 *   - pre-ad block: icall + float timeScale math  (pauses/rewinds game)
 *   - IronSource.showRewardedVideo() → the actual ad
 *   - ad SDK callback → RewardedVideoAdRewardedEvent  (VA 0x1842744):
 *       ldr w8,[x19,#0x24]  (JakaNagroda)  0 -> NagrodaRevive coroutine (0x1846DEC)
 *                                          1 -> NagrodaDubleCoins coroutine (0x1846E44)
 *       then tail-calls StartCoroutine on the calling (main) thread.
 *
 * Patch: overwrite the first 2 instructions of ShowRewardAds with a tail-call:
 *
 *   str w1, [x0, #0x24]   ; JakaNagroda = Ktora (the reward button that was pressed)
 *   b   RewardedVideoAdRewardedEvent   ; grant instantly, no ad
 *
 * This is deliberately an entry *tail-branch* (not a mid-method BL):
 *   - x0 (this) passes through untouched — BL-ing the dispatcher without
 *     a valid `this` crashes (SIGSEGV).
 *   - No BL → x30 never clobbered → the dispatcher returns straight to
 *     ShowRewardAds' caller. A mid-method BL + RET self-loops:
 *     the dispatcher's epilogue restores x30 = the RET's own address.
 *   - No stack push/pop games: the dispatcher's own prologue/epilogue stay balanced.
 *   - Skips the availability gate, the timer update, the pre-ad icall/float block
 *     (which pauses timeScale, so the reward coroutine could never run → soft-lock)
 *     and the ad itself.
 *
 * w1 mapping verified at both callers: revive button does `mov w1, wzr`,
 * double-coins button does `mov w1, #1`.
 *
 * Anchor = the first 8 words of ShowRewardAds (32 bytes), 3.2.10-only:
 *   VA 0x1846C0C, file 0x1842C0C, versionCode 50 (1 hit, byte-verified).
 * The similar RewardedVideoAdShowFailedEvent prologue differs
 * (adrp x20 / mov x19,x1) and does not match this anchor.
 */
@Suppress("unused")
val admcInstantRewardsPatch = rawResourcePatch(
    name = "Instant rewards",
    description = "Reward buttons (free revive, double coins) pay out instantly without playing any ad video (native libil2cpp.so patch).",
    default = true
) {
    compatibleWith(COMPATIBILITY_ADMC)

    execute {
        val soFile = get("lib/arm64-v8a/libil2cpp.so", true)
        val bytes = soFile.readBytes()

        // 3.2.10-only anchor: MyIronSource$$ShowRewardAds entry.
        //   VA 0x1846C0C, file 0x1842C0C (1 hit, byte-verified):
        //     str x30,[sp,#-0x30]! ; stp x22,x21,[sp,#0x10] ; stp x20,x19,[sp,#0x20]
        //     adrp x22,#0x35A0000  ; adrp x21,#0x3368000   ; mov w19,w1
        //     ldrb w8,[x22,#0x6ff] ; ldr x21,[x21,#0xbe8]
        // Replacement overwrites words 0-1 (entry): str lands at word 0, b lands at
        // word 1 — exactly the VA the branch word was computed for
        // (b @ 0x1846C10 -> dispatcher 0x1842744, delta -0x44CC, imm26 -4403,
        // round-trip verified).
        // (imm26 = (dispatcher - branchSite) >> 2; str w1,[x0,#0x24] keeps
        // JakaNagroda = Ktora, field 0x24 in the dump.)
        val anchor = hex("FE0F1DF8F65701A9F44F02A9D6EA00D015D900D0F303012AC8125739B55641F9")
        val replacement = hex("012400B9CDEEFF17")

        val idx = indexOfPattern(bytes, anchor)
        if (idx < 0) {
            throw PatchException("ADMC Instant rewards: ShowRewardAds entry anchor not found - unsupported game version?")
        }
        // Overwrite words 0-1 (entry): str lands at word 0, b lands at word 1.
        replacement.copyInto(bytes, idx, 0, replacement.size)
        println("ADMC Instant rewards (3.2.10): ShowRewardAds -> tail-call dispatcher at file offset 0x" + idx.toString(16))

        soFile.writeBytes(bytes)
    }
}

/** Parses a big-endian hex string (spaces optional) into a byte array. */
private fun hex(s: String): ByteArray =
    s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

private fun indexOfPattern(haystack: ByteArray, needle: ByteArray): Int {
    if (needle.isEmpty()) return 0
    val last = haystack.size - needle.size
    var i = 0
    while (i <= last) {
        var match = true
        for (j in needle.indices) {
            if (haystack[i + j] != needle[j]) {
                match = false
                break
            }
        }
        if (match) return i
        i++
    }
    return -1
}
