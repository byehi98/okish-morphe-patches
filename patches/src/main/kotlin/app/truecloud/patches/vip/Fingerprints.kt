package app.truecloud.patches.vip

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

// ════════════════════════════════════════════════════════════════════
// TIER 1 — entitlement + trial gates
// ════════════════════════════════════════════════════════════════════

// T1-1 — Central cloud entitlement `hasBought(I)Z`. The three concrete
// CloudAPI implementations (BaseCloudHelper is abstract and does not declare
// it; verified: only these 3 defs + the interface exist across all 10 DEXes).
// smali: classes6/com/juanvision/modulelist/helper/wrapper/
//   CloudHelper.smali:4194 · LvCloudHelper.smali:2299 · VNCloudHelper.smali:1662
// All `.method public hasBought(I)Z` with `.registers 3`.
// Filter order verified in all three bodies: DeviceWrapper.getCameraInfo →
// CameraInfo.getCloud_id (Lv/VN bodies are the same shape minus
// getServiceIdFromShare).
object CloudHelperHasBoughtFingerprint : Fingerprint(
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf("I"),
    name = "hasBought",
    custom = { _, classDef ->
        classDef.type == "Lcom/juanvision/modulelist/helper/wrapper/CloudHelper;"
    },
    filters = listOf(
        methodCall(
            definingClass = "Lcom/juanvision/modulelist/pojo/wrapper/DeviceWrapper;",
            name = "getCameraInfo",
        ),
        methodCall(
            definingClass = "Lcom/juanvision/http/pojo/device/CameraInfo;",
            name = "getCloud_id",
        ),
    ),
)

object LvCloudHelperHasBoughtFingerprint : Fingerprint(
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf("I"),
    name = "hasBought",
    custom = { _, classDef ->
        classDef.type == "Lcom/juanvision/modulelist/helper/wrapper/LvCloudHelper;"
    },
    filters = listOf(
        methodCall(
            definingClass = "Lcom/juanvision/modulelist/pojo/wrapper/DeviceWrapper;",
            name = "getCameraInfo",
        ),
        methodCall(
            definingClass = "Lcom/juanvision/http/pojo/device/CameraInfo;",
            name = "getCloud_id",
        ),
    ),
)

object VNCloudHelperHasBoughtFingerprint : Fingerprint(
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf("I"),
    name = "hasBought",
    custom = { _, classDef ->
        classDef.type == "Lcom/juanvision/modulelist/helper/wrapper/VNCloudHelper;"
    },
    filters = listOf(
        methodCall(
            definingClass = "Lcom/juanvision/modulelist/pojo/wrapper/DeviceWrapper;",
            name = "getCameraInfo",
        ),
        methodCall(
            definingClass = "Lcom/juanvision/http/pojo/device/CameraInfo;",
            name = "getCloud_id",
        ),
    ),
)

// T1-4 — WeChat-call package entitlement.
// smali: classes6/.../WechatCallHelper.smali:900
//   .method public isWechatCallIsService(I)Z — .registers 7
// Filter order verified: getWechatCallServiceInfo → (DateUtil.getCurrentTimeInSec
// between them) → ServiceInfo.getEndTime.
object WechatCallIsServiceFingerprint : Fingerprint(
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf("I"),
    name = "isWechatCallIsService",
    custom = { _, classDef ->
        classDef.type == "Lcom/juanvision/modulelist/helper/wrapper/WechatCallHelper;"
    },
    filters = listOf(
        methodCall(
            definingClass = "Lcom/juanvision/modulelist/helper/wrapper/WechatCallHelper;",
            name = "getWechatCallServiceInfo",
        ),
        methodCall(
            definingClass = "Lcom/juanvision/http/pojo/device/ServiceInfo;",
            name = "getEndTime",
        ),
    ),
)

// T1-5 — Alarm replay duration lock (6 s → 30 s for non-cloud users).
// smali: classes9/.../AlertMessageDisplayConfigPresenter.smali:3036
//   .method public configMaxDuration()V — .registers 4
// Filter order verified: DeviceWrapper.getAlarm → AlarmMessageAPI.updatePMNodeIfNeed
// → CloudAPI.hasBought → HabitCache.getAlarmReplayDurationForNoCloudUser.
object ConfigMaxDurationFingerprint : Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = emptyList(),
    name = "configMaxDuration",
    custom = { _, classDef ->
        classDef.type == "Lcom/zasko/modulemain/mvpdisplay/presenter/AlertMessageDisplayConfigPresenter;"
    },
    filters = listOf(
        methodCall(
            definingClass = "Lcom/juanvision/modulelist/pojo/wrapper/DeviceWrapper;",
            name = "getAlarm",
        ),
        methodCall(
            definingClass = "Lcom/juanvision/modulelist/absInterface/AlarmMessageAPI;",
            name = "updatePMNodeIfNeed",
        ),
        methodCall(
            definingClass = "Lcom/juanvision/modulelist/absInterface/CloudAPI;",
            name = "hasBought",
        ),
        methodCall(
            definingClass = "Lcom/zasko/commonutils/cache/HabitCache;",
            name = "getAlarmReplayDurationForNoCloudUser",
        ),
    ),
)

