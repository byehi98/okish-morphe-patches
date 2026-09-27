package app.truecloud.patches.ad

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.returnEarly
import app.truecloud.patches.shared.Constants.COMPATIBILITY_TRUECLOUD

@Suppress("unused")
val trueCloudAdsPatch = bytecodePatch(
    name = "TrueCloud Ads",
    description = "Removes all ads: ad service, house-ad network, ad rows, polling, and banner/popup surfaces; disables cloud boot pages, cloud prompts, and the help-center robot.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_TRUECLOUD)

    execute {
        val returnNullInsns = """
            const/4 v0, 0x0
            return-object v0
        """.trimIndent()

        val returnFalseInsns = """
            const/4 v0, 0x0
            return v0
        """.trimIndent()

        val returnVoidInsns = """
            return-void
        """.trimIndent()

        val hideRobotInstructions = """
            iget-object v0, p0, Lcom/zasko/modulemain/mvpdisplay/fragment/X35MainListFragment;->mBinding:Landroidx/viewbinding/ViewBinding;
            check-cast v0, Lcom/zasko/modulemain/databinding/FragmentX35MainListBinding;
            iget-object v0, v0, Lcom/zasko/modulemain/databinding/FragmentX35MainListBinding;->helpIv:Landroid/widget/ImageView;
            const/16 v1, 0x8
            invoke-virtual {v0, v1}, Landroid/widget/ImageView;->setVisibility(I)V
        """.trimIndent()

        // === Core ad removal ===

        // 1. Disable ADService
        AdServiceObtainFingerprint.method.addInstructions(0, returnNullInsns)
        AdServiceObtain3ArgFingerprint.method.addInstructions(0, returnNullInsns)

        // 2. Disable Cloud Boot Page
        CloudBootPageHelperFingerprint.method.addInstructions(0, returnNullInsns)

        // 3. Completely Disable Cloud APIs to remove all cloud prompts
        CloudHelperIsSupport.method.addInstructions(0, returnFalseInsns)
        LvCloudHelperIsSupport.method.addInstructions(0, returnFalseInsns)
        VNCloudHelperIsSupport.method.addInstructions(0, returnFalseInsns)

        // 4. Hide Help Center Robot
        X35MainListFragmentOnResume.method.addInstructions(0, hideRobotInstructions)
        X35MainListFragmentStartHelpIconShowAnimation.method.addInstructions(0, returnVoidInsns)

        // T1 — single choke for the whole self/house ad network (17 call sites:
        // splash, device-list banners, float icons, preview/playback banners,
        // popups, add-device promo). The returned value is a request task id that
        // call sites later pass to RequestManager.cancelRequest, so the safe
        // neutral value is an empty string rather than null.
        EseeUserApiGetAdvertisementUrlFingerprint.method.returnEarly("")

        // T4 — the alert message adapter unconditionally injects up to 2 placeholder
        // "alarmNativeAd" rows every 6 items; with ADService.obtain returning null
        // (step 1 above) the rows render blank. Stop the injection itself.
        // adapterAlarmAdPosition is the companion row-position injector in the item
        // adapter — patched together so no ad row can be positioned even if
        // mNeedAppendNativeAd is set by another path.
        AddAlarmNativeAdFingerprint.method.returnEarly()
        AdapterAlarmAdPositionFingerprint.method.returnEarly()

        // T8 — fetches the ad placement/ratio strategy (frequency caps, timers,
        // ecpm, blacklists, 300 s refresh). Still invoked after the ADService patch
        // via ADManager.tryRefreshingAdexStrategy / refreshAdexStrategyOverInterval.
        // Skipping the request is deadlock-free: startRequestingExposeParam's
        // isRequestingAdExpose flag simply stays false, and GlobalCache.getADSetting()
        // keeps its cached (stale) values.
        AdExposeRequestExposeParamFingerprint.method.returnEarly()

        // T9–T12 — belt-and-braces ad surfaces that survive T1 (house-ad choke):
        //   T9  DeviceListHelper.getAD / getBottomAd / getJmAd / getAdvertisementUrl
        //       — list top/bottom banner + popup feeds (getAD also merges
        //       OperationSlot HOMETOPBANNER and Shopline promo data)
        //   T10 X35DeviceListPresenter.loadBottomFloatAd — floating promo icon
        //   T11 X35DeviceListFragment.showHomePopupDialog — non-cancellable home popup
        //   T12 X35DeviceListFragment.showAdvertCustomizePrompt — per-device popup
        // All bodies are fire-and-forget fetch/show entry points; return-void is safe.
        // Do NOT patch EseeDeviceApi.getOperationSlotList — it also serves non-ad
        // config (activation_config, playbackSwitch, CloudLaunch, …).
        DeviceListHelperGetADFingerprint.method.returnEarly()
        DeviceListHelperGetBottomAdFingerprint.method.returnEarly()
        DeviceListHelperGetJmAdFingerprint.method.returnEarly()
        DeviceListHelperGetAdvertisementUrlFingerprint.method.returnEarly()
        LoadBottomFloatAdFingerprint.method.returnEarly()
        ShowHomePopupDialogFingerprint.method.returnEarly()
        ShowAdvertCustomizePromptFingerprint.method.returnEarly()
    }
}
