package app.truecloud.patches.ad

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

// === ADService Fingerprints ===
object AdServiceObtainFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Lcom/juanvision/bussiness/ad/IAD;",
    parameters = listOf("Landroid/content/Context;", "Lcom/juanvision/bussiness/ad/ADTYPE;"),
    name = "obtain",
    custom = { _, classDef -> classDef.type == "Lcom/juanvision/bussiness/ad/ADService;" }
)

object AdServiceObtain3ArgFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Lcom/juanvision/bussiness/ad/IAD;",
    parameters = listOf("Landroid/content/Context;", "Lcom/juanvision/bussiness/ad/ADTYPE;", "Z"),
    name = "obtain",
    custom = { _, classDef -> classDef.type == "Lcom/juanvision/bussiness/ad/ADService;" }
)

// === Cloud Boot Page Fingerprint ===
object CloudBootPageHelperFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Lcom/juanvision/bussiness/cloudBoot/CloudBootPageHelper\$InterceptData;",
    parameters = listOf("Landroid/content/Context;"),
    name = "shouldIntercept",
    custom = { _, classDef -> classDef.type == "Lcom/juanvision/bussiness/cloudBoot/CloudBootPageHelper;" }
)

// === CloudAPI.isSupport Fingerprints ===
object CloudHelperIsSupport : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "Z",
    parameters = emptyList(),
    name = "isSupport",
    custom = { _, classDef -> classDef.type == "Lcom/juanvision/modulelist/helper/wrapper/CloudHelper;" }
)

object LvCloudHelperIsSupport : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "Z",
    parameters = emptyList(),
    name = "isSupport",
    custom = { _, classDef -> classDef.type == "Lcom/juanvision/modulelist/helper/wrapper/LvCloudHelper;" }
)

object VNCloudHelperIsSupport : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "Z",
    parameters = emptyList(),
    name = "isSupport",
    custom = { _, classDef -> classDef.type == "Lcom/juanvision/modulelist/helper/wrapper/VNCloudHelper;" }
)

// === Help Center Robot Fingerprints ===
object X35MainListFragmentOnResume : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "V",
    parameters = emptyList(),
    name = "onResume",
    custom = { _, classDef -> classDef.type == "Lcom/zasko/modulemain/mvpdisplay/fragment/X35MainListFragment;" }
)

object X35MainListFragmentStartHelpIconShowAnimation : Fingerprint(
    accessFlags = listOf(AccessFlags.PRIVATE),
    returnType = "V",
    parameters = emptyList(),
    name = "startHelpIconShowAnimation",
    custom = { _, classDef -> classDef.type == "Lcom/zasko/modulemain/mvpdisplay/fragment/X35MainListFragment;" }
)

// === T1 — House-ad network choke ===
// Single backend for all 17 self/house ad placements (splash, device-list
// banners, float icons, preview/playback banners, popups, add-device promo).
// smali: classes3/com/juanvision/http/business/EseeUserApi.smali:694
//   .method public static getAdvertisementUrl(String,String,IHttpObserver)String
//   .registers 7 — only definition of this name in EseeUserApi (verified).
object EseeUserApiGetAdvertisementUrlFingerprint : Fingerprint(
    returnType = "Ljava/lang/String;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf(
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Lcom/juan/base/http/inter/IHttpObserver;",
    ),
    name = "getAdvertisementUrl",
    custom = { _, classDef -> classDef.type == "Lcom/juanvision/http/business/EseeUserApi;" },
)

// === T4 — Blank native-ad rows in the alert message list ===
// Injects up to 2 "alarmNativeAd" placeholder rows every 6 items; with
// ADService.obtain returning null the rows render empty.
// smali: classes9/com/zasko/modulemain/adapter/X35AlertMessageAdapter.smali:139
//   .method private addAlarmNativeAd(Ljava/util/List;)V — .registers 8
// Body const-string "alarmNativeAd" verified at the list-type compare.
object AddAlarmNativeAdFingerprint : Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PRIVATE),
    parameters = listOf("Ljava/util/List;"),
    name = "addAlarmNativeAd",
    custom = { _, classDef -> classDef.type == "Lcom/zasko/modulemain/adapter/X35AlertMessageAdapter;" },
    filters = listOf(
        string("alarmNativeAd"),
    ),
)

// Companion row-position injector (belt-and-braces for T4).
// smali: classes9/com/zasko/modulemain/adapter/X35MessageItemAdapter.smali:414
//   .method private adapterAlarmAdPosition(Ljava/util/List;)V — .registers 7
object AdapterAlarmAdPositionFingerprint : Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PRIVATE),
    parameters = listOf("Ljava/util/List;"),
    name = "adapterAlarmAdPosition",
    custom = { _, classDef -> classDef.type == "Lcom/zasko/modulemain/adapter/X35MessageItemAdapter;" },
    filters = listOf(
        fieldAccess(smali = "Lcom/zasko/modulemain/adapter/X35MessageItemAdapter;->mNeedAppendNativeAd:Z"),
    ),
)

