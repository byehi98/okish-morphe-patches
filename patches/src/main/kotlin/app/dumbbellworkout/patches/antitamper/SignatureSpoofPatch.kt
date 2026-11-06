package app.dumbbellworkout.patches.antitamper

import app.dumbbellworkout.patches.shared.Constants.COMPATIBILITY_DUMBBELL_WORKOUT
import app.dumbbellworkout.patches.shared.Constants.ORIGINAL_SIGNATURE_HEX
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.all.misc.hex.HexPatchBuilder
import app.morphe.patches.all.misc.hex.hexPatch

/**
 * Native signature-check sites in `liblpcore.so`, one group per ABI.
 *
 * There are two distinct gates, and **both** have to be neutralised:
 *
 * 1. `JNI_OnLoad` calls the verifier and returns `JNI_ERR` on failure. Only the
 *    conditional leading to that return is NOPed.
 * 2. `decryptDataAndConvertToString` calls the same verifier at its very start
 *    and returns `NULL` when it reports failure — before touching the asset.
 *    `readFile` (used by `Decoder.getAllJsonNative`, the path that loads every
 *    workout plan) then hands `null` to `rx3.E`, which does
 *    `new JSONArray(null)`, and the Kotlin caller NPEs. That is the crash when
 *    tapping **Start**. The `call` itself is therefore rewritten to
 *    `mov <ret>, 1`, so decryption always proceeds.
 *
 * Every pattern below covers the call plus one surrounding instruction that
 * catches the `JNIEnv`/argument setup, was byte-scanned against the 1.3.6
 * libraries, and occurs exactly once per library. Replacements are the same
 * length as the pattern, so nothing outside the matched bytes moves.
 *
 * Declared before the patch on purpose: top-level properties are initialised in
 * declaration order, so referencing this from the patch below a later
 * declaration would pass `null` at class-init time.
 */
private val nativeSignatureSites: HexPatchBuilder.() -> Unit = {
    // ---- Layer 1: JNI_OnLoad must not return JNI_ERR -------------------------
    // x86_64 @0x2a1d: call verifier; test al, al; je +0x18  →  nop nop
    "48 8B 7D E8 E8 4A 00 00 00 84 C0 74 18" asPatternTo
        "48 8B 7D E8 E8 4A 00 00 00 84 C0 90 90" inFile "lib/x86_64/liblpcore.so"

    // x86 @0x24e7: call verifier; mov ecx,eax; mov eax,0x10006; test cl,cl; jne +0x20
    "8B 4D F4 E8 51 00 00 00 89 C1 B8 06 00 01 00 84 C9 75 20" asPatternTo
        "8B 4D F4 E8 51 00 00 00 89 C1 B8 06 00 01 00 84 C9 90 90" inFile "lib/x86/liblpcore.so"

    // arm64-v8a @0x2920: ldr x0,[sp]; bl verifier; tst w0,#0xff; b.eq failure  →  nop
    "E0 03 40 F9 14 00 00 94 1F 1C 00 72 40 01 00 54" asPatternTo
        "E0 03 40 F9 14 00 00 94 1F 1C 00 72 1F 20 03 D5" inFile "lib/arm64-v8a/liblpcore.so"

    // armeabi-v7a @0x1da4: ldr r0,[sp]; bl verifier; cbz r0, failure  →  nop
    "00 98 00 F0 1D F8 58 B1" asPatternTo
        "00 98 00 F0 1D F8 00 BF" inFile "lib/armeabi-v7a/liblpcore.so"

    // ---- Layer 2: decryptDataAndConvertToString must not bail out ------------
    // x86_64 @0x2f5a: mov rbx,rdi; call verifier; test al,al  →  mov eax,1
    "48 89 FB E8 11 FB FF FF 84 C0" asPatternTo
        "48 89 FB B8 01 00 00 00 84 C0" inFile "lib/x86_64/liblpcore.so"

    // x86 @0x2a2a: mov ecx,edi; call verifier; test al,al  →  mov eax,1
    "89 F9 E8 11 FB FF FF 84 C0" asPatternTo
        "89 F9 B8 01 00 00 00 84 C0" inFile "lib/x86/liblpcore.so"

    // arm64-v8a @0x2eec: mov x19,x0; bl verifier; tst w0,#0xff  →  mov w0,#1
    "F3 03 00 AA A2 FE FF 97 1F 1C 00 72" asPatternTo
        "F3 03 00 AA 20 00 80 52 1F 1C 00 72" inFile "lib/arm64-v8a/liblpcore.so"

    // armeabi-v7a @0x2174: mov r4,r0; bl verifier; cmp r0,#0  →  movs r0,#1
    "04 46 FF F7 36 FE 00 28" asPatternTo
        "04 46 01 20 00 BF 00 28" inFile "lib/armeabi-v7a/liblpcore.so"
}

