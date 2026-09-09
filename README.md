<div align="center">

# 🧩 okish-morphe-patches

**A personal collection of Morphe patches for favorite Android apps and games.**

[![Release](https://img.shields.io/github/v/release/byehi98/okish-morphe-patches?style=for-the-badge&logo=github&color=5865F2)](https://github.com/byehi98/okish-morphe-patches/releases)
[![Build Status](https://img.shields.io/github/actions/workflow/status/byehi98/okish-morphe-patches/release.yml?branch=main&style=for-the-badge&logo=githubactions&logoColor=white&label=Release)](https://github.com/byehi98/okish-morphe-patches/actions/workflows/release.yml)
[![License: GPL v3](https://img.shields.io/github/license/byehi98/okish-morphe-patches?style=for-the-badge&color=forestgreen)](LICENSE)
[![Add to Morphe](https://img.shields.io/badge/Morphe-Add%20Source-8A2BE2?style=for-the-badge&logo=android&logoColor=white)](https://morphe.software/add-source?github=byehi98/okish-morphe-patches)

[About](#-about) • [How to Use](#-how-to-use) • [Patches List](#-patches-list) • [App Requests](#-app-requests) • [Contributing](#-contributing) • [Building](#%EF%B8%8F-building-from-source) • [License](#-license)

</div>

---

## ❓ About

**okish-morphe-patches** is a personal collection of custom patches created for the [Morphe](https://morphe.software) patching ecosystem, primarily developed for personal use and educational research on apps I actively use.

---

## 🚀 How to Use

If you are on an Android device with **Morphe Manager** installed, tap the button below to add this repository as a patch source:

[![Add Source to Morphe](https://img.shields.io/badge/Import%20to%20Morphe-Click%20Here-8A2BE2?style=for-the-badge&logo=android&logoColor=white)](https://morphe.software/add-source?github=byehi98/okish-morphe-patches)

Direct URL:  
👉 **https://morphe.software/add-source?github=byehi98/okish-morphe-patches**

---

## 🩹 Patches list

<!-- PATCHES_START -->
> **[v1.26.0](https://github.com/byehi98/okish-morphe-patches/releases/tag/v1.26.0)**&nbsp;&nbsp;•&nbsp;&nbsp;`main`&nbsp;&nbsp;•&nbsp;&nbsp;55 patches total
<details>
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

<details>
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

<details>
<summary>📦 Doodle Jump&nbsp;&nbsp;•&nbsp;&nbsp;2 patches</summary>
<br>

**🎯 Supported versions:**

| 3.11.38 | 3.11.40 |
| :---: | :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Doodle Jump Billing Bypass](#doodle-jump-billing-bypass) | Unlocks the full game by bypassing Google Play billing. |  |
| [Doodle Jump Resurrection Bypass](#doodle-jump-resurrection-bypass) | Resurrect instantly after death without watching a rewarded ad. |  |

</details>

<details>
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

<details>
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

<details>
<summary>📦 Fancy Pants Adventures&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

**🎯 Supported versions:**

| 1.0.30 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Fancy Pants Unlock Premium](#fancy-pants-unlock-premium) | Unlocks premium, wardrobe, world 1 and world 2 by forcing OTTBilling.isPurchased to always return true. |  |

</details>

<details>
<summary>📦 Burrito Bison: Launcha Libre&nbsp;&nbsp;•&nbsp;&nbsp;2 patches</summary>
<br>

**🎯 Supported versions:**

| 3.75 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Fix app opening](#fix-app-opening) | Required so the modified app opens without crashing — skips signature, root and emulator checks. |  |
| [Free purchases](#free-purchases) | Get paid items for free — tapping buy grants the item instantly without Google Play payment popup. |  |

</details>

<details>
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

<details>
<summary>📦 HAAK&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

**🎯 Supported versions:**

| 1.4.3 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [HAAK Free Purchases and Unlocked Paid Content](#haak-free-purchases-and-unlocked-paid-content) | Redirects purchase failure callbacks to their success twins: cancelling the Google Play dialog, a billing error, or an "not purchased" query result all grant the product instead. |  |

</details>

<details>
<summary>📦 Hill Climb Racing&nbsp;&nbsp;•&nbsp;&nbsp;3 patches</summary>
<br>

**🎯 Supported versions:**

| 1.70.0 | 1.71.1 |
| :---: | :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Hill Climb Racing Ad Removal](#hill-climb-racing-ad-removal) | Completely removes ads: banners and interstitials can never be displayed (CFirebaseAds.showBanners/showInterstitial become no-ops) and ad-free is granted once per app start — loadStore() seeds mAdFree = 1, the native engine's poll grants it and the store's own reset (inappPurchasesProcessed) zeroes the field, so no repeated purchase popups. |  |
| [Hill Climb Racing Free Store](#hill-climb-racing-free-store) | Every store item is granted instantly and free: coins, gems, paints, ad-skips, ad-free and bundles, without launching Google Play billing. |  |
| [Hill Climb Racing Instant Rewarded Video Rewards](#hill-climb-racing-instant-rewarded-video-rewards) | Rewarded video ads grant their reward instantly without playing the ad: the native engine receives onVideoStartedSuccess + onVideoCompletedSuccess on the GL thread, exactly as if the video had been watched and completed. |  |

</details>

<details>
<summary>📦 Alto's Adventure&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

**🎯 Supported versions:**

| 1.8.27 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [IAP Unlock](#iap-unlock) | Unlocks all in-app purchases by faking Google Play billing query results. |  |

</details>

<details>
<summary>📦 Alto's Odyssey&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

**🎯 Supported versions:**

| 1.0.42 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [IAP Unlock](#iap-unlock) | Unlocks all in-app purchases in Alto's Odyssey by faking Google Play billing query results. |  |

</details>

<details>
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

<details>
<summary>📦 Into the Dead&nbsp;&nbsp;•&nbsp;&nbsp;2 patches</summary>
<br>

**🎯 Supported versions:**

| 2.9.3 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Into the Dead 2 Ad Removal & Instant Boost Rewards](#into-the-dead-2-ad-removal-instant-boost-rewards) | Removes all ads (interstitials, banners, app-open) and grants rewarded-video perk boosts instantly on tap (no ad watch) using the correct reward name PERKS_BOOST, verified against a real rewarded event. |  |
| [Into the Dead Billing Bypass](#into-the-dead-billing-bypass) | Unlocks all in-app purchases for free and bypasses SHA1withRSA receipt verification: every store item is granted instantly without launching the Google Play payment dialog, and any fabricated receipt is accepted. |  |

</details>

<details>
<summary>📦 Jetpack Joyride&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

**🎯 Supported versions:**

| 1.104.1 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Jetpack Joyride Billing Bypass](#jetpack-joyride-billing-bypass) | Intercepts all in-app purchases and reports instant success to the game engine. |  |

</details>

<details>
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

<details>
<summary>📦 Only One&nbsp;&nbsp;•&nbsp;&nbsp;2 patches</summary>
<br>

**🎯 Supported versions:**

| 1.3031 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Only One Free Store](#only-one-free-store) | Makes 'Restore Purchases' grant every store item for free (Ultimate Power, all classes, bundles and powers) — also removes ads. |  |
| [Only One IAP billing bypass](#only-one-iap-billing-bypass) | Buy any item in the shop for free. When you tap Buy, the item is added to your game right away — no Google Play payment window ever opens. |  |

</details>

<details>
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

<details>
<summary>📦 Prince of Persia: The Lost Crown&nbsp;&nbsp;•&nbsp;&nbsp;3 patches</summary>
<br>

**🎯 Supported versions:**

| 1.1.9 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Prince of Persia: Anti-tamper bypass](#prince-of-persia-anti-tamper-bypass) | The game has a built-in self-check that shuts it down if it detects the app was modified. This patch quietly turns that check off so the game can start. |  |
| [Prince of Persia: Full Game Unlock Engine](#prince-of-persia-full-game-unlock-engine) | Adds a tiny hidden helper into the game. About 30 seconds after the game starts, it presses the game's own secret 'unlock full game' button — no purchases needed. |  |
| [Prince of Persia: Full Game Unlock Trigger](#prince-of-persia-full-game-unlock-trigger) | Tells the game to start that hidden helper the moment the app opens, so the unlock happens automatically. |  |

</details>

<details>
<summary>📦 Rodeo Stampede: Sky Zoo Safari&nbsp;&nbsp;•&nbsp;&nbsp;2 patches</summary>
<br>

**🎯 Supported versions:**

| 4.23.0 | 4.24.0 |
| :---: | :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Rodeo Stampede Ad-Free (Ads blocked + instant rewards)](#rodeo-stampede-ad-free-ads-blocked-instant-rewards) | Blocks banner/app-open/native ads and turns rewarded + interstitial ads into instant no-ad events so the game never fetches or displays ads. |  |
| [Rodeo Stampede Free Purchase](#rodeo-stampede-free-purchase) | Spoofs in-app purchases as instantly successful at the Yodo1 purchase funnel. |  |

</details>

<details>
<summary>📦 Smash Hit&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

**🎯 Supported versions:**

| 1.5.14 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Smash Hit Premium Unlock](#smash-hit-premium-unlock) | Unlocks premium and all game modes without purchase. |  |

</details>

<details>
<summary>📦 Swordigo&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

**🎯 Supported versions:**

| 1.4.12 | 1.4.13 |
| :---: | :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Swordigo Premium Unlock](#swordigo-premium-unlock) | Enables ad-free, guiding compass, and coin doubler without purchase. |  |

</details>

<details>
<summary>📦 Tentacle Wars&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

**🎯 Supported versions:**

| 2.1.27 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Tentacle Wars Free Purchases](#tentacle-wars-free-purchases) | Spoofs in-app purchases as instantly successful: tapping Buy grants the SKU directly through the game's own purchase callback — no Google Play dialog, no payment. For ad-free, just tap the Remove-Ads button on the main game screen. |  |

</details>

<details>
<summary>📦 Traffic Rider&nbsp;&nbsp;•&nbsp;&nbsp;2 patches</summary>
<br>

**🎯 Supported versions:**

| 2.11 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Traffic Rider Ads Removal + Instant Rewards](#traffic-rider-ads-removal-instant-rewards) | Removes ads and grants rewarded rewards instantly: interstitial/banner/app-open never show, rewarded videos fire onUserEarnedReward(1.0) instantly without ad. |  |
| [Traffic Rider IAP Bypass](#traffic-rider-iap-bypass) | Everything in the store is free with one tap — in-game currency, double cash, remove ads, extra continues, all bikes and packs. Just tap Buy and it grants instantly, no Google Play payment needed. |  |

</details>

<details>
<summary>📦 TrueCloud&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

**🎯 Supported versions:**

| 4.6.5.13 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [TrueCloud Ad Removal](#truecloud-ad-removal) | Consolidated patch to remove all ads, boot pages, cloud popups, and the help center robot. |  |

</details>

<details>
<summary>📦 Vector&nbsp;&nbsp;•&nbsp;&nbsp;2 patches</summary>
<br>

**🎯 Supported versions:**

| 2.10.0 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Vector Ad Spoof](#vector-ad-spoof) | Spoofs rewarded ad callbacks to grant coins/rewards without showing ads. |  |
| [Vector Money Mod](#vector-money-mod) | Spending money increases your balance instead of decreasing it (native libil2cpp.so patch). |  |

</details>

<!-- PATCHES_END -->

---

## 💡 App Requests

> [!NOTE]
> **okish-morphe-patches** is a personal project I created for the apps and games I personally use.
>
> - **App requests are open**: If you'd like to suggest an app or a patch, feel free to open a feature request via [GitHub Issues](https://github.com/byehi98/okish-morphe-patches/issues/new/choose).
> - **Discretionary pickup**: Whether a requested app will actually be picked up, investigated, or patched **totally depends on me** (based on whether I use the app, my personal interest, and available time).
> - Please understand there are no promises, guarantees, or ETAs for requested apps.

---

## 🤝 Contributing

Contributions and improvements are welcome! If you'd like to contribute, please follow these important guidelines:

### ⚠️ Rules for Pull Requests

1. **Work in the `dev` Branch (NOT `main`)**:
   - All contributions and pull requests **MUST be made against the `dev` branch, not `main`**.
   - PRs submitted against `main` will be closed or asked to be re-targeted to `dev`.
   - Merging from `dev` to `main` and version tagging is automated via CI release workflows.

2. **Strict Conventional Commits**:
   - Commit messages **MUST strictly follow [Conventional Commits](https://www.conventionalcommits.org/) format**.
   - The release pipeline uses Semantic Release to automatically calculate version bumps and generate changelogs based on commit prefixes:
     - `feat:` — New patch or new capability (triggers a **minor** release)
     - `fix:` — Bug fix in an existing patch (triggers a **patch** release)
     - `bump:` — Updating supported app target versions (triggers a **patch** release)
     - `docs:` — Documentation changes only (no release)

### Contribution Workflow

1. **Fork** the repository and clone your fork.
2. Check out a new branch based on **`dev`**:
   ```bash
   git checkout -b feat/my-new-patch origin/dev
   ```
3. Implement your patch in `patches/src/main/kotlin/`.
4. Ensure your patch builds cleanly:
   ```bash
   ./gradlew :patches:buildAndroid
   ```
5. Commit your changes using conventional commit format:
   ```bash
   git commit -m "feat(app-name): add ad-blocking patch"
   ```
6. Push to your fork and submit a Pull Request targeting the **`dev`** branch.

---

## 🛠️ Building from Source

### Prerequisites

- **Java Development Kit (JDK)**: Version 17 or 21 (Temurin / OpenJDK recommended)
- **Git**
- *(Optional)* **Nix** with Flakes (`flake.nix` and `.envrc` are provided for reproducible dev environments)

### Build Commands

1. **Clone the repository:**
   ```bash
   git clone https://github.com/byehi98/okish-morphe-patches.git
   cd okish-morphe-patches
   ```

2. **Build the patch package (`.mpp`):**
   ```bash
   ./gradlew :patches:buildAndroid
   ```
   The compiled bundle will be output to `patches/build/libs/patches-<version>.mpp`.

3. **Generate the patches list JSON (`patches-list.json`):**
   ```bash
   ./gradlew generatePatchesList
   ```

For detailed Morphe architecture documentation, refer to the [Morphe documentation](https://github.com/MorpheApp/morphe-documentation).

---

## ⚠️ Disclaimer

This project is provided for **educational purposes only**.

---

## 📜 License

**okish-morphe-patches** is licensed under the [GNU General Public License v3.0](LICENSE).
