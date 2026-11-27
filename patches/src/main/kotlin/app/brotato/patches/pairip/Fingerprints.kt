package app.brotato.patches.pairip

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * PairIP v2 anchors for Brotato 1.3.951 (versionCode 1030951).
 *
 * Verified against `analysis/brotato/smali/classes/com/pairip/` (baksmali 3.0.9, api 36).
 * Every `com.pairip` class of this build lives in **classes.dex**, so the anchors below are
 * unambiguous across the nine-dex bundle.
 *
 * Enforcement chain in this build (v2 generation — `ILicenseV2ResultListener`,
 * `checkLicenseInternal`, `RepeatedCheckMetadata`, `TrialClient` all present):
 *
 *   AndroidManifest application = com.pairip.application.Application
 *     extends com.safedk.android.SafeDKApplication
 *       <clinit> -> StartupLauncher.launch() -> VMRunner.invoke("FboEAJrc2eprcyK4", null)
 *     attachBaseContext
 *       -> VMRunner.setContext
 *       -> SignatureCheck.verifyIntegrity     [T1 signature gate — re-sign crash]
 *       -> LicenseClient.checkLicense         [T3 licence gate   — Play bind / paywall]
 *
 * `LicenseContentProvider` exists but is **not** declared in `AndroidManifest.xml`, and
 * `StartupLauncher.launch()` has exactly one caller (the SafeDK `<clinit>`). The VM layer is
 * therefore only reachable through those two paths; the app's own logic is not virtualised
 * (IL2CPP binaries are stock and metadata v29 dumps cleanly).
 */

/**
 * T0 — `public static declared-synchronized launch()V` (`StartupLauncher.smali:32`).
 *
 * The **first** thing that runs: `com.safedk.android.SafeDKApplication.<clinit>` calls this
 * from the Application's superclass initialiser, i.e. during `ActivityThread.handleBindApplication`
 * and *before* `attachBaseContext`. It runs the PairIP startup VM program
 * `FboEAJrc2eprcyK4` through `VMRunner.invoke`.
 *
 * That program is keyed to the original signing certificate. On a re-signed install
 * `executeVM` faults inside `libpairipcore.so` (verified on-device: SIGSEGV in the FNV-1
 * hash loop of `ExecuteProgram`, x86_64 pc 0x69d60, before any Java hook can run), the
 * process dies, and the launcher restarts it in a crash loop. Skipping the program is the
 * only way past it — the game's own code is **not** virtualised (all 20 `VMRunner.invoke`
 * call sites are inside ad-SDK / Unity-Ads / billing activities, none in `Assembly-CSharp`
 * consumers or UnityPlayer glue), so nothing the game needs is lost.
 */
object PairipStartupLauncherFingerprint : Fingerprint(
    definingClass = "Lcom/pairip/StartupLauncher;",
    name = "launch",
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        methodCall(definingClass = "Lcom/pairip/VMRunner;", name = "invoke"),
    ),
)

/**
 * T1 — `public static verifyIntegrity(Landroid/content/Context;)V`
 * (`SignatureCheck.smali:36`, `.registers 3`).
 *
 * SHA-256s `PackageInfo.signatures[0]`, Base64s it and compares against
 * `Vn3kj4pUblROi2S+QfRRL9nhsaO2uoHQg6+dpEtxdTE=` (the Play app-signing cert), throwing
 * `SignatureCheck$SignatureTamperedException("Apk signature is invalid.")` on mismatch.
 * It is called from `Application.attachBaseContext` **before** `super`, so a re-signed APK
 * dies before any Activity exists. Its only caller in the whole APK is that one line.
 */
object PairipVerifyIntegrityFingerprint : Fingerprint(
    definingClass = "Lcom/pairip/SignatureCheck;",
    name = "verifyIntegrity",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Landroid/content/Context;"),
    filters = listOf(
        string("SHA-256"),
        string("Apk signature is invalid."),
    ),
)

/**
 * T2 — `public static verifySignatureMatches(Ljava/lang/String;)Z`
 * (`SignatureCheck.smali:147`).
 *
 * Same hash comparison, exposed for the native core to call back through JNI. Nothing in
 * DEX calls it, but `libpairipcore.so` can, so it is forced to `true` as insurance.
 */
object PairipVerifySignatureMatchesFingerprint : Fingerprint(
    definingClass = "Lcom/pairip/SignatureCheck;",
    name = "verifySignatureMatches",
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Ljava/lang/String;"),
)

