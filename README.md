# 👋🧩 Morphe Patches template

Template repository for Morphe Patches.

## ❓ About

This is a template to create a new Morphe Patches repository.
TODO: Update this about section with a brief introduction/summary about this repo and what it offers.

## 🩹 Patches list

<!-- PATCHES_START EXPANDED -->
> **[v1.19.0](https://github.com/byehi98/okish-morphe-patches/releases/tag/v1.19.0)**&nbsp;&nbsp;•&nbsp;&nbsp;`main`&nbsp;&nbsp;•&nbsp;&nbsp;48 patches total
<details open>
<summary>📦 Adda247&nbsp;&nbsp;•&nbsp;&nbsp;2 patches</summary>
<br>

**🎯 Supported versions:**

| 12.6.4 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Adda247 Ad Removal](#adda247-ad-removal) | Blocks all Google AdMob ads (banner, interstitial, rewarded, rewarded interstitial, native, app open and Ad Manager) by neutralizing the Flutter Google Mobile Ads plugin: no ad is ever requested and no ad can ever be shown. |  |
| [Adda247 MoEngage In-App Blocker](#adda247-moengage-in-app-blocker) | Blocks the app's own MoEngage in-app promotions (the recurring "recommended for you" nudge with no close button, and full-screen standard in-app messages) by no-oping MoEngage's HTML render chokepoint, so no in-app WebView is ever built or attached. Separate from the Google AdMob block. |  |

</details>

<details open>
<summary>📦 Big Hunter&nbsp;&nbsp;•&nbsp;&nbsp;4 patches</summary>
<br>

**🎯 Supported versions:**

| 3.1.1 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Big Hunter Fake Purchase](#big-hunter-fake-purchase) | Tapping any store item grants the product immediately (via the game's own confirmed-purchase path -> native applyProduct) without opening Google Play billing. Entitlements persist across restarts. |  |
| [Big Hunter PairIP License Bypass](#big-hunter-pairip-license-bypass) | Disables the PairIP license check (installer verification + Google Play licensing service validation + paywall/exit dialog). Required for patched APKs — the original check fails on any non-Play signature and force-closes the app. |  |
| [Big Hunter Remove Ads](#big-hunter-remove-ads) | Interstitial ads are never shown (show() short-circuits via the ad-not-ready path on all three network bridges: AdMob, AppLovin, Unity Ads). |  |
| [Big Hunter Rewards Without Ads](#big-hunter-rewards-without-ads) | Rewarded video requests grant the reward immediately via the game's own native rewardCallback() without loading or showing any ad (AdMob, AppLovin, Unity Ads). |  |

</details>

<details open>
<summary>📦 Doodle Jump&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

**🎯 Supported versions:**

| 3.11.38 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Doodle Jump Billing Bypass](#doodle-jump-billing-bypass) | Unlocks the full game by bypassing Google Play billing. |  |

</details>

<details open>
<summary>📦 Dr. Driving&nbsp;&nbsp;•&nbsp;&nbsp;3 patches</summary>
<br>

**🎯 Supported versions:**

| 1.73 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Dr. Driving Ad Removal](#dr-driving-ad-removal) | Removes all Google AdMob ads (banner, interstitial, rewarded). |  |
| [Dr. Driving Unlimited Coins](#dr-driving-unlimited-coins) | Coins inflated to 1,000,000,000 on save load; purchases, upgrades, rentals and repairs never cost coins. |  |
| [Dr. Driving Unlimited Gold](#dr-driving-unlimited-gold) | Gold is inflated to 1,000,000,000 on save load; gold packs in the store also grant gold. |  |

</details>

<details open>
<summary>📦 Earn to Die 2&nbsp;&nbsp;•&nbsp;&nbsp;3 patches</summary>
<br>

**🎯 Supported versions:**

| 1.4.58 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Earn to Die 2 Free IAP](#earn-to-die-2-free-iap) | Intercepts all in-app purchases and reports instant success to the game engine, unlocking paid content (cash doubler) for free. |  |
| [Earn to Die 2 No Ads + Instant Rewards](#earn-to-die-2-no-ads-instant-rewards) | Removes interstitial and rewarded ads, and grants rewarded-video rewards instantly by firing the native callbacks. |  |
| [Earn to Die 2 Remove Google Play Check](#earn-to-die-2-remove-google-play-check) | Bypasses the native Google Play distribution checks (installer package + signing signature) so the app runs when sideloaded. |  |

</details>

<details open>
<summary>📦 FreeJobAlert&nbsp;&nbsp;•&nbsp;&nbsp;2 patches</summary>
<br>

**🎯 Supported versions:**

| 1.0.0 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [FreeJobAlert Ad Removal](#freejobalert-ad-removal) | Removes all Google AdMob ads (banner, interstitial, rewarded, app open, native). |  |
| [FreeJobAlert License Bypass](#freejobalert-license-bypass) | Bypasses the Pairip Play Store installation check so the app launches normally. |  |

</details>

<details open>
<summary>📦 HAAK&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

**🎯 Supported versions:**

| 1.4.3 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [HAAK Free Purchases and Unlocked Paid Content](#haak-free-purchases-and-unlocked-paid-content) | Redirects purchase failure callbacks to their success twins: cancelling the Google Play dialog, a billing error, or an "not purchased" query result all grant the product instead. |  |

</details>

<details open>
<summary>📦 Hill Climb Racing&nbsp;&nbsp;•&nbsp;&nbsp;3 patches</summary>
<br>

**🎯 Supported versions:**

| 1.70.0 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Hill Climb Racing Ad Removal](#hill-climb-racing-ad-removal) | Completely removes ads: banners and interstitials can never be displayed (CFirebaseAds.showBanners/showInterstitial become no-ops) and ad-free is granted once per app start — loadStore() seeds mAdFree = 1, the native engine's poll grants it and the store's own reset (inappPurchasesProcessed) zeroes the field, so no repeated purchase popups. |  |
| [Hill Climb Racing Free Store](#hill-climb-racing-free-store) | Every store item is granted instantly and free: coins, gems, paints, ad-skips, ad-free and bundles, without launching Google Play billing. |  |
| [Hill Climb Racing Instant Rewarded Video Rewards](#hill-climb-racing-instant-rewarded-video-rewards) | Rewarded video ads grant their reward instantly without playing the ad: the native engine receives onVideoStartedSuccess + onVideoCompletedSuccess on the GL thread, exactly as if the video had been watched and completed. |  |

</details>

<details open>
<summary>📦 Injustice: Gods Among Us&nbsp;&nbsp;•&nbsp;&nbsp;2 patches</summary>
<br>

**🎯 Supported versions:**

| 3.5.1 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Injustice Ads Removal](#injustice-ads-removal) | Get stamina without watching ad Video. |  |
| [Injustice Billing Bypass](#injustice-billing-bypass) | Bypass billing get the items in store for free. |  |

</details>

<details open>
<summary>📦 Into the Dead&nbsp;&nbsp;•&nbsp;&nbsp;2 patches</summary>
<br>

**🎯 Supported versions:**

| 2.9.3 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Into the Dead Ad Removal](#into-the-dead-ad-removal) | Removes all ads and grants rewarded-video rewards instantly: rewarded videos (legacy IronSource and LevelPlay), interstitials, launch, banner and AdMob App Open ads are all eliminated. |  |
| [Into the Dead Billing Bypass](#into-the-dead-billing-bypass) | Unlocks all in-app purchases for free and bypasses SHA1withRSA receipt verification: every store item is granted instantly without launching the Google Play payment dialog, and any fabricated receipt is accepted. |  |

</details>

<details open>
<summary>📦 Jetpack Joyride&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

**🎯 Supported versions:**

| 1.104.1 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Jetpack Joyride Billing Bypass](#jetpack-joyride-billing-bypass) | Intercepts all in-app purchases and reports instant success to the game engine. |  |

</details>

<details open>
<summary>📦 Missiles&nbsp;&nbsp;•&nbsp;&nbsp;3 patches</summary>
<br>

**🎯 Supported versions:**

| 1.41 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Missiles Ads Removal](#missiles-ads-removal) | Spoofs the Google Mobile Ads Unity bridge (AdMob): interstitial, rewarded, rewarded interstitial, app open and banner ads resolve instantly as loaded/showed/closed/rewarded without ever displaying an ad. |  |
| [Missiles Billing Bypass](#missiles-billing-bypass) | Spoofs the Google Play Billing 8.0.0 purchase flow: every buy is instantly granted (fake Purchase delivered through the registered listener), purchases/consumes complete instantly, and the product catalog is served with fake ProductDetails — no Google Play sheet, no real payment. |  |
| [Missiles License Bypass](#missiles-license-bypass) | Bypasses the Pairip Play Store license check so the app launches normally. |  |

</details>

<details open>
<summary>📦 MovieBox&nbsp;&nbsp;•&nbsp;&nbsp;5 patches</summary>
<br>

**🎯 Supported versions:**

| 4.0.01.0813.02 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [MovieBox Ad Removal](#moviebox-ad-removal) | Removes splash, banner, native, interstitial and reward video ads. |  |
| [MovieBox Download Unlock](#moviebox-download-unlock) | Bypasses the server-side download access check so any resolution (including 1080p) can be downloaded on a free account. |  |
| [MovieBox Force Update Bypass](#moviebox-force-update-bypass) | Disables forced and nag update prompts in MovieBox. |  |
| [MovieBox Parallel Downloads](#moviebox-parallel-downloads) | Allows up to 5 simultaneous downloads in MovieBox. |  |
| [MovieBox Premium Unlock](#moviebox-premium-unlock) | Unlocks MovieBox Premium (VIP) by forcing the member state active at Pro level. |  |

</details>

<details open>
<summary>📦 Only One&nbsp;&nbsp;•&nbsp;&nbsp;2 patches</summary>
<br>

**🎯 Supported versions:**

| 1.2983 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Only One Free Store](#only-one-free-store) | Makes 'Restore Purchases' grant every store item for free (Ultimate Power, all classes, bundles and powers) — also removes ads. |  |
| [Only One License Bypass](#only-one-license-bypass) | Bypasses the PairIP Play Store license check so the app launches normally. |  |

</details>

<details open>
<summary>📦 Plague Inc.&nbsp;&nbsp;•&nbsp;&nbsp;7 patches</summary>
<br>

**🎯 Supported versions:**

| 1.25.2 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Plague Inc. Premium](#plague-inc-premium) | Unlocks all premium features and disables premium prompts. |  |
| [Plague Inc. Remove Banner Ads](#plague-inc-remove-banner-ads) | Removes AppLovin banner ads and the Ndemic premium promo during gameplay. |  |
| [Plague Inc. Skip Rewarded Video Ads](#plague-inc-skip-rewarded-video-ads) | Grants rewarded video bonuses instantly without watching ads. |  |
| [Plague Inc. Sync Premium Entitlements](#plague-inc-sync-premium-entitlements) | Registers unlocked premium content with the native engine so premium modes run without trial banners. |  |
| [Plague Inc. Unlock Disease Types](#plague-inc-unlock-disease-types) | Unlocks all disease types including Neurax Worm, Necroa Virus, Simian Flu and Shadow Plague. |  |
| [Plague Inc. Unlock Expansions](#plague-inc-unlock-expansions) | Unlocks Cure Mode, Plague Pack, custom scenarios and bypasses the Play Pass check. |  |
| [Plague Inc. Unlock Fast-Forward](#plague-inc-unlock-fast-forward) | Removes the premium lock from the in-game fast-forward button. |  |

</details>

<details open>
<summary>📦 Rodeo Stampede: Sky Zoo Safari&nbsp;&nbsp;•&nbsp;&nbsp;2 patches</summary>
<br>

**🎯 Supported versions:**

| 4.23.0 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Rodeo Stampede Ad-Free (Ads blocked + instant rewards)](#rodeo-stampede-ad-free-ads-blocked-instant-rewards) | Blocks banner/app-open/native ads and turns rewarded + interstitial ads into instant no-ad events so the game never fetches or displays ads. |  |
| [Rodeo Stampede Free Purchase](#rodeo-stampede-free-purchase) | Spoofs in-app purchases as instantly successful at the Yodo1 purchase funnel. |  |

</details>

<details open>
<summary>📦 Smash Hit&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

**🎯 Supported versions:**

| 1.5.14 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Smash Hit Premium Unlock](#smash-hit-premium-unlock) | Unlocks premium and all game modes without purchase. |  |

</details>

<details open>
<summary>📦 Swordigo&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

**🎯 Supported versions:**

| 1.4.12 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Swordigo Premium Unlock](#swordigo-premium-unlock) | Enables ad-free, guiding compass, and coin doubler without purchase. |  |

</details>

<details open>
<summary>📦 Tentacle Wars&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

**🎯 Supported versions:**

| 2.1.27 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Tentacle Wars Free Purchases](#tentacle-wars-free-purchases) | Spoofs in-app purchases as instantly successful: tapping Buy grants the SKU directly through the game's own purchase callback — no Google Play dialog, no payment. For ad-free, just tap the Remove-Ads button on the main game screen. |  |

</details>

<details open>
<summary>📦 TrueCloud&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

**🎯 Supported versions:**

| 4.6.5.13 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [TrueCloud Ad Removal](#truecloud-ad-removal) | Consolidated patch to remove all ads, boot pages, cloud popups, and the help center robot. |  |

</details>

<details open>
<summary>📦 Vector&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

**🎯 Supported versions:**

| 2.10.0 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Vector Ad Spoof](#vector-ad-spoof) | Spoofs rewarded ad callbacks to grant coins/rewards without showing ads. |  |

</details>

<!-- PATCHES_END -->

#### How to use these patches

Click here to add these patches to Morphe: https://morphe.software/add-source?github=byehi98/okish-morphe-patches

Or manually add this repository url as a patch source in Morphe: https://github.com/byehi98/okish-morphe-patches

### 🛠️ Building

To build UserXYZ Morphe Patches,
you can follow the [Morphe documentation](https://github.com/MorpheApp/morphe-documentation).

## 📜 License

UserXYZ Morphe Patches are licensed under the [GNU General Public License v3.0](LICENSE)
