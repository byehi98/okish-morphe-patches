package app.docscanner.patches.declutter

import app.docscanner.patches.shared.Constants.COMPATIBILITY_DOCSCANNER
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags

private const val DASHBOARD = "Lcom/cv/docscanner/views/guide/DashboardInfoLayout;"

/**
 * `DashboardInfoLayout.b(ArrayList)r09` — the weighted-random server banner picker for the home
 * screen (in 6.9.9 this was the `e()` method).
 *
 * Confirmed 6.9.14: classes3/com/cv/docscanner/views/guide/DashboardInfoLayout.smali:186
 *   `.method public static b(Ljava/util/ArrayList;)Lr09;` / `.registers 8`
 *
 * Called from `c()` at :1439 after the installed-banner list is built. Returning null hides the
 * cross-promotion / upsell banner block under the home screen; the premium patch already removes
 * the PREMIUM_INFO_LOCAL_BANNER branch because `v78.a()` checks n78.b().
 */
private val serverBannerPicker = Fingerprint(
    definingClass = DASHBOARD,
    returnType = "Lr09;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Ljava/util/ArrayList;")
)

/**
 * `DashboardInfoLayout.d(List)r09` — the per-key banner picker (`PDF_TOOLS_LOCAL_BANNER`,
 * `THEME_INFO_LOCAL_BANNER`, `SYNC_INFO_LOCAL_BANNER`, …), i.e. the old `d(String)Z` role.
 *
 * Confirmed 6.9.14: classes3/com/cv/docscanner/views/guide/DashboardInfoLayout.smali:1191
 *   `.method public final d(Ljava/util/List;)Lr09;` / `.registers 6`
 *
 * Called from `c()` at :954; returning null removes the remaining server-driven promo cards.
 */
private val configBannerPicker = Fingerprint(
    definingClass = DASHBOARD,
    returnType = "Lr09;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("Ljava/util/List;")
)

/**
 * Doc Scanner Declutter
 *
 * Removes the server-driven promo / cross-sell banners from the app's home screen. Both pickers
 * are forced to return null; the rest of the dashboard (recent files, tools, ads are already
 * handled by the premium patch) is untouched.
 *
 * 6.9.14 changed the entry points: the old `d(String)Z` / `e()r09` pair is now `d(List)r09` /
 * `b(ArrayList)r09`, so the Loki fingerprints (which pinned the 6.9.9 names) no longer match.
 */
@Suppress("unused")
val docScannerDeclutterPatch = bytecodePatch(
    name = "Doc Scanner Declutter",
    description = "Removes the home screen's server-driven promo and cross-promotion banners. " +
        "Credits: Loki."
) {
    compatibleWith(COMPATIBILITY_DOCSCANNER)

    execute {
        // b(ArrayList)r09 — static, .registers 8 → v0 is a valid local.
        serverBannerPicker.method.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return-object v0
            """
        )

        // d(List)r09 — instance, .registers 6 → v0 is a valid local.
        configBannerPicker.method.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return-object v0
            """
        )
    }
}