/**
 * Dumbbell Workout at Home — Signature Spoof / anti-tamper bypass
 * (`dumbbellworkout.dumbbellapp.homeworkout` 1.3.6 / versionCode 52).
 *
 * ## What the app checks
 *
 * The app ships a **duplicated anti-tamper family of 38 classes** (`a08 … w08` and
 * `kz7 … zz7`, all in `smali/classes3/`). Every one of them has exactly one
 * signature-reading method:
 *
 * ```
 * .method public static b(<some Activity>)Ljava/lang/String;
 *     .registers 5
 * ```
 *
 * It reads the app's own signing certificate through reflection — the
 * `PackageManager.mPM` field (`const-string "mPM"`), then
 * `getPackageInfo(pkg, 64).signatures[0].toCharsString()` — and returns the
 * certificate's DER as a 1826-character lowercase hex string, or `""` on any
 * exception.
 *
 * **46 call sites across 23 activities** then take one hardcoded 31-character
 * slice of that string (`sig.substring(start, end)`, e.g. `a08` ↔
 * `SetFirstReminderActivity` uses `substring(0x2f4, 0x313)` = 756..787 against
 * `ad6ff5040854d8af689b62a92a3bfac`) and compare it against a hardcoded literal.
 * All 38 probe literals were verified to be exact slices of the same original
 * certificate hex, so a single correct return value satisfies every check.
 *
 * The check is invoked from `c90.onCreate` (`smali/classes3/c90.smali:589` →
 * `p0.A(p1)`), and `c90` is the base class of every screen, so it runs while
 * `MainActivity` is being created. On mismatch the reader's companion calls
 * `System.exit` via a `RuntimeException` that terminates the activity — the app
 * never becomes usable.
 *
 * Seven of the 38 classes (`d08`, `j08`, `lz7`, `rz7`, `uz7`, `vz7`, `yz7`) also
 * expose a `c(AppCompatActivity)V` wrapper used by 9 extra call sites. Those
 * wrappers just call their own `b()`, so patching `b()` covers both forms and
 * `c()` is intentionally left alone.
 *
 * ## Why this patch is mandatory
 *
 * `morphe-cli` always re-signs the output APK, so the installed certificate no
 * longer matches the baked-in slices, all 46 checks fail, and the re-signed APK
 * crashes on launch. There is no way to patch this app without spoofing the
 * signature, which is why this patch is `default = true`.
 *
 * ## The fix
 *
 * Replace the body of all 38 readers with a constant return of the original
 * certificate's `toCharsString()` output ([ORIGINAL_SIGNATURE_HEX]). Because every
 * check compares a slice of that same string, one hardcoded return makes all 46
 * comparisons pass — no per-call-site patching, no per-activity special cases, and
 * nothing name-based that would break on the next R8 rename.
 *
 * Each target is `public static` with `.registers 5`, so `v0` is a valid local
 * register and `const-string` / `return-object` are safe. Injecting at index 0
 * leaves the original body as unreachable dead code and strands no handler or
 * monitor — the same pattern as `LuminaPairipBypassPatch`.
 *
 * ## Second layer: native verifier inside `liblpcore.so`
 *
 * The Java spoof alone is **not enough**. `app.lp.decode.Decoder` (`classes3`)
 * loads `System.loadLibrary("lpcore")`, and `JNI_OnLoad` in every ABI copy of
 * `liblpcore.so` runs its own certificate check (log tag `zoecore::`):
 * it reflects `ActivityThread.currentApplication()` → `getPackageManager()` →
 * `getPackageInfo(pkg, 64).signatures[0].toCharsString()`, compares the result
 * with the original certificate, and on mismatch logs `signature is error` and
 * returns `JNI_ERR`. `Decoder.<clinit>` then catches the resulting
 * `UnsatisfiedLinkError`, sets `Decoder.a = false`, and `SplashActivity`
 * (resource `0x7f130434`) shows "File corrupted, please reinstall the App from
 * Google Play." and never continues.
 *
 * The **same** verifier is called a second time, at the top of
 * `decryptDataAndConvertToString` — the function behind every
 * `Decoder.getAllJsonNative` call that loads workout plans. On failure it returns
 * `NULL` without ever reading the asset, so `readFile` → `rz3`/`rx3` parses
 * `null` (`JSONArray(null)`) and `MyWorkoutInstructionActivity` throws
 * `NullPointerException: … Object.getClass() on a null object reference` as soon
 * as **Start** is tapped. Bypassing `JNI_OnLoad` alone therefore leaves the app
 * bootable but unable to open any workout.
 *
 * Both gates are neutralised in place for all four ABIs:
 *
 * - Layer 1: the `je/jne/b.eq/cbz` that leads to `JNI_ERR` is replaced with a
 *   NOP, so `JNI_OnLoad` returns `JNI_VERSION_1_6` (`0x10006`).
 * - Layer 2: the `call`/`bl` to the verifier inside
 *   `decryptDataAndConvertToString` is replaced with a return value of `true`
 *   (`mov eax,1` / `mov w0,#1` / `movs r0,#1`), so decoding always proceeds.
 *
 * Each anchor below is the exact instruction sequence around that site in 1.3.6
 * and occurs exactly once per library; replacements are the same length, so the
 * rest of the function is byte-identical.
 *
 * | ABI          | gate 1 offset | original branch        | replacement |
 * |--------------|---------------|------------------------|-------------|
 * | x86_64       | `0x2a28`      | `74 18` (`je +0x18`)   | `90 90`     |
 * | x86          | `0x24f8`      | `75 20` (`jne +0x20`)  | `90 90`     |
 * | arm64-v8a    | `0x292c`      | `40 01 00 54` (`b.eq`) | `1f 20 03 d5` (`nop`) |
 * | armeabi-v7a  | `0x1daa`      | `58 b1` (`cbz r0`)     | `00 bf` (`nop`) |
 *
 * | ABI          | gate 2 offset | original call          | replacement |
 * |--------------|---------------|------------------------|-------------|
 * | x86_64       | `0x2f5a`      | `e8 11 fb ff ff` (`call`) | `b8 01 00 00 00` (`mov eax,1`) |
 * | x86          | `0x2a2a`      | `e8 11 fb ff ff` (`call`) | `b8 01 00 00 00` (`mov eax,1`) |
 * | arm64-v8a    | `0x2eec`      | `a2 fe ff 97` (`bl`)      | `20 00 80 52` (`mov w0,#1`) |
 * | armeabi-v7a  | `0x2174`      | `ff f7 36 fe` (`bl`)      | `01 20 00 bf` (`movs r0,#1`; `nop`) |
 *
 * Both layers are one listing entry: `dependsOn(hexPatch(…))` edits the four
 * shared objects first, then `execute` rewrites the 38 Java readers. The
 * artifacts are disjoint (`.so` vs `classes3.dex`), so order does not matter,
 * but all of them are required for the app to reach its first activity *and* to
 * open a workout.
 */
