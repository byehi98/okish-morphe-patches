package app.plagueinc.patches.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import app.plagueinc.patches.shared.Constants.COMPATIBILITY_PLAGUEINC

/**
 * Fixes custom scenario downloads.
 *
 * Root cause: the scenario server (s.ndemiccreations.com/plague) rejects
 * requests whose User-Agent is the default Dalvik one (HTTP 400). The app's
 * HttpManager uses Volley/HurlStack which never sets a User-Agent, so every
 * metadata fetch (scenarios_download?id=...) fails with 400 and the download
 * never starts. Setting the JVM http.agent property at app startup makes
 * HttpURLConnection (and thus Volley) send "PlagueInc/1.27.3", which the
 * server accepts.
 *
 * Implementation: add a helper method `morpheSetHttpAgent()` to
 * PlagueApplication (static, .registers 3) and invoke it at the very start of
 * PlagueApplication.onCreate() (no registers needed for the invoke-static).
 */
@Suppress("unused")
val plagueIncCustomScenarioDownloadFixPatch = bytecodePatch(
    name = "Plague Inc. Custom Scenario Download Fix",
    description = "Fixes custom scenario downloads by setting the HTTP User-Agent the scenario server requires.",
    default = true
) {
    compatibleWith(COMPATIBILITY_PLAGUEINC)

    execute {
        val mutableClass = PlagueApplicationOnCreateFingerprint.classDef as MutableClass

        // Build the helper method with enough registers (3) for the two strings.
        val impl = MutableMethodImplementation(3)
        val helper = MutableMethod(
            ImmutableMethod(
                mutableClass.type,
                "morpheSetHttpAgent",
                emptyList(),
                "V",
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                null,
                null,
                impl
            )
        ).apply {
            addInstructions(0, """
                const-string v0, "http.agent"
                const-string v1, "PlagueInc/1.27.3"
                invoke-static {v0, v1}, Ljava/lang/System;->setProperty(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
                move-result-object v2
                return-void
            """)
        }
        mutableClass.methods.add(helper)

        // Invoke the helper at the very start of onCreate (no registers needed).
        PlagueApplicationOnCreateFingerprint.method.addInstructions(0, """
            invoke-static {}, Lcom/miniclip/plagueinc/PlagueApplication;->morpheSetHttpAgent()V
        """)
    }
}