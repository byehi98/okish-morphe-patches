package app.crazygames.patches.gms

import app.crazygames.patches.shared.Constants.COMPATIBILITY_CRAZYGAMES
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.util.indexOfFirstStringInstructionOrThrow
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import org.w3c.dom.Element

private const val GMS_PACKAGE = "com.google.android.gms"

/** MicroG-RE ships under the ReVanced-era vendor id (kept for compatibility). */
private const val MICROG_PACKAGE = "app.revanced.android.gms"

/** The package Google has the OAuth client registered against — must not change. */
private const val ORIGINAL_PACKAGE = "com.crazygames.crazygamesapp"

/**
 * SHA-1 of the original Play signing certificate (DER), i.e. what the Play
 * Console shows for the app. `PackageInfo.signatures[0].toByteArray()` is the
 * certificate DER for v2/v3-signed APKs, so this is exactly the digest real GMS
 * would send to Google. Read from the untouched base APK:
 *
 *   apksigner verify --print-certs com.crazygames.crazygamesapp.apk
 *   Signer #1 certificate SHA-1 digest: ecdc6d23ded931a4a61bae9e750b73f900d78a2b
 */
private const val ORIGINAL_SIGNING_SHA1 = "ecdc6d23ded931a4a61bae9e750b73f900d78a2b"

/** How far ahead of the `const-string "useCredentialManager"` the result is. */
private const val MOVE_RESULT_LOOKAHEAD = 8

/**
 * Auth action strings the app sends to Google Play Services.
 *
 * The manifest of MicroG-RE 7.3.1 registers the generic service actions under
 * both the original and the `app.revanced` name, but the sign-in entry points
 * exist **only** under the new name (verified with
 * `baksmali` + `aapt dump xmltree` on microg-7.3.1-noicon.apk):
 *
 *   AuthSignInActivity  ← action "app.revanced.android.gms.auth.GOOGLE_SIGN_IN"
 *   AuthSignInService   ← action "app.revanced.android.gms.auth.api.signin.service.START"
 *
 * Without rewriting these, `SignInHubActivity` resolves the intent against
 * `app.revanced.android.gms` and finds no matching activity/service.
 */
private val GMS_AUTH_ACTIONS = setOf(
    "com.google.android.gms.auth.GOOGLE_SIGN_IN",
    "com.google.android.gms.auth.APPAUTH_SIGN_IN",
    "com.google.android.gms.auth.api.signin.service.START",
    "com.google.android.gms.auth.api.credentials.service.START",
    "com.google.android.gms.auth.api.identity.service.signin.START",
    "com.google.android.gms.auth.api.identity.service.authorization.START",
    "com.google.android.gms.auth.api.identity.service.credentialsaving.START",
    "com.google.android.gms.auth.service.START",
    "com.google.android.gms.signin.service.START",
    "com.google.android.gms.common.service.START",
)

