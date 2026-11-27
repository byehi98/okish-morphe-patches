package app.altosodyssey.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.SupportedAbi

object Constants {
    /**
     * SKUs granted by the "IAP Unlock" patch. Consumable coin packs (coinpack1/2/3)
     * are intentionally excluded — they are not granted at game start. The engine
     * treats a successful queryPurchasesAsync response containing these as "owned",
     * granting every other entitlement (remove-ads, play pass, all characters, coin
     * doubler). Json is never receipt-validated (no Unity IAP CrossPlatformValidator,
     * see premium-bypass.md).
     */
    val SKUS = listOf(
        "com.noodlecake.altosodyssey.removeads",
        "com.noodlecake.altosodyssey.playpass",
        "com.noodlecake.altosodyssey.coindoubler",
        "com.noodlecake.altosodyssey.allcharacters",
        "com.noodlecake.altosodyssey.allcharacters2",
        "com.noodlecake.altosodyssey.allcharacters3",
        "com.noodlecake.altosodyssey.allcharacters4",
        "com.noodlecake.altosodyssey.felipe",
        "com.noodlecake.altosodyssey.izel",
        "com.noodlecake.altosodyssey.maya",
        "com.noodlecake.altosodyssey.paz",
        "com.noodlecake.altosodyssey.sumara"
    )

    /**
     * Alto's Odyssey 1.0.45 (versionCode 110).
     *
     * Published as an XAPK containing:
     *  - `com.noodlecake.altosodyssey.apk`  (base: all DEX, assets/global-metadata.dat)
     *  - `config.arm64_v8a.apk`             (native libs: libil2cpp.so, libunity.so)
     *  - `config.armeabi_v7a.apk`           (native libs: libil2cpp.so, libunity.so)
     *
     * Both ABI splits are declared in `versionCodes` so the bundle is accepted and
     * patched with either/both architectures present. The patch itself is DEX-only
     * (Google Play Billing 8.3.0 lives in `classes2.dex` of the base APK), and the
     * DEX payload is identical across ABI splits, so a single patch set covers
     * arm64-v8a and armeabi-v7a. morphe-cli merges every split, so the patched APK
     * keeps `lib/arm64-v8a/` and `lib/armeabi-v7a/` and installs on either ABI.
     */
    val COMPATIBILITY_ALTOSODYSSEY = Compatibility(
        name = "Alto's Odyssey",
        packageName = "com.noodlecake.altosodyssey",
        apkFileType = ApkFileType.XAPK,
        appIconColor = 0xE8A33D,
        targets = listOf(
            AppTarget(
                version = "1.0.45",
                versionCodes = mapOf(
                    SupportedAbi.ARM64_V8A to 110,
                    SupportedAbi.ARMEABI_V7A to 110,
                ),
            )
        )
    )
}