@Suppress("unused")
val dumbbellSignatureSpoofPatch = bytecodePatch(
    name = "Signature Spoof",
    description = "Neutralises the app's built-in certificate verifier so the patched APK keeps running. The app re-reads its own signing certificate on every screen and compares 46 hardcoded slices of it against baked-in values; because patching always re-signs the APK, those comparisons fail and the app force-closes while the first activity is being created. With this patch every verifier reports the original, unmodified certificate instead, so all 46 checks pass. The bundled native library runs two further certificate checks: one stops the library from loading, and one silently stops workout data from being decrypted, which crashes the app the moment you tap Start. Both are bypassed as well. This patch is required — the app can neither start nor open a workout without it.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_DUMBBELL_WORKOUT)

    dependsOn(hexPatch(ignoreMissingTargetFiles = true, block = nativeSignatureSites))

    execute {
        val matches = SignatureReaderFingerprint.matchAll()

        // Fail loudly rather than ship an APK that still force-closes on launch:
        // a renamed/reshaped reader, or a reordered filter, would otherwise drop
        // one class and re-break its activity without any build-time signal.
        if (matches.size != EXPECTED_SIGNATURE_READERS) {
            throw PatchException(
                "Signature Spoof: expected $EXPECTED_SIGNATURE_READERS certificate readers," +
                    " found ${matches.size}:" +
                    matches.joinToString(prefix = " ", separator = ", ") { it.classDef.type } +
                    ". The anti-tamper family changed shape — refusing to emit an APK that" +
                    " would still fail its own signature check."
            )
        }

        matches.forEach { match ->
            match.method.addInstructions(0, """
                const-string v0, "$ORIGINAL_SIGNATURE_HEX"
                return-object v0
            """.trimIndent())
        }
    }
}

/** 38 duplicated `b(<Activity>)Ljava/lang/String;` readers — see [SignatureReaderFingerprint]. */
private const val EXPECTED_SIGNATURE_READERS = 38