// T1-8 — Trial preview countdown. count() both increments the counter and
// returns the timeout result; hasTimeOut() covers other consumers.
// smali: classes9/com/zasko/modulemain/dialog/IOT4GTrailTimeTipsPopupWindow.smali
//   count()Z :487 (.registers 5) · hasTimeOut()Z :607 (.registers 3)
// hasTimeOut body verified: iget currentCount → iget maxCount → if-lt compare.
object TrailTimeCountFingerprint : Fingerprint(
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = emptyList(),
    name = "count",
    custom = { _, classDef ->
        classDef.type == "Lcom/zasko/modulemain/dialog/IOT4GTrailTimeTipsPopupWindow;"
    },
    filters = listOf(
        fieldAccess(smali = "Lcom/zasko/modulemain/dialog/IOT4GTrailTimeTipsPopupWindow;->currentCount:I"),
    ),
)

object TrailTimeHasTimeOutFingerprint : Fingerprint(
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = emptyList(),
    name = "hasTimeOut",
    custom = { _, classDef ->
        classDef.type == "Lcom/zasko/modulemain/dialog/IOT4GTrailTimeTipsPopupWindow;"
    },
    filters = listOf(
        fieldAccess(smali = "Lcom/zasko/modulemain/dialog/IOT4GTrailTimeTipsPopupWindow;->currentCount:I"),
        fieldAccess(smali = "Lcom/zasko/modulemain/dialog/IOT4GTrailTimeTipsPopupWindow;->maxCount:I"),
    ),
)

// T1-9 — Trial-timeout playback gate. Three concrete bodies exist across all
// DEXes (the two *Contact$Presenter entries are abstract interface declarations):
//   LiveControlPresenter         — full gate body (.registers 4) → patched
//   CommonEventControlPresenter  — mDisPlayTag-branched body (.registers 5) → patched
//   CloudEventControlPresenter   — ALREADY hard-returns false (.registers 2) → no edit needed
// smali: classes9/.../presenter/LiveControlPresenter.smali:6331
//   Filter order verified: LTEAPI.isSupport → const-string "preview_with_playback".
object LiveMaybeOnTrialTimeOutFingerprint : Fingerprint(
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = emptyList(),
    name = "maybeOnTrialTimeCountTimeOut",
    custom = { _, classDef ->
        classDef.type == "Lcom/zasko/modulemain/mvpdisplay/presenter/LiveControlPresenter;"
    },
    filters = listOf(
        methodCall(
            definingClass = "Lcom/juanvision/modulelist/absInterface/LTEAPI;",
            name = "isSupport",
        ),
        string("preview_with_playback"),
    ),
)

// smali: classes9/.../presenter/CommonEventControlPresenter.smali:6316
//   Filter order verified: const-string "preview_with_playback" precedes the
//   IOTOnTrialTipsTool.getTipsTimeOutTag invoke in the first branch.
object CommonEventMaybeOnTrialTimeOutFingerprint : Fingerprint(
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = emptyList(),
    name = "maybeOnTrialTimeCountTimeOut",
    custom = { _, classDef ->
        classDef.type == "Lcom/zasko/modulemain/mvpdisplay/presenter/CommonEventControlPresenter;"
    },
    filters = listOf(
        string("preview_with_playback"),
        methodCall(
            definingClass = "Lcom/juanvision/modulelist/util/IOTOnTrialTipsTool;",
            name = "getTipsTimeOutTag",
        ),
    ),
)

// ════════════════════════════════════════════════════════════════════
// TIER 2 — secondary eligibility / expiry / message-window gates
// ════════════════════════════════════════════════════════════════════

// T2-1 — Experience-cloud offer gate.
// smali: classes6/.../BaseCloudHelper.smali:1122
//   .method public canBuyExperienceCloud()Z — .registers 7
// Single definition on the abstract base (subclasses do not override; verified).
object CanBuyExperienceCloudFingerprint : Fingerprint(
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = emptyList(),
    name = "canBuyExperienceCloud",
    custom = { _, classDef ->
        classDef.type == "Lcom/juanvision/modulelist/helper/wrapper/BaseCloudHelper;"
    },
    filters = listOf(
        methodCall(
            definingClass = "Lcom/juanvision/modulelist/helper/wrapper/BaseCloudHelper;",
            name = "findFirstBoughtChannel",
        ),
    ),
)

