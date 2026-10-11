package app.swiftbackup.patches.cloud

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * wz5.e(action, path, data, hash, callback): PersistentConnection.putInternal — the single outgoing
 * write (setValue "p", updateChildren "m", transaction compare-and-put all funnel through it).
 * Firebase SDK is R8-obfuscated; names are pinned to 5.1.0.
 */
object PersistentConnectionPutFingerprint : Fingerprint(
    definingClass = "Lwz5;",
    name = "e",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf(
        "Ljava/lang/String;",
        "Ljava/util/ArrayList;",
        "Ljava/lang/Object;",
        "Ljava/lang/String;",
        "Lcl6;"
    )
)

/**
 * w8.run(): shared R8 runnable whose Repo branch is Repo.deferredInitialization (creates the
 * connection + both sync trees, restores persisted writes, resets .info/connected).
 * Pinned by a string unique to that branch.
 */
object RepoDeferredInitFingerprint : Fingerprint(
    name = "run",
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        string("Write ids were not in order.")
    )
)
