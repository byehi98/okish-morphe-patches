package app.bighunter.patches.license

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * PairIP license-check chokepoints for Big Hunter 3.1.2 (all non-obfuscated, classes.dex).
 *
 * Enforcement flow (verified in smali + decompiled Java):
 *   LicenseContentProvider.onCreate() (manifest-registered, runs before the launcher activity)
 *     → new LicenseClient(context).checkLicense() → initializeLicenseCheck()
 *     → local installer check (must be com.android.vending) + initiateFreshLicensingServiceConnection()
 *       (→ bindToLicensingService() → binds Play's com.android.vending.licensing.ILicensingService)
 *     → LicenseResponseHelper.validateResponse(payload, packageName)
 *     → on failure: LicenseActivity (PAYWALL / ERROR_DIALOG) → System.exit(0)
 *
 * 3.1.2 changes vs 3.1.1: connectToLicensingService was replaced by
 * initiateFreshLicensingServiceConnection (direct call) → bindToLicensingService (private, 2 hops down),
 * and ResponseValidator was merged into LicenseResponseHelper (same validateResponse name/signature).
 */

/** The single entry point — no-op it and the whole check never starts. */
object InitializeLicenseCheckFingerprint : Fingerprint(
    definingClass = "Lcom/pairip/licensecheck/LicenseClient;",
    name = "initializeLicenseCheck",
    returnType = "V",
    parameters = listOf(),
    accessFlags = listOf(AccessFlags.PUBLIC),
    filters = listOf(
        app.morphe.patcher.methodCall(
            definingClass = "Lcom/pairip/licensecheck/LicenseClient;",
            name = "initiateFreshLicensingServiceConnection",
        ),
    ),
)

/** Belt-and-braces: signature validation of the Play license response — never throws. */
object ValidateResponseFingerprint : Fingerprint(
    definingClass = "Lcom/pairip/licensecheck/LicenseResponseHelper;",
    name = "validateResponse",
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;", "Ljava/lang/String;"),
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
)

/** Last resort: if the paywall/error dialog activity is ever launched, it does nothing. */
object LicenseActivityOnStartFingerprint : Fingerprint(
    definingClass = "Lcom/pairip/licensecheck/LicenseActivity;",
    name = "onStart",
    returnType = "V",
    parameters = listOf(),
    accessFlags = listOf(AccessFlags.PUBLIC),
)
