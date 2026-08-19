package app.moviebox.patches.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string

/**
 * com.transsion.member.MemberProvider.D()I — parallel download limit.
 *
 * Returns MMKV int kv_parallel_download_task_num (default 1 for free users;
 * the server raises it for members). Method name ("D") obfuscated but pinned
 * to this version; kv key string makes the match unambiguous.
 */
object ParallelDownloadLimitFingerprint : Fingerprint(
    definingClass = "Lcom/transsion/member/MemberProvider;",
    name = "D",
    returnType = "I",
    filters = listOf(
        string("kv_parallel_download_task_num")
    )
)
