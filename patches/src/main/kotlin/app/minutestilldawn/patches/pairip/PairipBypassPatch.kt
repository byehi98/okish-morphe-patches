package app.minutestilldawn.patches.pairip

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.encodedValue.MutableStringEncodedValue
import app.morphe.patcher.util.smali.toInstruction
import app.minutestilldawn.patches.shared.Constants.COMPATIBILITY_20MTD
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.value.ImmutableStringEncodedValue
import com.android.tools.smali.dexlib2.util.FieldUtil

/**
 * PairIP bypass — prerequisite for every other 20 Minutes Till Dawn patch.
 *
 * 20 Minutes Till Dawn 8.7 / 1102 ships Google's PairIP ("app protection"):
 *
 *   AndroidManifest android:name="com.pairip.application.Application"
 *       extends com.safedk.android.SafeDKApplication
 *   com/pairip/SignatureCheck.verifyIntegrity(Context)   -> re-sign gate
 *   com/pairip/licensecheck/LicenseClient.checkLicense   -> Play licence gate
 *   com/pairip/VMRunner                                  -> native VM (libpairipcore.so)
 *   com/pairip/StartupLauncher.launch()                  -> runs startup VM program
 *
 * Three distinct layers:
 *
 * 1. Signature gate. verifyIntegrity SHA-256(Base64)-hashes PackageInfo.signatures[0]
 *    and throws SignatureTamperedException ("Apk signature is invalid.") unless it
 *    matches expectedSignature or ALLOWLISTED_SIG. It runs from
 *    Application.attachBaseContext before super, so a re-signed APK dies instantly.
 *
 * 2. License gate. checkLicense bounces sideloaded installs to Play.
 *
 * 3. Virtualised methods. PairIP rewrites selected methods into reflective
 *    Method.invoke on a static Method field, or a VMRunner.invoke(name, args) call.
 *    libpairipcore.so decrypts and executes that bytecode, and the key is bound to
 *    the install signature — so on a re-signed APK the VM faults natively
 *    (SIGSEGV in libpairipcore.so) and the holder fields stay null, so call sites NPE.
 *
 * Since layer 3 cannot be repaired from DEX, this patch replaces the invoke stubs with
 * real bodies and skips the startup VM program so the fault is never reached:
 *
 *   UnityPlayerActivity  onStart/onStop/onPause/onResume/onDestroy -> delegate to
 *                        UnityPlayer, matching the untampered Unity template
 *   FacebookContentProvider.onCreate -> return true
 *   SafeDKApplication.onCreate       -> call super
 *   StartupLauncher.launch()         -> skip the VM program that faults natively
 *   VMRunner.invoke(...)             -> null at the single choke point
 *   static String pool holders       -> filled with "" (real values are unrecoverable)
 *
 * Remaining caveat: the app still registers the PairIP Application/provider and still
 * runs Play integrity checks, so this is a best-effort client-side bypass for a
 * re-signed build, not a full de-PairIP.
 */