// T2-2 — Cloud expiry (drives renewal banners / "expired" UI).
// smali: classes6/.../CloudHelper.smali:4294 — .method public isExpired(I)Z (.registers 9)
// NOTE: LvCloudHelper.isExpired (:2357) and VNCloudHelper.isExpired (:1720) are
// ALREADY constant `return false` in 4.6.5.13 (verified) — only CloudHelper needs
// the edit.
object CloudHelperIsExpiredFingerprint : Fingerprint(
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf("I"),
    name = "isExpired",
    custom = { _, classDef ->
        classDef.type == "Lcom/juanvision/modulelist/helper/wrapper/CloudHelper;"
    },
    filters = listOf(
        methodCall(
            definingClass = "Lcom/juanvision/http/pojo/device/CameraInfo;",
            name = "getEndtime",
        ),
    ),
)

// T2-3 — First bought channel: returns first channel with a cloud service or
// -1; callers treat `>= 0` as "has cloud". Patching to 0 enables cloud event
// search / widens the alarm message window.
// smali: classes6/.../ CloudHelper.smali:2820 · LvCloudHelper.smali:1500 ·
//        VNCloudHelper.smali:934 — all `.method public findFirstBoughtChannel()I`
//        (.registers 5), each leading with thisDeviceNotSupportCloud().
object CloudHelperFindFirstBoughtChannelFingerprint : Fingerprint(
    returnType = "I",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = emptyList(),
    name = "findFirstBoughtChannel",
    custom = { _, classDef ->
        classDef.type == "Lcom/juanvision/modulelist/helper/wrapper/CloudHelper;"
    },
    filters = listOf(
        methodCall(
            definingClass = "Lcom/juanvision/modulelist/helper/wrapper/CloudHelper;",
            name = "thisDeviceNotSupportCloud",
        ),
    ),
)

object LvCloudHelperFindFirstBoughtChannelFingerprint : Fingerprint(
    returnType = "I",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = emptyList(),
    name = "findFirstBoughtChannel",
    custom = { _, classDef ->
        classDef.type == "Lcom/juanvision/modulelist/helper/wrapper/LvCloudHelper;"
    },
    filters = listOf(
        methodCall(
            definingClass = "Lcom/juanvision/modulelist/helper/wrapper/LvCloudHelper;",
            name = "thisDeviceNotSupportCloud",
        ),
    ),
)

object VNCloudHelperFindFirstBoughtChannelFingerprint : Fingerprint(
    returnType = "I",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = emptyList(),
    name = "findFirstBoughtChannel",
    custom = { _, classDef ->
        classDef.type == "Lcom/juanvision/modulelist/helper/wrapper/VNCloudHelper;"
    },
    filters = listOf(
        methodCall(
            definingClass = "Lcom/juanvision/modulelist/helper/wrapper/VNCloudHelper;",
            name = "thisDeviceNotSupportCloud",
        ),
    ),
)

// T2-4 — Message check-day window (3 → 7 days of alarm history). Two concrete
// implementations (both contract members, both same body shape: mWrapper →
// CloudAPI.findFirstBoughtChannel → >= 0):
//   AlertMessageDisplayConfigPresenter :5538 (.registers 2) — the mMaxCheckDay gate
//   X35BinocularMessageCentrePresenter :1341 (.registers 2) — binocular variant
object AlertConfigHasBoughtCloudServiceFingerprint : Fingerprint(
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = emptyList(),
    name = "hasBoughtCloudService",
    custom = { _, classDef ->
        classDef.type == "Lcom/zasko/modulemain/mvpdisplay/presenter/AlertMessageDisplayConfigPresenter;"
    },
    filters = listOf(
        methodCall(
            definingClass = "Lcom/juanvision/modulelist/absInterface/CloudAPI;",
            name = "findFirstBoughtChannel",
        ),
    ),
)

object BinocularHasBoughtCloudServiceFingerprint : Fingerprint(
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = emptyList(),
    name = "hasBoughtCloudService",
    custom = { _, classDef ->
        classDef.type == "Lcom/zasko/modulemain/feature/binocular/mvpdisplay/presenter/X35BinocularMessageCentrePresenter;"
    },
    filters = listOf(
        methodCall(
            definingClass = "Lcom/juanvision/modulelist/absInterface/CloudAPI;",
            name = "findFirstBoughtChannel",
        ),
    ),
)
