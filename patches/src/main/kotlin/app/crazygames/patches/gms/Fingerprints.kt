package app.crazygames.patches.gms

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

// ── Google sign-in path selection (CrazyGames 1.7.13) ────────────────────────
// @capacitor-firebase/authentication's `signInWithGoogle()` has two Android
// implementations and picks one from the JS option `useCredentialManager`
// (default: true):
//
//   true  → androidx.credentials GetCredential + the GMS credential-provider
//           service (androidx.credentials.playservices ... HiddenActivity)
//   false → legacy Auth.GoogleSignInApi → GoogleSignInClient.getSignInIntent()
//           → startActivityForResult
//
// The portal only passes `{ skipNativeAuth: true }`
// (`assets/public/_next/static/chunks/10b56ozvphgb5.js`), so the default is used.
//
// That default is wrong on MicroG-RE: its Credential Manager provider is broken
// on Android 14+ (MorpheApp/MicroG-RE#346 — credential-type mismatch plus an
// Android 14 Bundle/parcelable format mismatch, chooser opens and closes
// immediately). The issue's own workaround is "forcing the client onto the
// legacy BeginSignIn path", which is exactly `useCredentialManager: false` and
// is what this patch does.
//
// smali: smali_classes2/u51.smali, `.method public d(Lrf2;Z)V`
//
//    const-string v1, "useCredentialManager"
//    const/4 v2, 0x0
//    invoke-virtual {p1, v1, v2}, Lrf2;->c(Ljava/lang/String;Ljava/lang/Boolean;)Ljava/lang/Boolean;
//    move-result-object v1          ← replaced with Boolean.FALSE
//    if-eqz v1, :cond_0
//    ...
//    :goto_0
//    if-eqz v1, :cond_2             ← false now always lands on the legacy branch
//
// The method is in the R8-obfuscated `defpackage` namespace, so the string is the
// only stable marker. Verified: "useCredentialManager" occurs in exactly one
// class and one method across all three DEX files, so the filter cannot drift
// onto an unrelated caller.
internal object UseCredentialManagerFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        string("useCredentialManager"),
    ),
)

// ── Play Services availability / signature gate ──────────────────────────────
// smali: smali_classes2/o51.smali, `.method public c(Landroid/content/Context;I)I`
// (decompiled defpackage/o51.java). `n51` extends `o51` and `n51Var.c(...)` in
// the sign-in client calls this inherited implementation.
//
// The method is `GoogleApiAvailabilityLight.isGooglePlayServicesAvailable`:
// it resolves `com.google.android.gms`, then runs the Google signature
// verifier (`g61.d`) against the installed package. MicroG-RE is *not* signed
// with Google's key, and this Waydroid image has no signature spoofing, so the
// check logs `requires Google Play services, but their signature is invalid`
// and returns SERVICE_INVALID (9). The legacy sign-in client then maps that to
// `NO_IMPL` → `AuthSignInClient: Action not implemented` → JS error 12500.
//
// Upstream GmsCore-support patches `returnEarly(0)` on the same method
// (MorpheApp/morphe-patches shared/misc/gms — GooglePlayUtilityFingerprint).
// The client-side check is safe to disable: the *server-side* package +
// certificate check is handled by the SPOOFED_PACKAGE_* meta-data below, not by
// this verifier.
//
// Fingerprint differs from upstream only in access flags: here the method is an
// instance method on `o51`, not static, so no accessFlags filter is applied.
// The `"com.google.android.gms"` marker must be matched before the string
// rewrite runs (the patch calls `.method` first for exactly that reason).
internal object GooglePlayUtilityFingerprint : Fingerprint(
    returnType = "I",
    parameters = listOf("L", "I"),
    filters = listOf(
        string("MetadataValueReader"),
        string("This should never happen."),
        string("com.google.android.gms"),
    ),
)

// ── "Play Services not available" hard failure ───────────────────────────────
// smali: smali_classes2/d61.smali, `.method public static a(Landroid/content/Context;I)V`
// (GoogleApiAvailability.ensurePlayServicesAvailable). When availability is not
// SUCCESS it throws GooglePlayServicesRepairableException / NotAvailableException,
// which aborts every API call. Returning early suppresses the exception; the
// upstream patch does the same (`ServiceCheckFingerprint.method.returnEarly()`).
internal object ServiceCheckFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "V",
    parameters = listOf("L", "I"),
    strings = listOf("Google Play Services not available"),
)