@Suppress("unused")
val pairipBypassPatch = bytecodePatch(
    name = "Bypass PairIP signature, license & VM gate",
    description = "Neutralises the PairIP signature and license gates, restores the virtualised lifecycle methods with real Unity delegating bodies, and skips the startup VM program. Required for every other 20 Minutes Till Dawn patch. Credits: fynnaly."
) {
    compatibleWith(COMPATIBILITY_20MTD)

    execute {
        // Layer 1: signature gate — return before the cert hash comparison.
        SignatureCheckVerifyFingerprint.method.addInstructions(0, "return-void")

        // Layer 2: license gate — return before the Play licence bind.
        LicenseClientCheckFingerprint.method.addInstructions(0, "return-void")

        // Layer 3a: do not run the startup VM program — it faults natively
        // (SIGSEGV in libpairipcore.so) on a re-signed install.
        StartupLauncherFingerprint.method.addInstructions(0, "return-void")

        // Layer 3b: restore the lifecycle methods PairIP virtualised.
        UnityPlayerActivityOnStartFingerprint.method.addInstructions(
            0,
            """
                invoke-super {p0}, Landroid/app/Activity;->onStart()V
                iget-object v0, p0, Lcom/unity3d/player/UnityPlayerActivity;->mUnityPlayer:Lcom/unity3d/player/UnityPlayer;
                invoke-virtual {v0}, Lcom/unity3d/player/UnityPlayer;->onStart()V
                return-void
            """.trimIndent()
        )
        UnityPlayerActivityOnStopFingerprint.method.addInstructions(
            0,
            """
                invoke-super {p0}, Landroid/app/Activity;->onStop()V
                iget-object v0, p0, Lcom/unity3d/player/UnityPlayerActivity;->mUnityPlayer:Lcom/unity3d/player/UnityPlayer;
                invoke-virtual {v0}, Lcom/unity3d/player/UnityPlayer;->onStop()V
                return-void
            """.trimIndent()
        )
        UnityPlayerActivityOnPauseFingerprint.method.addInstructions(
            0,
            """
                invoke-super {p0}, Landroid/app/Activity;->onPause()V
                iget-object v0, p0, Lcom/unity3d/player/UnityPlayerActivity;->mUnityPlayer:Lcom/unity3d/player/UnityPlayer;
                invoke-virtual {v0}, Lcom/unity3d/player/UnityPlayer;->onPause()V
                return-void
            """.trimIndent()
        )
        UnityPlayerActivityOnResumeFingerprint.method.addInstructions(
            0,
            """
                invoke-super {p0}, Landroid/app/Activity;->onResume()V
                iget-object v0, p0, Lcom/unity3d/player/UnityPlayerActivity;->mUnityPlayer:Lcom/unity3d/player/UnityPlayer;
                invoke-virtual {v0}, Lcom/unity3d/player/UnityPlayer;->onResume()V
                return-void
            """.trimIndent()
        )
        // onDestroy: tear the player down first, then let the superclass run.
        UnityPlayerActivityOnDestroyFingerprint.method.addInstructions(
            0,
            """
                iget-object v0, p0, Lcom/unity3d/player/UnityPlayerActivity;->mUnityPlayer:Lcom/unity3d/player/UnityPlayer;
                invoke-virtual {v0}, Lcom/unity3d/player/UnityPlayer;->destroy()V
                invoke-super {p0}, Landroid/app/Activity;->onDestroy()V
                return-void
            """.trimIndent()
        )

        // Layer 3c: the Facebook provider must report success instead of invoking
        // its dead VM reference.
        FacebookContentProviderOnCreateFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x1
                return v0
            """.trimIndent()
        )

        // Layer 3d: SafeDK's onCreate wraps the real Application.onCreate; calling
        // super restores tracing/attribution without the dead VM reference.
        SafeDKApplicationOnCreateFingerprint.method.addInstructions(
            0,
            """
                invoke-super {p0}, Landroid/app/Application;->onCreate()V
                return-void
            """.trimIndent()
        )

        // Layer 4: no paywall, no error dialog, ever.
        LicenseClientStartPaywallFingerprint.method.addInstructions(0, "return-void")
        LicenseClientStartErrorDialogFingerprint.method.addInstructions(0, "return-void")

        // Layer 5: close the native VM for every virtualised stub at the single
        // entry point. The original body (log, readByteCode, executeVM) is dead code.
        VMRunnerInvokeFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return-object v0
            """.trimIndent()
        )

        // Layer 6: WorkManager / GMS workers must return a non-null Result.
        // Built through the dexlib2 API because the smali lexer chokes on the `$`
        // in `ListenableWorker$Result`.
        val successRef = ImmutableMethodReference(
            "Landroidx/work/ListenableWorker\$Result;",
            "success",
            emptyList(),
            "Landroidx/work/ListenableWorker\$Result;"
        )
        val successResult = listOf(
            BuilderInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0, successRef),
            BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
            BuilderInstruction11x(Opcode.RETURN_OBJECT, 0)
        )
        CombineContinuationsWorkerFingerprint.method.addInstructions(0, successResult)
        OfflinePingSenderFingerprint.method.addInstructions(0, successResult)

        // Layer 7: Tenjin's startup thread reads a pool holder string and dies with
        // StringBuilder.<init>(null). It is attribution-only; skip it.
        TenjinStartupRunnableFingerprint.method.addInstructions(0, "return-void")

        // Layer 8: AppLovin's duplicate-key throw. With all pool strings set to ""
        // the 388 AppLovin settings collide and sj.a() throws in its clinit.
        // Branch over the assert so duplicates overwrite instead.
        val appLovinMethod = AppLovinSettingRegisterFingerprint.method
        val duplicateKeyBranch = appLovinMethod.implementation
            ?.instructions
            ?.indexOfFirst { it.opcode == Opcode.IF_NEZ }
            ?.takeIf { it >= 0 }
            ?: 12
        appLovinMethod.replaceInstruction(
            duplicateKeyBranch,
            "nop".toInstruction(appLovinMethod)
        )

        // Layer 9: the string pool itself. Every PairIP pool holder class carries
        // static String fields the native VM was supposed to fill at runtime (it
        // cannot — the bytecode is encrypted and keyed to the original signature).
        // Consumers then die on StringBuilder.<init>(null) / String.length() NPEs.
        // Fill them with "" so consumers get a valid (if empty) string.
        // Fields already assigned a literal are left untouched.
        val staticStringPool = { cls: ClassDef ->
            cls.methods.count() == 0 &&
                cls.fields.count() >= 10 &&
                cls.fields.all { FieldUtil.isStatic(it) && it.type == "Ljava/lang/String;" }
        }
        classDefForEach { cls ->
            if (!staticStringPool(cls)) return@classDefForEach
            val mutableCls = mutableClassDefBy(cls)
            mutableCls.staticFields.forEach { field ->
                field.setInitialValue(
                    MutableStringEncodedValue(ImmutableStringEncodedValue(""))
                )
            }

            // Targeted restores. R8 re-shuffles the pool class/field names on every build,
            // so the values consumed by ordinary AndroidX code (not by the PairIP VM) must
            // be re-mapped per version. Blanking these breaks real logic:
            //
            //   WorkSpecDao_Impl.getRecentlyCompletedWork() reads the `run_attempt_count`
            //   column through XaivvuwuA->wzhRjA, and QueueEventDao_Impl.getEventsFromParams()
            //   reads `params` through xpmjMmJpaeAPK->LTIUhCXiALq. With "" every cursor
            //   lookup throws:
            //     IllegalArgumentException: column '' does not exist. Available columns: …
            //
            //   AnalyticsHWSdk's SQLiteOpenHelper (`com/dobest/analyticshwsdk/c/d`) builds
            //   every CREATE TABLE with luFkMTMuZpvz->bgkioDosd as the first column name.
            //   It was a literal `_id` in 8.6; 8.7 moved it into the pool, so with "" the
            //   SDK throws on init:
            //     SQLiteException: AUTOINCREMENT is only allowed on an INTEGER PRIMARY KEY
            //     … CREATE TABLE IF NOT EXISTS sessions ( INTEGER PRIMARY KEY autoincrement,…
            //   and the Unity `Loading` coroutine dies, leaving the game on the loading screen.
            //
            // All names are confirmed by the literal reads in the 8.6 build at the same
            // instruction positions (see analysis/minutestilldawn/notes/build.md).
            listOf(
                Triple(
                    "Lcom/bykv/vk/openvk/preload/falconx/loader/nWuw/XaivvuwuA;",
                    "wzhRjA",
                    "run_attempt_count",
                ),
                Triple(
                    "Lcom/bykv/vk/openvk/bg/bg/bg/bX/mGMm/xpmjMmJpaeAPK;",
                    "LTIUhCXiALq",
                    "params",
                ),
                Triple(
                    "Lcom/ironsource/mediationsdk/adunit/data/rRSI/luFkMTMuZpvz;",
                    "bgkioDosd",
                    "_id",
                ),
            ).forEach { (owner, fieldName, value) ->
                if (cls.type == owner) {
                    mutableCls.staticFields
                        .firstOrNull { it.name == fieldName }
                        ?.setInitialValue(
                            MutableStringEncodedValue(
                                ImmutableStringEncodedValue(value)
                            )
                        )
                }
            }
        }
    }
}