/**
 * T3 — `public static checkLicense(Landroid/content/Context;)V`
 * (`LicenseClient.smali:510`, `.registers 3`).
 *
 * The outermost licence entry: on a non-null context it posts `lambda$checkLicense$0`
 * (→ `bindService(ILicensingService)`, → `checkLicenseInternal`, → `processResponse`,
 * → `LicenseResponseHelper`) onto the immediate executor. `return-void` at offset 0 kills
 * the whole v2 state machine before it can bind, and therefore before `LicenseActivity`
 * (paywall / "Something went wrong" + `System.exit(0)`) can ever be launched.
 *
 * The licence *binding* is what fails on a sideload; it does not gate any game feature, so
 * this has no gameplay side effects.
 */
object PairipCheckLicenseFingerprint : Fingerprint(
    definingClass = "Lcom/pairip/licensecheck/LicenseClient;",
    name = "checkLicense",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Landroid/content/Context;"),
    filters = listOf(
        string("Cannot check license with null context."),
        methodCall(definingClass = "Lcom/pairip/licensecheck/LicenseClient;", name = "isIsolatedProcess"),
        string("Skipping license check in isolated process."),
        methodCall(
            definingClass = "Lcom/pairip/licensecheck/LicenseClient\$ImmediateTaskExecutor;",
            name = "run"
        ),
    ),
)

/**
 * T4 — exit killer: `public run()V` on `LicenseClient$1` (`LicenseClient$1.smali`).
 *
 * Body is literally `System.exit(0); return-void`. It is the `exitAction` handed to the
 * delayed executor when the licence flow decides the app must die. `checkLicense` is
 * already stubbed, so this is defence in depth: even if a repeated check re-enters through
 * another path, the process can no longer be killed from Java.
 */
object PairipExitActionFingerprint : Fingerprint(
    definingClass = "Lcom/pairip/licensecheck/LicenseClient\$1;",
    name = "run",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(),
    filters = listOf(
        methodCall(definingClass = "Ljava/lang/System;", name = "exit"),
    ),
)

/**
 * T5 — paywall screen: `public onStart()V` (`LicenseActivity.smali:426`, `.registers 3`).
 *
 * `super.onStart()` → read `"activitytype"` → `showErrorDialog()` or
 * `showPaywallAndCloseApp()` (market:// details intent + `exitApp()`). Returning at offset 0
 * makes the activity inert. It stays registered in the manifest — removing it would only
 * create `ClassNotFoundException` risk for the VM layer while adding nothing, because it is
 * unreachable once T3 is stubbed.
 */
object PairipLicenseActivityOnStartFingerprint : Fingerprint(
    definingClass = "Lcom/pairip/licensecheck/LicenseActivity;",
    name = "onStart",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(),
    filters = listOf(
        methodCall(definingClass = "Landroid/app/Activity;", name = "onStart"),
        string("activitytype"),
        methodCall(definingClass = "Lcom/pairip/licensecheck/LicenseActivity;", name = "showPaywallAndCloseApp"),
    ),
)

/*
 * ---------------------------------------------------------------------------------------
 * VM-free mode (T6–T9)
 *
 * The PairIP startup program `FboEAJrc2eprcyK4` does two things: it restores the 52-entry
 * static string vault `Landroidx/collection/internal/syT/hnsQGas;` (no Java code ever writes
 * those fields), and it registers PairIP's own glue. If `StartupLauncher.launch()` is
 * stubbed, every consumer of that vault sees `null`.
 *
 * That is invisible to the game (IL2CPP uses none of it) but fatal to the ad/analytics SDKs
 * that read the vault during their auto-init. On environments where `libpairipcore.so`
 * cannot run at all — verified on Waydroid: SIGSEGV in the VM's FNV-1a handler on x86_64 and
 * on houdini-translated arm64, SIGABRT from a bad free on x86, all with the *untouched Play
 * signature* — stubbing the VM plus these four init entry points is the only way to boot.
 *
 * Every hook below is a standard SDK auto-init gate, not app logic:
 *  - SafeDK is the ad-mediation/privacy wrapper (`SafeDKApplication.onCreate -> SafeDK.a`).
 *  - Firebase, ironSource, Vungle and MBridge init providers only start their SDKs.
 * ---------------------------------------------------------------------------------------
 */

/** T6 — `public onCreate()V` (`SafeDKApplication.smali:98`). */
object SafedkApplicationOnCreateFingerprint : Fingerprint(
    definingClass = "Lcom/safedk/android/SafeDKApplication;",
    name = "onCreate",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/safedk/android/SafeDK;",
            name = "a",
            parameters = listOf("Landroid/content/Context;", "Landroid/app/Application;"),
        ),
    ),
)

/**
 * T7 — `public onCreate()Z` (`FirebaseInitProvider.smali`).
 *
 * The normal body returns `false`; the crash happens inside the try block when
 * `FirebaseOptions.fromResource` passes the vault field `hnsQGas.oAkleYynIAKg`
 * (`google_storage_bucket`) to `Resources.getIdentifier`, which throws
 * `NullPointerException: name is null` because the field was never restored.
 */