// === T8 — Ad strategy polling / frequency caps ===
// Fetches per-placement ratio/frequency-cap strategy (ratioControlAd) plus
// dealer info; driven every list refresh + 300 s throttle.
// smali: classes6/com/juanvision/modulelogin/ad/ADExposeController.smali:2821
//   .method public requestExposeParam()V — .registers 5
// NOTE: refreshLocation/ratioControlAd live inside startRequestingExposeParam,
// not directly in this body — filters verified against the actual smali.
object AdExposeRequestExposeParamFingerprint : Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = emptyList(),
    name = "requestExposeParam",
    custom = { _, classDef -> classDef.type == "Lcom/juanvision/modulelogin/ad/ADExposeController;" },
    filters = listOf(
        methodCall(
            definingClass = "Lcom/zasko/aroutercomponent/modulemain/IDeviceDealerInfo;",
            name = "searchADComponentInfo",
        ),
        methodCall(
            definingClass = "Lcom/juanvision/modulelogin/ad/ADExposeController;",
            name = "startRequestingExposeParam",
        ),
    ),
)

// === T9 — Device-list top/bottom banner + popup feeds (backup to T1) ===
// smali: classes9/com/zasko/modulemain/helper/DeviceListHelper.smali
//   getAD(Context,I)V :9495 (.registers 16) — OperationSlot HOMETOPBANNER +
//   Shopline "device_list_top" + EseeUserApi.getAdvertisementUrl merge
object DeviceListHelperGetADFingerprint : Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf("Landroid/content/Context;", "I"),
    name = "getAD",
    custom = { _, classDef -> classDef.type == "Lcom/zasko/modulemain/helper/DeviceListHelper;" },
    filters = listOf(
        methodCall(
            definingClass = "Lcom/juanvision/modulelist/helper/OperationSlotHelper;",
            name = "handleOperationSlots",
        ),
        methodCall(
            definingClass = "Lcom/juanvision/http/shopline/model/ShoplineDataManager;",
            name = "getDataByPositionAsync",
        ),
    ),
)

// getBottomAd(Context)V :9795 (.registers 4) — "eseecloud_deviceList2" feed
object DeviceListHelperGetBottomAdFingerprint : Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf("Landroid/content/Context;"),
    name = "getBottomAd",
    custom = { _, classDef -> classDef.type == "Lcom/zasko/modulemain/helper/DeviceListHelper;" },
    filters = listOf(
        string("eseecloud_deviceList2"),
    ),
)

// getJmAd()V :9820 (.registers 4) — "devPop" feed
object DeviceListHelperGetJmAdFingerprint : Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = emptyList(),
    name = "getJmAd",
    custom = { _, classDef -> classDef.type == "Lcom/zasko/modulemain/helper/DeviceListHelper;" },
    filters = listOf(
        string("devPop"),
    ),
)

// getAdvertisementUrl(List,Z)V :9707 (.registers 6) — "eseecloud_deviceListPopUp"
// feed → view-command type 32 → showAdvertCustomizePrompt (T12)
object DeviceListHelperGetAdvertisementUrlFingerprint : Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf("Ljava/util/List;", "Z"),
    name = "getAdvertisementUrl",
    custom = { _, classDef -> classDef.type == "Lcom/zasko/modulemain/helper/DeviceListHelper;" },
    filters = listOf(
        string("eseecloud_deviceListPopUp"),
    ),
)

// === T10 — Floating promo icon on the device list ===
// smali: classes9/.../X35DeviceListPresenter.smali:8255
//   .method public loadBottomFloatAd(Landroid/widget/FrameLayout;)V — .registers 8
object LoadBottomFloatAdFingerprint : Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf("Landroid/widget/FrameLayout;"),
    name = "loadBottomFloatAd",
    custom = { _, classDef ->
        classDef.type == "Lcom/zasko/modulemain/mvpdisplay/presenter/X35DeviceListPresenter;"
    },
    filters = listOf(
        methodCall(
            definingClass = "Lcom/juanvision/http/business/EseeUserApi;",
            name = "getAdvertisementUrl",
        ),
    ),
)

// === T11 — Non-cancellable home promo popup ===
// smali: classes9/.../X35DeviceListFragment.smali:16195
//   .method public showHomePopupDialog(ListOperationSlotInfo$SlotInfo;)V — .registers 6
object ShowHomePopupDialogFingerprint : Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf("Lcom/juanvision/http/pojo/device/ListOperationSlotInfo\$SlotInfo;"),
    name = "showHomePopupDialog",
    custom = { _, classDef ->
        classDef.type == "Lcom/zasko/modulemain/mvpdisplay/fragment/X35DeviceListFragment;"
    },
    filters = listOf(
        methodCall(
            definingClass = "Lcom/zasko/modulemain/dialog/HomePopupImageDialog;",
            name = "showImage",
        ),
    ),
)

// === T12 — Per-device promo popup (DeviceBelongDialog) ===
// smali: classes9/.../X35DeviceListFragment.smali:14795
//   .method public showAdvertCustomizePrompt(AdvUrlClass,String)V — .registers 5
object ShowAdvertCustomizePromptFingerprint : Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(
        "Lcom/juanvision/http/pojo/user/LoginUserInfo\$AdvUrlClass;",
        "Ljava/lang/String;",
    ),
    name = "showAdvertCustomizePrompt",
    custom = { _, classDef ->
        classDef.type == "Lcom/zasko/modulemain/mvpdisplay/fragment/X35DeviceListFragment;"
    },
    filters = listOf(
        methodCall(
            definingClass = "Lcom/zasko/modulemain/dialog/DeviceBelongDialog;",
            name = "show",
        ),
    ),
)