/**
 * CrazyGames 1.7.13 — Google login on a re-signed build via MicroG-RE.
 *
 * WHY A PATCH IS NEEDED AT ALL: the portal signs in with Firebase Auth and
 * `@capacitor-firebase/authentication`'s native `signInWithGoogle()` (see the
 * login screen → "Continue with Google"). On Android that asks Google Play
 * Services for a Google ID token with the project's web client
 * (`R.string.default_web_client_id` =
 * `492838575572-tsmmf0abrjagqgoasumdrrm3eoias0ie.apps.googleusercontent.com`).
 * Google validates the calling app's **package name and signing certificate**
 * against the Android OAuth client registered in the Firebase/Cloud project.
 * morphe-cli re-signs with `morphe.keystore`, so the certificate no longer
 * matches and the request is rejected inside GMS (observed on a healthy,
 * Play-Store-signed-in account as
 * `Auth.Api.Credentials ... [16] Account reauth failed`). There is no app-side
 * check to bypass — the rejection is server-side — so the app has to hand
 * Google the *original* package + certificate, which is what MicroG-RE's
 * `SPOOFED_PACKAGE_*` support does (see below). No ROM signature spoofing is
 * involved: MicroG-RE reads the two meta-data values straight from the patched
 * app's manifest (`org.microg.gms.common.PackageSpoofUtils`, verified in
 * microg-7.3.1).
 *
 * WHAT THIS PATCH DOES:
 *  1. Disables the **client-side** Play Services gate. The bundled
 *     `play-services-base` resolves `com.google.android.gms`, then runs the
 *     Google signature verifier against the installed package. MicroG-RE is not
 *     Google-signed, so the check returned `SERVICE_INVALID` and the legacy
 *     sign-in client bailed out with `NO_IMPL` → JS error 12500. Two methods are
 *     neutered, matching upstream Morphe/ReVanced GmsCore support:
 *     `GoogleApiAvailabilityLight.isGooglePlayServicesAvailable` →
 *     `returnEarly(0)`, and `GoogleApiAvailability.ensurePlayServicesAvailable`
 *     → `returnEarly()`. The *server-side* package/certificate check is not
 *     affected by this and is handled by (4).
 *  2. Repoints `"com.google.android.gms"` **package-name** constants and the
 *     sign-in action strings at MicroG-RE's package
 *     (`app.revanced.android.gms`). Type descriptors
 *     (`Lcom/google/android/gms/...;`, AIDL interface names) and `"com.google"`
 *     account-type strings are untouched — MicroG-RE implements the same class
 *     names and registers its accounts under the standard `com.google` type.
 *  3. Declares MicroG-RE in `<queries>` so Android 11+ package visibility does
 *     not filter it out. Without this, `getPackageInfo("app.revanced.
 *     android.gms", …)` throws `NameNotFoundException` (observed in logcat) and
 *     Firebase/`GoogleApiAvailability` fall back to "not installed".
 *  4. Adds the spoofing contract to `<application>` so MicroG-RE impersonates
 *     the original install when it talks to Google:
 *       `app.revanced.android.gms.SPOOFED_PACKAGE_NAME`      = ORIGINAL_PACKAGE
 *       `app.revanced.android.gms.SPOOFED_PACKAGE_SIGNATURE` = SHA-1 of the
 *                                                             original cert
 *     This is the same mechanism upstream GmsCore-support patches use
 *     (`$GMS_CORE_VENDOR_GROUP_ID.android.gms.SPOOFED_PACKAGE_*`), and it is
 *     why no ROM-level signature spoofing is required.
 *  5. Forces the legacy `Auth.GoogleSignInApi` path in the
 *     `@capacitor-firebase/authentication` Google provider by replacing the
 *     `useCredentialManager` read with `Boolean.FALSE` (see
 *     [UseCredentialManagerFingerprint]). Journey: Credential Manager →
 *     androidx.credentials. Base `MicroG-RE`'s credential provider is broken on
 *     Android 14+ (MorpheApp/MicroG-RE#346; the documented workaround is
 *     "forcing the client onto the legacy BeginSignIn path"), and the legacy
 *     path is the one MicroG-RE implements completely
 *     (`AuthSignInActivity` / `AuthSignInService`).
 *
 * WHAT THIS PATCH DOES **NOT** DO: it cannot make Google login work with stock
 * GMS — re-signing is fatal there regardless of the package the app binds to,
 * and this patch does not touch real GMS. It also cannot fix an account whose
 * Google credentials are stale.
 *
 * REQUIREMENTS:
 *  - MicroG-RE (`app.revanced.android.gms`) installed **and signed in** with the
 *    Google account. The patch only routes the app to it and tells it which
 *    package/certificate to present.
 *  - The patched app must **replace** the Play install: Google checks the
 *    package name, so do not combine this patch with any package-rename/clone
 *    patch.
 *  - Android 7+ (the app's minSdk). Tested against MicroG-RE 7.3.1.
 *
 * The in-app email field on the same login screen is untouched and keeps
 * working without MicroG.
 */
