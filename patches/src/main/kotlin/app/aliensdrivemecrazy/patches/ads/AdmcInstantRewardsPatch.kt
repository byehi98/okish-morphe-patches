package app.aliensdrivemecrazy.patches.ads

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.rawResourcePatch
import app.aliensdrivemecrazy.patches.shared.Constants.COMPATIBILITY_ADMC
import kotlin.io.readBytes
import kotlin.io.writeBytes

/**
 * Aliens Drive Me Crazy — Instant rewards (native il2cpp)
 *
 * Reward buttons (btnAdmoRew.OnClick @ 0x166CDF0, BtnWatchAds.OnClick @ 0x166E83C) call
 * MyIronSource.ShowRewardAds(MyIronSource.RewardType Ktora)  (VA 0x183EA68):
 *
 *   - availability gate: tbz w0,#0,skip (skips if no ad loaded)
 *   - MinutyOdOstReklamy = CoIleMinutReklama   (+0x28 <- +0x2C)
 *   - pre-ad block: icall bl #0x16FD044 (w1=1) + float timeScale math  (pauses/rewinds game)
 *   - IronSource.showRewardedVideo() → the actual ad
 *   - ad SDK callback → RewardedVideoAdRewardedEvent  (VA 0x183B10C):
 *       ldr w8,[x19,#0x24]  (JakaNagroda)  0 -> NagrodaRevive coroutine (0x183EC48)
 *                                          1 -> NagrodaDubleCoins coroutine (0x183ECA0)
 *       then tail-calls StartCoroutine on the calling (main) thread.
 *
 * Patch (v3): overwrite the first 2 instructions of ShowRewardAds with a tail-call:
 *
 *   str w1, [x0, #0x24]   ; JakaNagroda = Ktora (the reward button that was pressed)
 *   b   RewardedVideoAdRewardedEvent   ; grant instantly, no ad
 *
 * This is deliberately an entry *tail-branch* (not a mid-method BL):
 *   - x0 (this) passes through untouched — v2 crashed (SIGSEGV 0x25) by BL-ing the
 *     dispatcher without a valid `this`.
 *   - No BL → x30 never clobbered → the dispatcher returns straight to
 *     ShowRewardAds' caller. A mid-method BL + RET self-looped (v2 bug #2):
 *     the dispatcher's epilogue restores x30 = the RET's own address.
 *   - No stack push/pop games: the dispatcher's own prologue/epilogue stay balanced.
 *   - Skips the availability gate, the timer update, the pre-ad icall/float block
 *     (v2 would still have run it — if it pauses timeScale, the reward coroutine
 *     could never run → soft-lock) and the ad itself.
 *
 * w1 mapping verified at both callers: revive button does `mov w1, wzr`,
 * double-coins button does `mov w1, #1`.
 *
 * Anchor = the first 8 words of ShowRewardAds (32 bytes), verified to occur exactly
 * once in the whole 56 MB library (3.2.7 / versionCode 46); the similar
 * RewardedVideoAdShowFailedEvent prologue differs (adrp x20 / mov x19,x1) and
 * does not match.
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

        // Anchor: MyIronSource$$ShowRewardAds entry (VA 0x183EA68, file 0x183AA68)
        //   str x30,[sp,#-0x30]! ; stp x22,x21,[sp,#0x10] ; stp x20,x19,[sp,#0x20]
        //   adrp x22,#0x3595000  ; adrp x21,#0x335d000   ; mov w19,w1
        //   ldrb w8,[x22,#0x6ff] ; ldr x21,[x21,#0xbe8]
        val anchor = hex("FE0F1DF8F65701A9F44F02A9B6EA00F0F5D800F0F303012AC8FE5B39B5F645F9")
        val idx = indexOfPattern(bytes, anchor)
        if (idx < 0) {
            throw PatchException("ADMC Instant rewards: ShowRewardAds entry anchor not found - unsupported game version?")
        }

        // Replacement (8 bytes overwriting words 1-2):
        //   012400B9  str w1, [x0, #0x24]      ; JakaNagroda = Ktora
        //   A8F1FF17  b   0x183B10C            ; tail-branch to RewardedVideoAdRewardedEvent
        //     (imm26 = (0x183B10C - 0x183EA6C) >> 2 = -0x14D8, round-trip verified)
        val replacement = hex("012400B9A8F1FF17")
        replacement.copyInto(bytes, idx, 0, replacement.size)
        println(
            "ADMC Instant rewards: ShowRewardAds -> tail-call dispatcher at file offset 0x" +
                idx.toString(16) + " (expected 0x183aa68)"
        )

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
