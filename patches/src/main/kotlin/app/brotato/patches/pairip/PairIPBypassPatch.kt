package app.brotato.patches.pairip

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.brotato.patches.shared.Constants.COMPATIBILITY_BROTATO

/**
 * **Brotato — PairIP bypass (L1).** Required for every other Brotato patch: without it a
 * re-signed build dies inside `AndroidManifest`'s `com.pairip.application.Application`
 * before Unity starts.
 *
 * Brotato 1.3.951 ships **PairIP v2**:
 *
 * ```
 * com.pairip.application.Application   extends com.safedk.android.SafeDKApplication
 *   <clinit>                           StartupLauncher.launch() -> VMRunner.invoke(...)
 *   attachBaseContext                  VMRunner.setContext
 *                                      SignatureCheck.verifyIntegrity   <- re-sign gate
 *                                      LicenseClient.checkLicense       <- Play licence gate
 * com.pairip.licensecheck.LicenseClient   v2 state machine (ILicenseV2ResultListener,
 *                                         checkLicenseInternal, RepeatedCheckMetadata)
 * com.pairip.licensecheck.LicenseActivity paywall / "Something went wrong" + exitApp
 * com.pairip.VMRunner + libpairipcore.so   native VM
 * ```
 *
 * Hooks (all at instruction 0, register-safe, return types matched):
 *
 * | method | body | why |
 * |---|---|---|
 * | `StartupLauncher.launch()` | `return-void` | its VM program is signature-keyed and faults in `ExecuteProgram` on a re-signed build — the actual boot crash |
 * | `SignatureCheck.verifyIntegrity(Context)` | `return-void` | the only DEX caller is `attachBaseContext`; kills `SignatureTamperedException` |
 * | `SignatureCheck.verifySignatureMatches(String)` | `return true` | native callback path in `libpairipcore.so` |
 * | `LicenseClient.checkLicense(Context)` | `return-void` | outermost licence entry; nothing downstream starts |
 * | `LicenseClient$1.run()` | `return-void` | the `System.exit(0)` action |
 * | `LicenseActivity.onStart()` | `return-void` | paywall screen inert |
 * | `SafeDKApplication.onCreate()` | `return-void` | SafeDK ad-mediation init |
 * | `FirebaseInitProvider.onCreate()` | `return false` | normally returns false; consumes the string vault |
 * | ironSource / LevelPlay / Vungle / MBridge `onCreate()` | `return true` | remaining ad-SDK auto-init |
 * | `MobileAds.initialize(Context)` + `(Context, listener)` | `return-void` | GMS Ads auto-init; its client reflects vault strings into `getDeclaredField` |
 * | `zzeu.zzD(Context)` | `return-void` | the single client-creation choke point — blocks `zzk.run()` → `GassClient` → `zzazk` from every caller (incl. AppLovin's `disableMediationAdapterInitialization`) |
 * | `zzgae.zza(Context,String,String)` | `return null` | Gass request factory; `null` is the callers' "service unavailable" contract |
 *
 * ## Why the startup program is skipped
 *
 * `StartupLauncher.launch()` runs the VM program `assets/FboEAJrc2eprcyK4` (566,567 bytes,
 * magic `\x00IAP`, high entropy). That program is what **restores the 1,468 static strings**
 * of the 32 obfuscated vault classes — the field declarations exist in DEX but nothing in Java
 * writes them. It is also the point where `libpairipcore.so` dies: the program is keyed to the
 * original signing certificate, so on a re-signed build the VM faults **on every ABI** —
 * genuine arm64 hardware dies in `libpairipcore.so+0x3a600` (`SIGSEGV`, `SEGV_ACCERR`, the
 * FNV-1a opcode handler reading an unmapped page), and Waydroid reproduces it for x86_64,
 * houdini-translated arm64 and x86. There is no environment where letting the VM run is an
 * option.
 *
 * With the VM off, every vault consumer sees `null`, so the SDK auto-init entry points that
 * read it are gated too (T6–T11). All 50 DEX classes touching the vault are ad/analytics
 * SDKs, `SafeDK` and a few libraries — **none of the game** (IL2CPP metadata v29 dumps
 * cleanly, no virtualized method bodies), so this costs nothing in gameplay.
 *
 * This is a client-side bypass only. Play Integrity / licensing *server* decisions, cloud
 * saves and anything receipt-backed remain server-side and are not addressed here.
 */