@Suppress("unused")
val crazyGamesGmsCorePatch = bytecodePatch(
    name = "Google login via MicroG-RE",
    description = "Redirects Google Play Services to MicroG-RE (app.revanced.android.gms), " +
        "spoofs the original package + signing certificate for Google's OAuth check, and " +
        "forces the legacy Google Sign-In flow, so \"Continue with Google\" works on a " +
        "re-signed build. Requires MicroG-RE installed and signed in; the patched app must " +
        "replace the Play install. Does not help with stock GMS; the email login needs no patch.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_CRAZYGAMES)

    // ── Manifest half — inline resourcePatch dependency ──────────────────────
    // The DSL binds one context type per patch (javap-verified against
    // morphe-patcher-1.5.2): `bytecodePatch` gets BytecodePatchContext (no file
    // API) and `resourcePatch` gets ResourcePatchContext (get/document/delete).
    // Defined inline so PatchLoader never exposes it as a second listing entry.
    dependsOn(
        resourcePatch(
            name = "Google login via MicroG-RE (manifest)",
            description = "Declares app.revanced.android.gms in <queries> and adds the " +
                "SPOOFED_PACKAGE_NAME / SPOOFED_PACKAGE_SIGNATURE meta-data MicroG-RE reads.",
            default = false,
        ) {
            compatibleWith(COMPATIBILITY_CRAZYGAMES)

            execute {
                document("AndroidManifest.xml").use { manifest ->
                    val root = manifest.documentElement
                    val application =
                        manifest.getElementsByTagName("application").item(0) as Element

                    // The 1.7.13 manifest already has a <queries> block; create
                    // one before <application> only if a future build drops it.
                    val queries = (manifest.getElementsByTagName("queries").item(0) as? Element)
                        ?: manifest.createElement("queries").also { queries ->
                            root.insertBefore(queries, application)
                        }

                    val declaredPackages = (0 until queries.childNodes.length)
                        .mapNotNull { queries.childNodes.item(it) as? Element }
                        .filter { element -> element.tagName == "package" }
                        .map { element -> element.getAttribute("android:name") }

                    if (MICROG_PACKAGE !in declaredPackages) {
                        queries.appendChild(
                            manifest.createElement("package").apply {
                                setAttribute("android:name", MICROG_PACKAGE)
                            },
                        )
                    }

                    // <application> meta-data: the spoofing contract. MicroG-RE
                    // reads these from the *calling* app's manifest, so the key
                    // prefix is MicroG-RE's package name, not Google's.
                    fun upsertMetaData(name: String, value: String) {
                        val existing = (0 until application.childNodes.length)
                            .mapNotNull { application.childNodes.item(it) as? Element }
                            .firstOrNull { element ->
                                element.tagName == "meta-data" &&
                                    element.getAttribute("android:name") == name
                            }

                        if (existing != null) {
                            existing.setAttribute("android:value", value)
                        } else {
                            application.appendChild(
                                manifest.createElement("meta-data").apply {
                                    setAttribute("android:name", name)
                                    setAttribute("android:value", value)
                                },
                            )
                        }
                    }

                    upsertMetaData("$MICROG_PACKAGE.SPOOFED_PACKAGE_NAME", ORIGINAL_PACKAGE)
                    upsertMetaData(
                        "$MICROG_PACKAGE.SPOOFED_PACKAGE_SIGNATURE",
                        ORIGINAL_SIGNING_SHA1,
                    )
                }
            }
        },
    )

    execute {
        // ── 1. Disable the client-side Play Services availability gate ───────
        // Order matters: GooglePlayUtilityFingerprint uses the literal
        // "com.google.android.gms" as a marker, so it has to be resolved before
        // step 2 rewrites that string.
        GooglePlayUtilityFingerprint.method.returnEarly(0)
        ServiceCheckFingerprint.method.returnEarly()

        // ── 2. Repoint GMS package/action strings at MicroG-RE ───────────────
        // classDefByStrings(CONTAINS) narrows the work to the classes that
        // actually carry the package name, so we do not recompile the whole app.
        // Only exact-string literals are replaced. The same address prefix also
        // appears in values that must keep their original text:
        //   "com.google.android.gms.version"             manifest meta-data key
        //   "com.google.android.gms.ads.APPLICATION_ID"  manifest meta-data key
        //   "com.google.android.gms.measurement.*"       dynamite module ids
        // Those live in AndroidManifest.xml, which this patch only appends to —
        // renaming them in code just breaks the lookup (observed before this was
        // restricted: "A required meta-data tag in your app's
        // AndroidManifest.xml does not exist ... app.revanced.android.gms.
        // version").
        val rewritableStrings = buildSet {
            add(GMS_PACKAGE)
            addAll(GMS_AUTH_ACTIONS)
        }

        var rewrittenStrings = 0
        var rewrittenClasses = 0

        for (classDef in classDefByStrings(GMS_PACKAGE, StringComparisonType.CONTAINS)) {
            val mutableClass = mutableClassDefBy(classDef)
            var classChanged = false

            for (method in mutableClass.methods) {
                val instructions = method.implementation?.instructions ?: continue

                // Collect first: replacing an instruction resizes the backing
                // list, so mutating during iteration is unsafe.
                val indices = instructions.indices.filter { index ->
                    val instruction = instructions[index]
                    instruction.opcode == Opcode.CONST_STRING &&
                        (instruction as ReferenceInstruction)
                            .let { (it.reference as StringReference).string }
                            .let { value -> value in rewritableStrings }
                }

                for (index in indices) {
                    val instruction = method.getInstruction<OneRegisterInstruction>(index)
                    val original =
                        (instruction as ReferenceInstruction).let {
                            (it.reference as StringReference).string
                        }

                    method.replaceInstruction(
                        index,
                        BuilderInstruction21c(
                            Opcode.CONST_STRING,
                            instruction.registerA,
                            ImmutableStringReference(
                                MICROG_PACKAGE + original.removePrefix(GMS_PACKAGE),
                            ),
                        ),
                    )
                    rewrittenStrings++
                    classChanged = true
                }
            }

            if (classChanged) rewrittenClasses++
        }

        check(rewrittenStrings > 0) {
            "CrazyGames Google login (MicroG-RE): no \"$GMS_PACKAGE\" string constants found — " +
                "unexpected build?"
        }

        // ── 3. Force the legacy GoogleSignInClient path ──────────────────────
        val googleProviderMethod = UseCredentialManagerFingerprint.method
        val stringIndex =
            googleProviderMethod.indexOfFirstStringInstructionOrThrow("useCredentialManager")

        val moveResultIndex = (stringIndex + 1..stringIndex + MOVE_RESULT_LOOKAHEAD)
            .firstOrNull { index ->
                index < googleProviderMethod.implementation!!.instructions.size &&
                    googleProviderMethod.getInstruction<Instruction>(index).opcode ==
                    Opcode.MOVE_RESULT_OBJECT
            }
            ?: error(
                "CrazyGames Google login (MicroG-RE): no move-result-object after " +
                    "\"useCredentialManager\" — @capacitor-firebase/authentication layout changed?",
            )

        val resultRegister =
            googleProviderMethod.getInstruction<OneRegisterInstruction>(moveResultIndex).registerA

        googleProviderMethod.replaceInstruction(
            moveResultIndex,
            "sget-object v$resultRegister, Ljava/lang/Boolean;->FALSE:Ljava/lang/Boolean;",
        )
    }
}
