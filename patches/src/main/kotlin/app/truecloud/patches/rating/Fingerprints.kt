package app.truecloud.patches.rating

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags

// === T5 — In-app star-rating dialog ===
// Gate for the DialogAppGoodReviewsBinding rating dialog; only caller is
// GoodReviewHelper.handleGoodReview (returning false just fires onError which
// resets mCheckingGoodReview — side-effect free).
// smali: classes9/com/zasko/modulemain/helper/GoodReviewHelper.smali:2280
//   .method public shouldShowGoodReviewDialog()Z — .registers 6
// Filter order verified: getListGoodReviewsDialogShowTime (first UserCache call)
// → getListCloseGoodReviewDialogTimes → isGoodReviewDialogHadStar.
object ShouldShowGoodReviewDialogFingerprint : Fingerprint(
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = emptyList(),
    name = "shouldShowGoodReviewDialog",
    custom = { _, classDef -> classDef.type == "Lcom/zasko/modulemain/helper/GoodReviewHelper;" },
    filters = listOf(
        methodCall(
            definingClass = "Lcom/zasko/commonutils/cache/UserCache;",
            name = "getListGoodReviewsDialogShowTime",
        ),
        methodCall(
            definingClass = "Lcom/zasko/commonutils/cache/UserCache;",
            name = "isGoodReviewDialogHadStar",
        ),
    ),
)

// === T6 — "Rate us" floating bottom tip ===
// Gate for the BottomTipRemindGoodReviewBinding banner over the device list;
// single internal caller showGoodReviewTipView → early-returns on false.
// smali: classes9/.../ListRemindGoodReviewsTipsFloatViewHelper.smali:46
//   .method private checkCanShowTipsData()Z — .registers 6
// (Lte4GTipsFloatViewHelper has a same-named method with a parameter — the
// empty parameter list + class anchor disambiguate.)
// Filter order verified: getListGoodReviewsDialogEditInterruptTime →
// getListGoodReviewsDialogEditInterruptTimeData.
object CheckCanShowTipsDataFingerprint : Fingerprint(
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PRIVATE),
    parameters = emptyList(),
    name = "checkCanShowTipsData",
    custom = { _, classDef ->
        classDef.type == "Lcom/zasko/modulemain/helper/ListRemindGoodReviewsTipsFloatViewHelper;"
    },
    filters = listOf(
        methodCall(
            definingClass = "Lcom/zasko/commonutils/cache/UserCache;",
            name = "getListGoodReviewsDialogEditInterruptTime",
        ),
        methodCall(
            definingClass = "Lcom/zasko/commonutils/cache/UserCache;",
            name = "getListGoodReviewsDialogEditInterruptTimeData",
        ),
    ),
)
