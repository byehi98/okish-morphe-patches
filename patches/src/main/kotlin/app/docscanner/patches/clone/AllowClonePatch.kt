package app.docscanner.patches.clone

import app.docscanner.patches.shared.Constants.COMPATIBILITY_DOCSCANNER
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

/** Base64 of "com.cv.docscanner", decoded by `b07.t()` and used as the app's original identity. */
private const val ORIGINAL_PACKAGE_B64 = "Y29tLmN2LmRvY3NjYW5uZXI="

/**
 * `b07.t()` — returns the app's original package name.
 *
 * Confirmed 6.9.14: classes/b07.smali:4729
 *   `.method public static t()Ljava/lang/String;` / `.registers 3`
 *   decodes "Y29tLmN2LmRvY3NjYW5uZXI=" with Base64 and returns it.
 *
 * Three call sites compare the result with the running package name through the
 * case-insensitive contains helper `jm3.l(original, current)`:
 *   - `b07.g()`  (classes/b07.smali) — anti-clone guard: on mismatch it reflectively calls
 *                 `dalvik.system.VMDebug.crash()` and then `System.exit(0)`. Called from
 *                 SplashActivity and AppMainActivity.onCreate.
 *   - AppMainActivity.onCreate — on mismatch it nulls the toolbar field.
 *   - BaseSurfaceView.<init> — on mismatch it skips wiring the editor surface.
 *
 * MATCH STRATEGY — the Base64 constant is unique to this method, so no obfuscated name is
 * referenced (6.9.9 Loki target `hb3.k` is now an unrelated protobuf switch-map class).
 */
private val originalPackageName = Fingerprint(
    returnType = "Ljava/lang/String;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf(),
    filters = listOf(
        string(ORIGINAL_PACKAGE_B64)
    )
)

/**
 * Doc Scanner Allow Clone
 *
 * Lets the app run under a package name changed by a "Change package name" patch. Instead of
 * teaching the case-insensitive helper to accept clones (6.9.9 Loki approach), the original
 * package name accessor is made to return the *running* package name, so every identity check
 * compares the app with itself and the anti-clone `VMDebug.crash()` / `System.exit(0)` guard
 * never fires.
 *
 * On an unmodified package name this is a no-op.
 */
@Suppress("unused")
val docScannerAllowClonePatch = bytecodePatch(
    name = "Doc Scanner Allow Clone",
    description = "Lets the app run under a changed package name. Doc Scanner compares its own " +
        "package name with the original at start-up and crashes itself when they differ. " +
        "Credits: Loki."
) {
    compatibleWith(COMPATIBILITY_DOCSCANNER)

    execute {
        // .registers 3, static, no parameters → v0..v2 are locals.
        originalPackageName.method.addInstructions(
            0,
            """
                sget-object v0, Lcom/cv/docscanner/helper/ModuleAppConfig;->c:Lcom/cv/docscanner/helper/ModuleAppConfig;
                invoke-virtual {v0}, Landroid/content/Context;->getPackageName()Ljava/lang/String;
                move-result-object v0
                return-object v0
            """
        )
    }
}
