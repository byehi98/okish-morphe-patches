package app.truecloud.patches.analytics

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

// === T7 — Analysys (数美) analytics agent init ===
// Default-ENABLED SDK (initAnalysysByConfig only skips when remote config
// explicitly disables it). smali: classes6/.../AnalysysAgentUtil.smali:975
//   .method public static initAnalysys(Landroid/content/Context;)V — .registers 7
// Filter order verified: AnalysysAgent.setDebugMode → AnalysysConfig.<init>.
object InitAnalysysFingerprint : Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Landroid/content/Context;"),
    name = "initAnalysys",
    custom = { _, classDef -> classDef.type == "Lcom/juanvision/http/utils/AnalysysAgentUtil;" },
    filters = listOf(
        methodCall(definingClass = "Lcom/analysys/AnalysysAgent;", name = "setDebugMode"),
        methodCall(definingClass = "Lcom/analysys/AnalysysConfig;", name = "<init>"),
    ),
)

// === T15a — Aliyun SLS event-log sink (45 caller files) ===
// smali: classes6/.../NewAliStatLog.smali:249
//   .method public upload()V — .registers 4 (only upload()V in the class)
// Body starts by comparing logStore to the const-string "appUse".
object NewAliStatLogUploadFingerprint : Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = emptyList(),
    name = "upload",
    custom = { _, classDef -> classDef.type == "Lcom/juanvision/http/log/stat/NewAliStatLog;" },
    filters = listOf(
        string("appUse"),
    ),
)

// === T15b — STS / "Yr" data-collection dispatch (ADLogger et al.) ===
// MUST ship together with T15a — two independent telemetry pipelines.
// smali: classes5/.../StsLogManager.smali:64
//   .method public static commit(IStsLog)V — .registers 4 (only commit in class)
// Body dispatches to sLogAdapter + registered stsLogAdapters (verified).
object StsLogManagerCommitFingerprint : Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Lcom/juanvision/bussiness/log/IStsLog;"),
    name = "commit",
    custom = { _, classDef -> classDef.type == "Lcom/juanvision/bussiness/log/StsLogManager;" },
)

// === T16a — MSA OAID device identifier ===
// Patch the 2-arg overload — the 1-arg init(Context) delegates to it (verified),
// so both call paths (direct + the reflection wrapper) funnel here.
// smali: classes6/.../OAIDUtil.smali:246
//   .method public init(Landroid/content/Context;OAIDUtil$AppIdsUpdater;)V — .registers 11
// Filter order verified: MdidSdkHelper.InitCert → MdidSdkHelper.InitSdk.
object OaidUtilInitFingerprint : Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(
        "Landroid/content/Context;",
        "Lcom/juanvision/modulelogin/util/OAIDUtil\$AppIdsUpdater;",
    ),
    name = "init",
    custom = { _, classDef -> classDef.type == "Lcom/juanvision/modulelogin/util/OAIDUtil;" },
    filters = listOf(
        methodCall(definingClass = "Lcom/bun/miitmdid/core/MdidSdkHelper;", name = "InitCert"),
        methodCall(definingClass = "Lcom/bun/miitmdid/core/MdidSdkHelper;", name = "InitSdk"),
    ),
)

// === T16b — Bugly crash reporting (config-gated, default OFF — low value) ===
// Body verified: iput mContext → invoke-virtual initBugly(). Skipping init
// leaves the object in exactly the state it has under the default config.
// smali: classes4/.../BuglyManager.smali:188
//   .method public init(Landroid/content/Context;)V — .registers 2
object BuglyManagerInitFingerprint : Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf("Landroid/content/Context;"),
    name = "init",
    custom = { _, classDef -> classDef.type == "Lcom/juanvision/eseecloud30/utils/BuglyManager;" },
    filters = listOf(
        methodCall(definingClass = "Lcom/juanvision/eseecloud30/utils/BuglyManager;", name = "initBugly"),
    ),
)
