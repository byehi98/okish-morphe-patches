package app.moviebox.patches.download

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * com.transsnet.downloader.fragment.DownloadReDetectorGroupMainFragment$initView$4$2.g(...)
 *
 * The download-button click callback invoked after the server-side checkAccess
 * (IPremiumApi.mo71621q) completes. When PremiumV2CheckAccessDto.hasAccess is
 * false (free account / resolution denied) it shows a VIP dialog instead of
 * starting the download — this is THE server-gated download path.
 *
 * Mod parity: the mod flipped this gate (Boolean.TRUE → Boolean.FALSE) so
 * downloads start even when the server denies access. We replicate it more
 * cleanly by forcing g() to always call sibling h() (startDownload) directly,
 * skipping the rate-limit wrapper and quota consume.
 *
 * Class and method names are stable (downloader module not obfuscated, same
 * as the rest of the 0813.02 targets).
 */
object DownloadAccessCheckFingerprint : Fingerprint(
    definingClass = "Lcom/transsnet/downloader/fragment/DownloadReDetectorGroupMainFragment${'$'}initView${'$'}4${'$'}2;",
    name = "g",
    returnType = "Lkotlin/Unit;",
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC, AccessFlags.FINAL),
    parameters = listOf(
        "Lcom/transsnet/downloader/fragment/DownloadReDetectorGroupMainFragment;",
        "Lcom/transsion/memberapi/PremiumV2CheckAccessDto;"
    )
)