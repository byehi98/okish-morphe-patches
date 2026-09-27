package app.truecloud.patches.update

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags

// === T2 — Force/normal update dialog choke ===
// Server flag `upgrade == 1` gates ALL update UI (login-screen forced dialog,
// Me-tab auto check, needShowDialog notice); `force == 1` makes it dismissable.
// smali: classes6/com/juanvision/http/pojo/user/VersionInfoResp.smali:108
//   .method public getUpgrade()I — .registers 2
//   iget v0, p0, Lcom/juanvision/http/pojo/user/VersionInfoResp;->upgrade:I
// Only getUpgrade()I definition in the class (verified).
object VersionInfoRespGetUpgradeFingerprint : Fingerprint(
    returnType = "I",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = emptyList(),
    name = "getUpgrade",
    custom = { _, classDef -> classDef.type == "Lcom/juanvision/http/pojo/user/VersionInfoResp;" },
    filters = listOf(
        fieldAccess(smali = "Lcom/juanvision/http/pojo/user/VersionInfoResp;->upgrade:I"),
    ),
)

// === T13 — Login-screen forced update probe ===
// smali: classes6/com/juanvision/modulelogin/util/VersionDownloadHelper.smali:929
//   .method public checkForce()V — .registers 4
// Body verified: builds VersionDownloadHelper$1 observer then
// invoke-static EseeUserApi.checkAppUpdate(...) → mCheckVersionTaskId.
object VersionDownloadHelperCheckForceFingerprint : Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = emptyList(),
    name = "checkForce",
    custom = { _, classDef -> classDef.type == "Lcom/juanvision/modulelogin/util/VersionDownloadHelper;" },
    filters = listOf(
        methodCall(
            definingClass = "Lcom/juanvision/http/business/EseeUserApi;",
            name = "checkAppUpdate",
        ),
    ),
)

// T13 companion — Me-tab update notice gate (force ⇒ always; notify_method 1/2
// ⇒ once per version). smali: same file:421
//   .method public static needShowDialog(VersionInfoResp)Z — .registers 5
// Filter order verified: getUpgrade → getForce → getNotify_method.
object VersionDownloadHelperNeedShowDialogFingerprint : Fingerprint(
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Lcom/juanvision/http/pojo/user/VersionInfoResp;"),
    name = "needShowDialog",
    custom = { _, classDef -> classDef.type == "Lcom/juanvision/modulelogin/util/VersionDownloadHelper;" },
    filters = listOf(
        methodCall(
            definingClass = "Lcom/juanvision/http/pojo/user/VersionInfoResp;",
            name = "getNotify_method",
        ),
    ),
)

// === T14 — Me-tab auto update check ===
// smali: classes9/.../X35PersonalCentrePresenter.smali:1741
//   .method public checkVersionUpgrade(Z)V — .registers 6
// Body verified: EseeUserApi.checkAppUpdate(...) at relative index 148.
object CheckVersionUpgradeFingerprint : Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf("Z"),
    name = "checkVersionUpgrade",
    custom = { _, classDef ->
        classDef.type == "Lcom/zasko/modulemain/mvpdisplay/presenter/X35PersonalCentrePresenter;"
    },
    filters = listOf(
        methodCall(
            definingClass = "Lcom/juanvision/http/business/EseeUserApi;",
            name = "checkAppUpdate",
        ),
    ),
)