@Suppress("unused")
val pairipBypassPatch = bytecodePatch(
    name = "Bypass PairIP signature & license check",
    description = "Neutralises the PairIP v2 signature gate (SignatureTamperedException) and the " +
        "Play licence flow (service bind, signed-response validation, paywall and its delayed " +
        "System.exit) so a re-signed Brotato build launches. Required for every other Brotato " +
        "patch.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_BROTATO)

    execute {
        // T0 — `StartupLauncher.launch()` runs the startup VM program
        // (`assets/FboEAJrc2eprcyK4`), which is the only thing that ever assigns the 32
        // obfuscated static string-vault classes (1,468 fields, see VaultStrings.kt).
        //
        // That program is **keyed to the original signing certificate**, so it cannot run on a
        // re-signed build on *any* ABI: verified on genuine arm64 hardware (Redmi K40,
        // Android 17) the untouched program dies in `libpairipcore.so+0x3a600` with
        // `Fatal signal 11 (SIGSEGV), code 2 (SEGV_ACCERR)` on a page-aligned address, the
        // process is killed and the launcher restarts it in a ~5 s loop. The same fault was
        // already known for x86_64 (FNV-1a opcode handler) and houdini. There is therefore no
        // environment where letting the VM run is an option.
        //
        // So the program is skipped and the vault is materialised here instead. The values
        // must be **non-null and distinct**: several SDKs register every vault string as a
        // settings key (e.g. `AppLovinSdkSettings` in `Lcom/applovin/impl/c5;`, whose
        // `a(String, Object)` throws `IllegalArgumentException("Setting has already been
        // used")`), so a shared `""` breaks their `<clinit>` with `ExceptionInInitializerError`.
        // The field's own reference is unique and inert. Consumers that *require* a real value
        // (reflection, `Charset.forName`) are gated individually — see T13/T14 and T16.
        PairipStartupLauncherFingerprint.method.addInstructions(
            0,
            buildString {
                VAULT_FIELD_REFS.forEach { ref ->
                    appendLine("const-string v1, \"$ref\"")
                    appendLine("sput-object v1, $ref:Ljava/lang/String;")
                }
                appendLine("return-void")
            },
        )

        // T1 — signature gate: return before the cert hash is even computed.
        PairipVerifyIntegrityFingerprint.method.addInstructions(0, "return-void")

        // T2 — native callback variant of the same check.
        PairipVerifySignatureMatchesFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x1
                return v0
            """.trimIndent()
        )

        // T3 — licence entry: no bind, no response validation, no paywall.
        PairipCheckLicenseFingerprint.method.addInstructions(0, "return-void")

        // T4 — the delayed System.exit(0) action.
        PairipExitActionFingerprint.method.addInstructions(0, "return-void")

        // T5 — paywall activity, inert if it is ever launched by something else.
        PairipLicenseActivityOnStartFingerprint.method.addInstructions(0, "return-void")

        // T6 — SafeDK ad-mediation init (runs after the providers, reads the vault).
        SafedkApplicationOnCreateFingerprint.method.addInstructions(0, "return-void")

        // T7 — Firebase auto-init. Normal body already returns false; skip the try block that
        // dereferences a null vault string (`google_storage_bucket`).
        FirebaseInitProviderOnCreateFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return v0
            """.trimIndent()
        )

        // T8–T11 — remaining SDK init providers installed after Firebase in the manifest.
        listOf(
            IronsourceLifecycleProviderOnCreateFingerprint,
            LevelPlayLifecycleProviderOnCreateFingerprint,
            VungleProviderOnCreateFingerprint,
            MbComponentLifecycleProviderOnCreateFingerprint,
        ).forEach { fingerprint ->
            fingerprint.method.addInstructions(
                0,
                """
                    const/4 v0, 0x1
                    return v0
                """.trimIndent()
            )
        }

        // T13/T14 — GMS Ads auto-init: both `MobileAds.initialize` overloads. These stop the
        // SDK from starting its own background init.
        GmsMobileAdsInitializeFingerprint.method.addInstructions(0, "return-void")
        GmsMobileAdsInitializeWithListenerFingerprint.method.addInstructions(0, "return-void")

        // T16 — the real choke point: `zzeu.zzD(Context)` creates the local GMS Ads client.
        // AppLovin's Google adapter calls `MobileAds.disableMediationAdapterInitialization`
        // during its own init, which calls `zzeu.zzm` → `zzD` → `new zzk(...)`; the `zzk`
        // constructor schedules `zzk.run()` → `zzi.run()` → `zzk.zzn()` → `zzbbg.zza()` →
        // `zzgaj.<clinit>` → `zzazk`'s reflective protobuf schema → `RuntimeException` on a
        // synthesised vault string → process death and the relaunch loop. Keeping `zzl` null
        // (the SDK's own uninitialised state) prevents any of that; every caller of `zzD`
        // already checks `if-nez zzl` afterwards.
        GmsAdsEnsureClientFingerprint.method.addInstructions(0, "return-void")

        // T15 — Gass requests (GMS Ads signal service). `zzazk`, the response model, cannot be
        // instantiated at all on a re-signed build: its protobuf builder resolves field names
        // by reflection and those names are vault values, so `<clinit>`/`zzl()` throws
        // `ExceptionInInitializerError`. Returning the SDK's own "service unavailable" default
        // is therefore not an option — every Gass request returns `null` instead, which is
        // exactly what `zzgsc` null-checks for and what `zzbbq`/`zzbcp` already produce from
        // their catch-all. No service bind, no `GassClient` thread, no model.
        GmsGassRequestFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return-object v0
            """.trimIndent(),
        )

        // T12 — the rebuilt APK renumbers dex files, so the shipped baseline profile no
        // longer matches; ProfileInstaller NPEs on a background thread and kills the process
        // a few seconds into Unity startup. Disable the transcode/write entirely.
        ProfileInstallerWriteProfileFingerprint.method.addInstructions(0, "return-void")
    }
}
