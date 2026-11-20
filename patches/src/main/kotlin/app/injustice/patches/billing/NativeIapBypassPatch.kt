package app.injustice.patches.billing

import app.injustice.patches.shared.Constants.COMPATIBILITY_INJUSTICE
import app.injustice.patches.shared.NativeSite
import app.injustice.patches.shared.applyNativeSites
import app.morphe.patcher.patch.rawResourcePatch

private const val LIB_ARM64 = "lib/arm64-v8a/libInjusticeGAU.so"

/**
 * Injustice 3.5.1 — **native store unlock + IAP gate bypass** (`libInjusticeGAU.so`).
 *
 * The store is Unreal + Scaleform, so the whole purchase path is native. Every tile tap
 * ends in `UMicroTransactionAndroid::BeginPurchase(int)`, and only that function calls the
 * Java bridge (`CallJava_MicroTransactionBeginPurchase` → `JavaCallback_MicroTransactionBeginPurchase`
 * → `MicroTransaction.BeginPurchase` → `MicroTransactionAndroid.BeginPurchase`). Our Java
 * hook (T7 of "Injustice Billing Bypass") grants the item instantly there, so the free-store
 * flow depends on native actually reaching that call.
 *
 * On a re-signed build two independent gates stop a purchase:
 *
 * 1. The script-level network predicate. `InjusticeIOSGame.HandlePurchase` (export 8027)
 *    calls `HasNetworkConnection()` before anything else and runs
 *    `DisplayCouldNotConnect` ("Cannot Connect To Google Play") when it is false. That is
 *    fixed on the Java side by T11 of "Injustice Billing Bypass".
 * 2. This library's "purchases blocked" byte `+0xc4`, which `Init` sets to 1 and only
 *    `OnMicroTransactionInit` clears. Upstream that callback requires a successful Play
 *    Billing handshake, which a re-signed APK can never complete.
 *
 * Site 1 keeps `+0xc4` clear from process start, site 2 makes the script-visible
 * `IsAllowedToMakePurchases` predicate constant true, and site 3 does the same for
 * `IsLoggedIntoGooglePlay`. With the Java hooks (T3/T7/T10) the storefront then reaches
 * `UMicroTransactionAndroid::BeginPurchase(int)`, which calls
 * `CallJava_MicroTransactionBeginPurchase` → `MicroTransaction.BeginPurchase` →
 * `MicroTransactionAndroid.BeginPurchase`, where T7 hands out the item.
 *
 * ## Sites (arm64-v8a; file offset == virtual address — first LOAD is offset 0, vaddr 0)
 *
 * ### 1 — `UMicroTransactionAndroid::Init` @ 0x27cf948 — never enter the blocked state
 *
 * ```
 * 0x27cf948  52800021  mov   w1, #1
 * 0x27cf94c  3903141f  strb  wzr, [x0, #0xc5]
 * 0x27cf950  3903181f  strb  wzr, [x0, #0xc6]
 * 0x27cf954  39031001  strb  w1,  [x0, #0xc4]   ; <- purchases blocked = 1
 * 0x27cf958  39031c01  strb  w1,  [x0, #0xc7]
 * 0x27cf95c  17c6d016  b     0x19839b4 <CallJava_MicroTransactionInit>
 * ```
 *
 * `strb w1, [x0, #0xc4]` → `nop`. `+0xc4` therefore stays 0 for the whole process
 * lifetime, including the very first script query (before any `update()` tick could have
 * delivered `NativeCallback_MicroTransactionOnInit`).
 *
 * ### 2 — `UMicroTransactionAndroid::IsAllowedToMakePurchases` @ 0x27ce748 — always true
 *
 * ```
 * 0x27ce73c  39431000  ldrb  w0, [x0, #0xc4]    ; RestorePreviousPurchases: !blocked
 * 0x27ce740  52000000  eor   w0, w0, #1
 * 0x27ce744  d65f03c0  ret
 * 0x27ce748  39431000  ldrb  w0, [x0, #0xc4]    ; IsAllowedToMakePurchases
 * 0x27ce74c  52000000  eor   w0, w0, #1
 * 0x27ce750  d65f03c0  ret
 * ```
 *
 * The store script's availability predicate (exposed as the `IsAllowedToMakePurchases`
 * UFunction) is this function. It becomes `mov w0, #1 ; ret`, so the banner can never come
 * back even if some other code path re-sets `+0xc4`. `RestorePreviousPurchases` is left
 * untouched.
 *
 * ### 3 — `UInjusticeIOSGameEngine::IsLoggedIntoGooglePlay` @ 0x1cbb8b0 — always true
 *
 * ```
 * 0x1cbb8b0  a9bf7bfd  stp  x29, x30, [sp, #-0x10]!
 * 0x1cbb8b4  910003fd  mov  x29, sp
 * 0x1cbb8b8  97f2ec54  bl   0x1976a08 <CallJava_isSignedIn>
 * 0x1cbb8bc  53001c00  uxtb w0, w0
 * 0x1cbb8c0  a8c17bfd  ldp  x29, x30, [sp], #0x10
 * 0x1cbb8c4  d65f03c0  ret
 * ```
 *
 * GPGS sign-in can never succeed for a re-signed build (Play Games rejects the signature),
 * so the engine's "logged into Google Play" predicate is permanently false. The store
 * script uses this to decide whether the storefront is usable, so the prologue becomes
 * `mov w0, #1 ; ret`. The Java `isSignedIn()` itself is untouched, so unrelated GPGS
 * features keep their real answer.
 *
 * Every window below occurs exactly **once** in the 52,977,760 byte library of the shipped
 * 3.5.1 build, so a different build refuses to patch instead of being corrupted.
 */
@Suppress("unused")
val injusticeNativeIapBypassPatch = rawResourcePatch(
    name = "Injustice Native Store Unlock",
    description = "Unlocks the native storefront without Google Play (patches " +
        "libInjusticeGAU.so: store never enters the blocked state, purchase gates are " +
        "skipped), so the free-item hooks can grant every pack.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_INJUSTICE)

    execute {
        applyNativeSites(
            patchName = "Injustice Native Store Unlock (3.5.1)",
            libPath = LIB_ARM64,
            sites = listOf(
                // 1 — UMicroTransactionAndroid::Init: strb w1,[x0,#0xc4] -> nop
                NativeSite(
                    label = "MicroTransactionAndroid::Init — keep purchases unblocked",
                    verifyHex = "210080521f1403391f18033901100339011c033916d0c617",
                    replacementHex = "210080521f1403391f1803391f2003d5011c033916d0c617",
                ),
                // 2 — IsAllowedToMakePurchases: !ldrb(0xc4) -> mov w0,#1 ; ret
                NativeSite(
                    label = "MicroTransactionAndroid::IsAllowedToMakePurchases — always true",
                    verifyHex = "0010433900000052c0035fd60010433900000052c0035fd6",
                    replacementHex = "0010433900000052c0035fd620008052c0035fd61f2003d5",
                ),
                // 3 — IsLoggedIntoGooglePlay: stp/mov -> mov w0,#1 ; ret
                NativeSite(
                    label = "InjusticeIOSGameEngine::IsLoggedIntoGooglePlay — always true",
                    verifyHex = "fd7bbfa9fd03009154ecf297001c0053fd7bc1a8c0035fd6",
                    replacementHex = "20008052c0035fd654ecf297001c0053fd7bc1a8c0035fd6",
                ),
            ),
        )
    }
}
