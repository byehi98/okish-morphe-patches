package app.minutestilldawn.patches.pairip

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * 20 Minutes Till Dawn 8.6 (versionCode 1093) — PairIP fingerprints.
 *
 * All targets verified present in the APKPure base APK:
 *   com/pairip/StartupLauncher; startupProgramName = "8jsM3WGcz3HmEwH0"
 *   "Apk signature is invalid." / "Cannot check license with null context."
 *   UnityPlayerActivity, FacebookContentProvider, SafeDKApplication, TenjinStartup,
 *   CombineContinuationsWorker, OfflinePingSender, applovin/impl/sj.
 */

// ---- Layer 1 + 2: signature and license gates ------------------------------------

internal object SignatureCheckVerifyFingerprint : Fingerprint(
    definingClass = "Lcom/pairip/SignatureCheck;",
    name = "verifyIntegrity",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Landroid/content/Context;"),
    strings = listOf("Apk signature is invalid.")
)

internal object LicenseClientCheckFingerprint : Fingerprint(
    definingClass = "Lcom/pairip/licensecheck/LicenseClient;",
    name = "checkLicense",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Landroid/content/Context;"),
    strings = listOf("Cannot check license with null context.")
)

// ---- Layer 3: skip the startup VM program ----------------------------------------

internal object StartupLauncherFingerprint : Fingerprint(
    definingClass = "Lcom/pairip/StartupLauncher;",
    name = "launch",
    returnType = "V",
    // No accessFlags: the patcher compares them exactly and launch() is
    // `public static declared-synchronized`, so PUBLIC|STATIC alone never matches.
    // startupProgramName is a static field, not a const-string in the body.
    filters = listOf(
        fieldAccess(
            name = "startupProgramName",
            type = "Ljava/lang/String;",
            opcode = Opcode.SGET_OBJECT
        )
    )
)

// ---- Layer 4: license presentation sinks -----------------------------------------

internal object LicenseClientStartPaywallFingerprint : Fingerprint(
    definingClass = "Lcom/pairip/licensecheck/LicenseClient;",
    name = "startPaywallActivity",
    returnType = "V",
    parameters = listOf("Landroid/app/PendingIntent;"),
    strings = listOf("paywallintent")
)

internal object LicenseClientStartErrorDialogFingerprint : Fingerprint(
    definingClass = "Lcom/pairip/licensecheck/LicenseClient;",
    name = "startErrorDialogActivity",
    returnType = "V",
    strings = listOf("activitytype")
)

// ---- Layer 5: the VM choke point --------------------------------------------------

internal object VMRunnerInvokeFingerprint : Fingerprint(
    definingClass = "Lcom/pairip/VMRunner;",
    name = "invoke",
    returnType = "Ljava/lang/Object;",
    strings = listOf("Executing ")
)

// ---- Layer 6: stubs that must return a value, not null ----------------------------

internal object CombineContinuationsWorkerFingerprint : Fingerprint(
    definingClass = "Landroidx/work/impl/workers/CombineContinuationsWorker;",
    name = "doWork",
    returnType = "Landroidx/work/ListenableWorker\$Result;"
)

internal object OfflinePingSenderFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/gms/ads/internal/offline/buffering/OfflinePingSender;",
    name = "doWork",
    returnType = "Landroidx/work/ListenableWorker\$Result;"
)

// ---- Layer 7: string-pool consumers that NPE on null holders -----------------------

internal object TenjinStartupRunnableFingerprint : Fingerprint(
    definingClass = "Lcom/tenjin/android/utils/TenjinStartup\$1;",
    name = "run",
    returnType = "V"
)

// ---- Layer 8: AppLovin duplicate-key assertion -------------------------------------

internal object AppLovinSettingRegisterFingerprint : Fingerprint(
    definingClass = "Lcom/applovin/impl/sj;",
    name = "a",
    returnType = "Lcom/applovin/impl/sj;",
    parameters = listOf("Ljava/lang/String;", "Ljava/lang/Object;"),
    strings = listOf("Setting has already been used: ")
)

// ---- Virtualized lifecycle stubs (reflective Method holders) ----------------------

internal fun vmHolderField() = fieldAccess(
    type = "Ljava/lang/reflect/Method;",
    opcode = Opcode.SGET_OBJECT
)

internal object UnityPlayerActivityOnStartFingerprint : Fingerprint(
    definingClass = "Lcom/unity3d/player/UnityPlayerActivity;",
    name = "onStart",
    returnType = "V",
    filters = listOf(vmHolderField())
)

internal object UnityPlayerActivityOnStopFingerprint : Fingerprint(
    definingClass = "Lcom/unity3d/player/UnityPlayerActivity;",
    name = "onStop",
    returnType = "V",
    filters = listOf(vmHolderField())
)

internal object UnityPlayerActivityOnPauseFingerprint : Fingerprint(
    definingClass = "Lcom/unity3d/player/UnityPlayerActivity;",
    name = "onPause",
    returnType = "V",
    filters = listOf(vmHolderField())
)

internal object UnityPlayerActivityOnResumeFingerprint : Fingerprint(
    definingClass = "Lcom/unity3d/player/UnityPlayerActivity;",
    name = "onResume",
    returnType = "V",
    filters = listOf(vmHolderField())
)

internal object UnityPlayerActivityOnDestroyFingerprint : Fingerprint(
    definingClass = "Lcom/unity3d/player/UnityPlayerActivity;",
    name = "onDestroy",
    returnType = "V",
    filters = listOf(vmHolderField())
)

/** Facebook provider installs early and blocks process start. */
internal object FacebookContentProviderOnCreateFingerprint : Fingerprint(
    definingClass = "Lcom/facebook/FacebookContentProvider;",
    name = "onCreate",
    returnType = "Z",
    filters = listOf(vmHolderField())
)

/** SafeDK hook — its virtualised onCreate is the next NPE after the provider. */
internal object SafeDKApplicationOnCreateFingerprint : Fingerprint(
    definingClass = "Lcom/safedk/android/SafeDKApplication;",
    name = "onCreate",
    returnType = "V",
    filters = listOf(vmHolderField())
)
