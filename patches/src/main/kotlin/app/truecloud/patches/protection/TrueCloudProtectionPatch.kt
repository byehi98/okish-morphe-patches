package app.truecloud.patches.protection

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.returnEarly
import app.truecloud.patches.analytics.BuglyManagerInitFingerprint
import app.truecloud.patches.analytics.InitAnalysysFingerprint
import app.truecloud.patches.analytics.NewAliStatLogUploadFingerprint
import app.truecloud.patches.analytics.OaidUtilInitFingerprint
import app.truecloud.patches.analytics.StsLogManagerCommitFingerprint
import app.truecloud.patches.shared.Constants.COMPATIBILITY_TRUECLOUD

@Suppress("unused")
val trueCloudProtectionPatch = bytecodePatch(
    name = "TrueCloud Protection",
    description = "Bypasses anti-emulator self-kill and disables analytics, telemetry, device-ID, and crash-reporting SDKs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_TRUECLOUD)

    execute {
        // T3 — CheckSimulator.isSimulator3 reports "emulator" → this listener calls
        // Process.killProcess(myPid()) + System.exit(0). The listener is the ONLY
        // kill site (patching here is safer than neutering isSimulator3 itself,
        // which spawns a scanning thread as a side effect).
        MyApplicationSimulatorKillFingerprint.method.returnEarly()

        // Telemetry opt-out — flagged OFF by default. All five chokes ship together
        // so no half-disabled pipeline is left behind:
        //   T7   AnalysysAgentUtil.initAnalysys — Analysys (数美) analytics, default-ENABLED
        //   T15a NewAliStatLog.upload           — Aliyun SLS event log
        //   T15b StsLogManager.commit           — STS/"Yr" dispatch (ADLogger etc.);
        //        independent of T15a — BOTH must be patched for full opt-out
        //   T16a OAIDUtil.init (2-arg)          — MSA OAID device identifier
        //   T16b BuglyManager.init              — Bugly crash reporting (config-gated,
        //        default OFF — included so the pipeline stays fully inert)
        // All bodies are fire-and-forget initializers/sinks; return-void is safe.
        InitAnalysysFingerprint.method.returnEarly()
        NewAliStatLogUploadFingerprint.method.returnEarly()
        StsLogManagerCommitFingerprint.method.returnEarly()
        OaidUtilInitFingerprint.method.returnEarly()
        BuglyManagerInitFingerprint.method.returnEarly()
    }
}
