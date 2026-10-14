package app.crashofcars.patches.antihack

import app.crashofcars.patches.shared.Constants.COMPATIBILITY_CRASH_OF_CARS
import app.morphe.patcher.Match
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch

/**
 * The class-definition CRC that `libil2cpp.so` was built against.
 *
 * Read straight off the device log
 * (`analysis/com.notdoppler.crashofcars/notes/deployment-waydroid.md`):
 * `Expected class definition crc 478359669`, and `478359669 == 0x1C833075`.
 */
private const val EXPECTED_CLASS_DEFINITION_CRC = 478359669L // 0x1C833075

/**
 * Crash of Cars — Anti-hacking CRC pin (`com.notdoppler.CRCTool`)
 * (com.notdoppler.crashofcars v1.9.34 / versionCode 1090034)
 *
 * Crash of Cars is a Unity IL2CPP game with a native tamper check. The C# side
 * calls a plain Java static,
 * [`GetClassDefinitionCRC`](get), which CRCs `classes.dex` out of the installed
 * APK; the native `AntiHackingCheck.get_HasCodeBeenModified()` compares that
 * against a constant baked into `libil2cpp.so` and, on a mismatch, logs
 * *"Anti-hacking check failed, CRC not match."* and sets
 * `Application.genuine = false`.
 *
 * ```
 * E Unity: Anti-hacking check failed, CRC not match.
 * E Unity: Expected class definition crc 478359669, actual value 958176894.
 *          AntiHackingCheck:get_HasCodeBeenModified()
 *          com.notdoppler.utils.AntiHackingUtils:TestIsGenuine()
 *          com.notdoppler.crashofcars.<Start>d__19:MoveNext()
 * ```
 *
 * **Why this has to exist.** Any `bytecodePatch` rewrites `classes.dex`, so the
 * CRC necessarily differs from the value the stock `.so` was compiled against.
 * There is no way to patch this APK and keep the original digest — the check is
 * on the very bytes the patcher modifies. The only options are to bypass the
 * comparison or to pin the value the native side expects, and pinning is the
 * smaller change: one seven-instruction method, no native patching, no
 * `libil2cpp.so` byte offsets that would break on the next Not Doppler release.
 *
 * Keeping `Application.genuine == true` matters beyond cosmetics — it is a live
 * risk to the Free IAP patch, because if the game suppresses purchases while
 * `genuine` is false then the DEX-level `BillingClientImpl` spoof alone would
 * never land a grant. The game did not crash or exit on the check during the
 * Waydroid run, but whether it gates the store could not be determined, since the
 * store was blocked behind the expansion licensing gate at the time.
 *
 * **Kept as its own `bytecodePatch` on purpose.** The Morphe patcher rebuilds
 * `classes*.dex` per patch; keeping this separate from the billing and expansion
 * hooks means each one's intent stays legible and each can be reasoned about —
 * and disabled — on its own.
 *
 * Deliberately NOT pinned:
 *
 * - `GetCRC(String, String)J` (smali `:16`) — the generic helper
 *   `GetClassDefinitionCRC` calls. Pinning it would break every other caller.
 * - `GetManifestCRC()J` (smali `:98`) — CRCs `AndroidManifest.xml` instead. It is
 *   a legitimate part of the same tamper check, but **no mismatch was ever
 *   observed for it** on device — the log only ever reported the class-definition
 *   CRC — so it is deliberately left computing the real value. Re-signing does
 *   change `AndroidManifest.xml`, so if a future build starts reporting
 *   *"Expected manifest crc …"* this is the second method to pin, and its
 *   expected value would have to be read off that log the same way.
 */
@Suppress("unused")
val crashOfCarsAntiHackingCrcPinPatch = bytecodePatch(
    name = "Crash of Cars Anti-hacking Check",
    description = "Stops the game's built-in tamper detector from flagging the app as modified. The game ships a native check that fingerprints classes.dex and quietly marks the app as not genuine whenever the fingerprint does not match the one it was built with — which is exactly what happens the moment any mod is applied, since changing the app inevitably changes that fingerprint. With this on, the game keeps reporting itself as a normal, unmodified install, so purchases and other store features are not silently suppressed.",
    default = true
) {
    compatibleWith(COMPATIBILITY_CRASH_OF_CARS)

    execute {
        GetClassDefinitionCrcFingerprint.matchAllOrNull().requireExactlyOne()

        // `.registers 2` → the only wide pair available is v0/v1, which is exactly
        // what `const-wide v0, …` / `return-wide v0` needs. The method has no
        // exception table and no labels, so a label-free replacement at index 0
        // is safe and the unreachable original is not verified by ART.
        GetClassDefinitionCrcFingerprint.method.addInstructions(0, """
            const-wide v0, ${hexLiteral(EXPECTED_CLASS_DEFINITION_CRC)}
            return-wide v0
        """.trimIndent())
    }
}

/**
 * Throws [PatchException] unless [this] contains exactly one match — i.e. the
 * method was renamed or `CRCTool` changed shape. Emitting the patch with a
 * silently unresolved fingerprint would leave `Application.genuine == false` and
 * quietly re-break the store.
 */
private fun List<Match>?.requireExactlyOne() {
    val count = this?.size ?: 0
    if (count != 1) {
        throw PatchException(
            "Crash of Cars Anti-hacking Check: CRCTool.GetClassDefinitionCRC()J matched" +
                " $count time(s), expected exactly 1. The tamper check or its expected" +
                " value has changed — refusing to emit an APK that would still report" +
                " Application.genuine = false."
        )
    }
}

/**
 * Renders [value] as a `const-wide` literal: lowercase hex, the mandatory `0x`
 * prefix, and the trailing `L` that selects the 64-bit form.
 *
 * The prefix is not optional — emitting a bare `1c833075` makes smali's parser
 * reject the instruction outright (`Encountered 1 parser syntax errors`), which
 * aborts the whole patch run.
 */
private fun hexLiteral(value: Long): String = "0x${value.toString(16)}L"
