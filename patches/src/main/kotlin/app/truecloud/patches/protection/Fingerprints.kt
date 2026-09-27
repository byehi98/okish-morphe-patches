package app.truecloud.patches.protection

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags

// === T3 — Anti-emulator process kill ===
// Anonymous OnCheckSimulatorListener: when CheckSimulator.isSimulator3 reports a
// VM the app kills itself (Process.killProcess + System.exit).
// smali: classes4/com/juanvision/eseecloud30/application/MyApplication$1.smali:38
//   .method public onResult(Z)V — .registers 2
// Class header verified: .class Lcom/juanvision/eseecloud30/application/MyApplication$1;
//   implements Lcom/juan/base/utils/rom/CheckSimulator$OnCheckSimulatorListener;
// Only onResult in the class; body's killProcess call used as structural filter.
object MyApplicationSimulatorKillFingerprint : Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf("Z"),
    name = "onResult",
    custom = { _, classDef ->
        classDef.type == "Lcom/juanvision/eseecloud30/application/MyApplication\$1;"
    },
    filters = listOf(
        methodCall(definingClass = "Landroid/os/Process;", name = "killProcess"),
    ),
)
