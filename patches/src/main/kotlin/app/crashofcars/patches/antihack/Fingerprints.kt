package app.crashofcars.patches.antihack

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * `com.notdoppler.CRCTool.GetClassDefinitionCRC()J` — smali
 * `analysis/com.notdoppler.crashofcars/smali/classes4/com/notdoppler/CRCTool.smali:78`,
 * `.registers 2`, **`public static`**.
 *
 * Computes the CRC of `classes.dex` straight out of the installed APK:
 * ```
 * sget-object  v0, Lcom/unity3d/player/UnityPlayer;->currentActivity:Landroid/app/Activity;
 * invoke-virtual {v0}, Landroid/app/Activity;->getPackageCodePath()Ljava/lang/String;
 * move-result-object v0
 * const-string  v1, "classes.dex"
 * invoke-static  {v0, v1}, Lcom/notdoppler/CRCTool;->GetCRC(Ljava/lang/String;Ljava/lang/String;)J
 * move-result-wide v0
 * return-wide   v0
 * ```
 * Seven instructions, **no exception table and no labels**, so a label-free
 * straight-line replacement at index 0 is safe and ART skips verifying the
 * now-unreachable remainder.
 *
 * The native IL2CPP routine `AntiHackingCheck.get_HasCodeBeenModified()` calls
 * this from C# and compares the result against a value baked into
 * `libil2cpp.so`, setting `Application.genuine = false` on mismatch. Observed
 * live on Waydroid after patching:
 * ```
 * E Unity: Anti-hacking check failed, CRC not match.
 * E Unity: Expected class definition crc 478359669, actual value 958176894.
 *          AntiHackingCheck:get_HasCodeBeenModified()
 *          com.notdoppler.utils.AntiHackingUtils:TestIsGenuine()
 *          com.notdoppler.crashofcars.<Start>d__19:MoveNext()
 * ```
 * `478359669 == 0x1C833075` — that is the constant pinned by
 * [CrashOfCarsAntiHackingCrcPinPatch].
 *
 * **Any** `bytecodePatch` rewrites `classes.dex`, so this CRC necessarily changes
 * and the check trips on every patched build, including the stock-looking Free
 * IAP build. Pinning it is what keeps `Application.genuine == true`, which is a
 * live risk to the Free IAP patch: if the game suppresses purchases while
 * `genuine` is false, the DEX-level billing spoof alone is not enough.
 *
 * The name is a real, non-obfuscated JNI-exported method (`CRCTool` is called by
 * name from native code) and occurs exactly once in the class, so no body
 * filters are needed. `accessFlags` matching is exact — `0x9` = PUBLIC | STATIC,
 * neither FINAL nor SYNCHRONIZED.
 */
object GetClassDefinitionCrcFingerprint : Fingerprint(
    definingClass = "Lcom/notdoppler/CRCTool;",
    name = "GetClassDefinitionCRC",
    returnType = "J",
    parameters = listOf(),
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC)
)