object FirebaseInitProviderOnCreateFingerprint : Fingerprint(
    definingClass = "Lcom/google/firebase/provider/FirebaseInitProvider;",
    name = "onCreate",
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(),
    filters = listOf(
        string("FirebaseApp initialization unsuccessful"),
    ),
)

/** T8 — `public onCreate()Z` ironSource lifecycle provider (declares its own override). */
object IronsourceLifecycleProviderOnCreateFingerprint : Fingerprint(
    definingClass = "Lcom/ironsource/lifecycle/IronsourceLifecycleProvider;",
    name = "onCreate",
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(),
)

/** T9 — `public onCreate()Z` LevelPlay activity-lifecycle provider. */
object LevelPlayLifecycleProviderOnCreateFingerprint : Fingerprint(
    definingClass = "Lcom/ironsource/lifecycle/LevelPlayActivityLifecycleProvider;",
    name = "onCreate",
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(),
)

/** T10 — `public onCreate()Z` Vungle init provider. */
object VungleProviderOnCreateFingerprint : Fingerprint(
    definingClass = "Lcom/vungle/ads/VungleProvider;",
    name = "onCreate",
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(),
)

/** T11 — `public onCreate()Z` MBridge component-lifecycle provider. */
object MbComponentLifecycleProviderOnCreateFingerprint : Fingerprint(
    definingClass = "Lcom/mbridge/msdk/config/component/status/MBComponentLifecycleProvider;",
    name = "onCreate",
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(),
)

/**
 * T13 / T14 — GMS Ads init: `public static initialize(Landroid/content/Context;)V` and
 * `initialize(Landroid/content/Context;Lcom/google/android/gms/ads/initialization/OnInitializationCompleteListener;)V`
 * (`classes6/com/google/android/gms/ads/MobileAds.smali:151,167`).
 *
 * This is the one vault consumer that **kills the process** on a device with real Play
 * Services. GMS Ads lazily builds its local client in `zzeu.zzD` (`zzcoq.zzg` → `new zzk`);
 * the `zzk` constructor schedules `zzk.run()`, which reaches `zzgan.zza` → a `zzazk` built by
 * reflection over vault-provided field names (`com.vungle.ads.internal.bidding.Bt.lnMpdDDetUC.tVFJIcLNrdLi`
 * is the *name* of a field of `com.google.android.gms.internal.ads.zzazk`). With the vault
 * synthesised that lookup throws, the pool thread dies with an uncaught `RuntimeException`
 * (`ExceptionInInitializerError`) and takes the whole process down — the 5-second relaunch
 * loop observed on the Redmi K40. On Waydroid the same build boots fine, because GMS Ads
 * never connects there.
 *
 * Stubbing both `initialize` overloads stops the SDK's own auto-init, but mediation adapters
 * (AppLovin's `GoogleMediationAdapter`) also call `disableMediationAdapterInitialization`,
 * which creates the client through the same path — hence `GmsAdsEnsureClientFingerprint`
 * (T16) blocks `zzeu.zzD` itself. Cost: no AdMob/AppLovin-MAX ads (which is the point of
 * `no_ads` anyway); AppLovin's Google adapter just reports `DOES_NOT_APPLY`, and mediation
 * falls through to the remaining networks.
 */
object GmsMobileAdsInitializeFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/gms/ads/MobileAds;",
    name = "initialize",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Landroid/content/Context;"),
    filters = listOf(
        methodCall(definingClass = "Lcom/google/android/gms/ads/internal/client/zzeu;", name = "zzb"),
    ),
)

object GmsMobileAdsInitializeWithListenerFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/gms/ads/MobileAds;",
    name = "initialize",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf(
        "Landroid/content/Context;",
        "Lcom/google/android/gms/ads/initialization/OnInitializationCompleteListener;",
    ),
    filters = listOf(
        methodCall(definingClass = "Lcom/google/android/gms/ads/internal/client/zzeu;", name = "zzb"),
    ),
)

