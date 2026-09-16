package app.dantheaman.patches.save

import app.dantheaman.patches.shared.Constants.COMPATIBILITY_DTM
import app.morphe.patcher.patch.rawResourcePatch

/**
 * Dan the Man — Unlimited money (save injection), asset payload.
 */
@Suppress("unused")
val dtmActionsOnlineAssetPatch = rawResourcePatch(
    name = "Unlimited money (save bundle)",
    description = "Packs the special save file (unlimited coins & gems, everything unlocked) inside the app.",
    default = true
) {
    compatibleWith(COMPATIBILITY_DTM)

    execute {
        val assetFile = get("assets/actions.online", true)
        assetFile.writeBytes(DtmActionsOnlineBytes.bytes)
        println("DTM save bundle: assets/actions.online written (${DtmActionsOnlineBytes.bytes.size} bytes)")
    }
}
