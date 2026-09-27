package app.truecloud.patches.vip

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.returnEarly
import app.truecloud.patches.shared.Constants.COMPATIBILITY_TRUECLOUD
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

@Suppress("unused")
val trueCloudVipPatch = bytecodePatch(
    name = "TrueCloud VIP",
    description = "Unlocks cloud/WeChat-call entitlements, extends alarm replay duration and message window, and disables 4G trial countdown gates. - Doesn't unlock Cloud Server Side Storage.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_TRUECLOUD)

    execute {
        // ═══ Tier-1 VIP / billing entitlement gates (client-side feature locks
        // only — cloud upload entitlement itself is server-driven; see notes §0). ═══

        // T1-1 — central `cloud().hasBought(channel)` answer: all three
        // CloudAPI implementations. Unlocks replay duration, HD/definition
        // memory, check-day window, status views and store entrances that
        // consult it.
        CloudHelperHasBoughtFingerprint.method.returnEarly(true)
        LvCloudHelperHasBoughtFingerprint.method.returnEarly(true)
        VNCloudHelperHasBoughtFingerprint.method.returnEarly(true)

        // T1-4 — WeChat-call package entitlement (store entrances, status
        // views, expiry prompts, playback/TF-card store entries).
        WechatCallIsServiceFingerprint.method.returnEarly(true)

        // T1-5 — Alarm replay duration: force the hasBought branch to fall
        // through to the 30 s assignment. Verified smali layout
        // (classes9/.../AlertMessageDisplayConfigPresenter.smali:3097+):
        //   N   : invoke-interface {v0, v1}, CloudAPI;->hasBought(I)Z
        //   N+1 : move-result v0
        //   N+2 : if-eqz v0, :cond_3c        ← jumps PAST the 30 s iput
        //   iput v2, ...->mTFCardRecordMaxDuration:I  (v2 = 0x1e)
        //   iput v2, ...->mCloudRecordMaxDuration:I
        // Forcing v0 = 1 right before the branch makes the 30 s replay
        // duration unconditional (falls through to both iputs).
        ConfigMaxDurationFingerprint.method.let { method ->
            val hasBoughtIndex = method.indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_INTERFACE &&
                        getReference<MethodReference>()?.let { ref ->
                            ref.definingClass == "Lcom/juanvision/modulelist/absInterface/CloudAPI;" &&
                                    ref.name == "hasBought"
                        } ?: false
            }
            method.addInstructions(hasBoughtIndex + 2, "const/4 v0, 0x1")
        }

        // ═══ Tier-1 4G free-preview trial gates (LTE devices only): ═══
        //   count()  — per-second counter that ALSO returns the timeout result.
        //     Callers pattern is `if (!popup.count()) return; handleTrialTimeout();`,
        //     so always-false stops playback + the trial-expired UI (verified:
        //     X35DisplayFloatFragment.smali:8266, BaseAlertMessageDisplayActivity).
        //   hasTimeOut() — pure `currentCount >= maxCount` compare; covers the other
        //     consumers (BaseAlertMessageDisplayActivity, AlertMessageDisplayActivity,
        //     PushAlertMessageDisplayActivity).
        //   maybeOnTrialTimeCountTimeOut() — playback refusal gate; the two presenters
        //     with real bodies are patched. CloudEventControlPresenter already
        //     hard-returns false in 4.6.5.13 (verified), and the two *Contact$Presenter
        //     entries are abstract interface declarations — no edits possible/needed.
        TrailTimeCountFingerprint.method.returnEarly(false)
        TrailTimeHasTimeOutFingerprint.method.returnEarly(false)
        LiveMaybeOnTrialTimeOutFingerprint.method.returnEarly(false)
        CommonEventMaybeOnTrialTimeOutFingerprint.method.returnEarly(false)

        // ═══ Tier-2 — narrower / partial-value cloud gates ═══
        //   T2-1 canBuyExperienceCloud       → experience-cloud offer allowed
        //   T2-2 CloudHelper.isExpired       → suppress renewal/expired banners
        //        (Lv/VN isExpired already hard-return false in 4.6.5.13 — verified,
        //        so only CloudHelper needs the edit)
        //   T2-3 findFirstBoughtChannel ×3   → return 0 (valid channel) instead of -1;
        //        enables cloud event search + `hasBoughtCloudService` callers
        //   T2-4 hasBoughtCloudService ×2    → widens the alarm message window
        //        from 3 to 7 days (both contract implementations, verified same body)
        CanBuyExperienceCloudFingerprint.method.returnEarly(true)
        CloudHelperIsExpiredFingerprint.method.returnEarly(false)
        CloudHelperFindFirstBoughtChannelFingerprint.method.returnEarly(0)
        LvCloudHelperFindFirstBoughtChannelFingerprint.method.returnEarly(0)
        VNCloudHelperFindFirstBoughtChannelFingerprint.method.returnEarly(0)
        AlertConfigHasBoughtCloudServiceFingerprint.method.returnEarly(true)
        BinocularHasBoughtCloudServiceFingerprint.method.returnEarly(true)
    }
}
