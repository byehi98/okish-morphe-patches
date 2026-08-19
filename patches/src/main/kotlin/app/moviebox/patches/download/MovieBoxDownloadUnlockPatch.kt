package app.moviebox.patches.download

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.moviebox.patches.shared.Constants.COMPATIBILITY_MOVIEBOX

@Suppress("unused")
val movieBoxDownloadUnlockPatch = bytecodePatch(
    name = "MovieBox Download Unlock",
    description = "Bypasses the server-side download access check so any resolution (including 1080p) can be downloaded on a free account.",
    default = true
) {
    compatibleWith(COMPATIBILITY_MOVIEBOX)

    execute {
        // Force the checkAccess callback to ALWAYS start the download:
        // replace g()'s body with a direct call to sibling h() (= the start
        // download wrapper). Skips the dto.hasAccess gate, the rate-limit
        // wrapper and the quota consume (free_download_count stays intact).
        //
        // g() has .registers 23 (.locals 21) so p0 == register v21, which is
        // out of range for the 4-bit invoke-static {v..} operand form. Stage
        // the fragment in v0 with the 16-bit move-object/from16 form first,
        // then invoke h() from the low register.
        DownloadAccessCheckFingerprint.method.addInstructions(0, """
            move-object/from16 v0, p0
            invoke-static {v0}, Lcom/transsnet/downloader/fragment/DownloadReDetectorGroupMainFragment${'$'}initView${'$'}4${'$'}2;->h(Lcom/transsnet/downloader/fragment/DownloadReDetectorGroupMainFragment;)Lkotlin/Unit;
            move-result-object v0
            return-object v0
        """.trimIndent())
    }
}