/**
 * T16 — GMS Ads client creation: `private final zzD(Context)V`
 * (`classes6/com/google/android/gms/ads/internal/client/zzeu.smali`).
 *
 * This is the single choke point behind the 5 s crash loop. Everything app-facing in
 * `MobileAds` (`initialize`, `disableMediationAdapterInitialization`, `openAdInspector`)
 * funnels into `zzeu.zzD`, which lazily builds the local Ads client (`zzat`/`zzax.zzd`) and
 * stores it in `zzl`. That client is `zzcoq.zzg()` → `new zzk(...)`, and the `zzk` constructor
 * immediately schedules `zzk.run()`, which posts `zzi.run()` → `zzk.zzn()` →
 * `zzbbg.zza()` → `zzgaj.<clinit>` → `zzgan.zza` → `zzazk` — whose protobuf schema builder
 * resolves field names by `Class.getDeclaredField(<vault string>)`. On a re-signed build the
 * vault is synthesised, the lookup throws `RuntimeException` inside `<clinit>`, the pool
 * thread dies with `ExceptionInInitializerError` and the process is killed: the relaunch loop
 * on a Play-Services/microG device.
 *
 * Making `zzD` a no-op keeps `zzl` null, the SDK's own "never initialised" state. All three
 * callers already re-check `if-nez zzl` and return early, so no Ads client is ever
 * constructed, no `GassClient` binds, and no `zzazk` is touched. Ads cannot load on a
 * re-signed build anyway; nothing outside the Ads SDK reads `zzl`.
 */
object GmsAdsEnsureClientFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/gms/ads/internal/client/zzeu;",
    name = "zzD",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.FINAL),
    parameters = listOf("Landroid/content/Context;"),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/google/android/gms/ads/internal/client/zzat;",
            name = "<init>",
        ),
        methodCall(
            definingClass = "Lcom/google/android/gms/ads/internal/client/zzax;",
            name = "zzd",
        ),
    ),
)

/**
 * T15 — Gass request factory: `static zza(Context, String, String)zzazk`
 * (`classes8/com/google/android/gms/internal/ads/zzgae.smali`).
 *
 * This is where the crash actually comes from. `zzgae.zza` is the only constructor of the
 * "Gass" client (`zzgad`, `HandlerThread` name `GassClient`), and all four GMS Ads signal
 * providers (`zzbbq`, `zzbcp`, `zzgan`, `zzgsc`) call it. `zzgad.onConnected` asks the bound
 * GMS Ads service for a config blob and deserialises the reply through
 * `zziil.zzn`, which does `Class.getDeclaredField(<vault string>)` on the model class
 * `zzazk` — the field *names* of `zzazk` are themselves vault values
 * (`com.vungle.ads.internal.bidding.Bt.lnMpdDDetUC.tVFJIcLNrdLi` → `"zzQ"` etc.). With the
 * vault synthesised the lookup throws `RuntimeException` on the `GassClient` thread and the
 * process dies, which is the 5-second relaunch loop on a Play-Services device. Stubbing
 * `MobileAds.*` alone is not enough: the Ads client singleton is created from other GMS
 * internals too.
 *
 * `zzgad` does contain a "no service" fallback — `static zzc()` builds a default `zzazk`
 * through the generated protobuf builder — but that builder is itself the reflection site, so
 * it throws too. `null` is the contract the callers already handle: `zzgsc` branches on
 * `if-eqz v0`, and `zzbbq`/`zzbcp` wrap the call in a catch-all that returns `null`. So every
 * Gass request returns `null`: no service bind, no HandlerThread, no model. Ads lose their
 * Gass signals (they cannot load anyway on a re-signed build); nothing else is affected.
 */
object GmsGassRequestFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/gms/internal/ads/zzgae;",
    name = "zza",
    returnType = "Lcom/google/android/gms/internal/ads/zzazk;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf(
        "Landroid/content/Context;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
    ),
    filters = listOf(
        methodCall(definingClass = "Lcom/google/android/gms/internal/ads/zzgad;", name = "<init>"),
    ),
)

/**
 * T12 — `public static writeProfile(Context, Executor, DiagnosticsCallback)V`
 * (`androidx/profileinstaller/ProfileInstaller.smali:433`).
 *
 * Not a PairIP hook: Morphe's rebuild renumbers the dex files (nine `classes*.dex` in the
 * original become ten), so the shipped baseline profile no longer matches. The transcoder
 * dereferences a null dex name on a background executor —
 * `NullPointerException … CharSequence.toString() on a null object reference` in
 * `ProfileTranscoder.generateDexKey` — and that uncaught exception kills the process a few
 * seconds into Unity startup. Returning at offset 0 of the 3-arg overload also covers the
 * 1-arg `writeProfile(Context)` entry, which just delegates to it.
 * Cost: no baseline-profile AOT install (minor startup-performance loss only).
 */
object ProfileInstallerWriteProfileFingerprint : Fingerprint(
    definingClass = "Landroidx/profileinstaller/ProfileInstaller;",
    name = "writeProfile",
    returnType = "V",
    parameters = listOf(
        "Landroid/content/Context;",
        "Ljava/util/concurrent/Executor;",
        "Landroidx/profileinstaller/ProfileInstaller\$DiagnosticsCallback;",
        "Z",
    ),
    filters = listOf(
        methodCall(definingClass = "Landroidx/profileinstaller/ProfileInstaller;", name = "transcodeAndWrite"),
    ),
)
