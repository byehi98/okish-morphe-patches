package app.truecloud.patches.rating

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.returnEarly
import app.truecloud.patches.shared.Constants.COMPATIBILITY_TRUECLOUD

// T5+T6 — the two rating surfaces:
//   T5 shouldShowGoodReviewDialog → star-rating dialog (RatingBar) gate;
//     its only caller (handleGoodReview) treats false as "conditions not
//     met" and just fires onError, which resets a boolean flag.
//   T6 checkCanShowTipsData → floating "rate us" bottom tip over the device
//     list; single internal caller early-returns on false.
@Suppress("unused")
val trueCloudRatingPromptPatch = bytecodePatch(
    name = "TrueCloud UX",
    description = "Hides the in-app rating dialog and rate-us tip banner.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_TRUECLOUD)

    execute {
        ShouldShowGoodReviewDialogFingerprint.method.returnEarly(false)
        CheckCanShowTipsDataFingerprint.method.returnEarly(false)
    }
}
