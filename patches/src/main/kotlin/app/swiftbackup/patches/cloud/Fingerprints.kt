package app.swiftbackup.patches.cloud

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * q86.e(action, path, data, hash, callback): PersistentConnection.putInternal — the single outgoing
 * write (setValue "p", updateChildren "m", transaction compare-and-put all funnel through it).
 * Firebase SDK is R8-obfuscated; names are pinned to 5.1.1.
 */
object PersistentConnectionPutFingerprint : Fingerprint(
    definingClass = "Lq86;",
    name = "e",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf(
        "Ljava/lang/String;",
        "Ljava/util/ArrayList;",
        "Ljava/lang/Object;",
        "Ljava/lang/String;",
        "Ltu6;"
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

// The remaining fingerprints are the anonymous gates that block cloud use. They all read
// jl4.a(isAnonymous(), TRUE) and branch on the result, so the patch only needs the method
// that owns the branch. Class/method names are R8-renamed and pinned to 5.1.1 by
// COMPATIBILITY_SWIFTBACKUP.

/**
 * CloudConnectActivity.P(Laf1;): the connect entry point. When the account is anonymous it calls
 * Const.O(this) — the "sign in with Google" sheet — and returns instead of connecting.
 * Gate: smali2/org/swiftapps/swiftbackup/cloud/connect/common/CloudConnectActivity.smali:356
 * (`if-eqz v0, :cond_20`).
 */
object CloudConnectAnonGateFingerprint : Fingerprint(
    definingClass = "Lorg/swiftapps/swiftbackup/cloud/connect/common/CloudConnectActivity;",
    name = "P",
    parameters = listOf("Laf1;"),
    returnType = "V"
)

/**
 * af1.getClient(): resolves the active client through clientProvider. One arm of the anonymous
 * check returns the empty c13.i client, the other builds the real one from clientProvider.
 * Gate: smali2/af1.smali:3449 (`if-nez v0, :cond_1e`). The isAnonymous() call is NOT in this
 * method (it is hidden in the synthetic Lvv lambda), which is why the patch matches the jl4.a
 * comparison instead of the isAnonymous() call.
 */
object CloudClientGateFingerprint : Fingerprint(
    definingClass = "Laf1;",
    name = "getClient",
    parameters = listOf(),
    returnType = "Lwd1;"
)

/**
 * td1.d(): cached network client accessor used by the backup/restore engine. Anonymous accounts
 * short-circuit to c13.i before the cached instance is built.
 * Gate: smali2/td1.smali:577 (`if-eqz v0, :cond_1f`).
 */
object CurrentClientGateFingerprint : Fingerprint(
    definingClass = "Ltd1;",
    name = "d",
    parameters = listOf(),
    returnType = "Lwd1;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC)
)

/**
 * j04.e(String): "deleteTaggedBackups" cleanup task. It carries an inlined copy of td1.d()'s
 * anonymous guard, so fixing td1.d() alone still leaves the anonymous account on c13.i here.
 *
 * Pinned by the class (the legacy BClient twin ai0.e carries the same log line) plus the log line
 * and the (String) → boolean signature.
 * Gate: smali2/j04.smali:670 (`if-eqz p0, :cond_a1`).
 */
object DeleteTaggedBackupsClientFingerprint : Fingerprint(
    definingClass = "Lj04;",
    parameters = listOf("Ljava/lang/String;"),
    returnType = "Z",
    filters = listOf(
        string("deleteTaggedBackups: Deleting cloud files")
    )
)

/**
 * br8.c(): upload session start. One arm of the anonymous check jumps to a block that raises
 * b6.m("Check failed.") — an IllegalStateException — instead of starting the upload; the
 * fall-through is the real start (state = RUNNING).
 * Gate: smali2/br8.smali:405 (`if-nez v0, :cond_a5`).
 */
object UploadSessionStartFingerprint : Fingerprint(
    definingClass = "Lbr8;",
    name = "c",
    parameters = listOf(),
    returnType = "Lar8;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL)
)

/**
 * ks2.c(): download session start, the mirror image of br8.c(): one arm of the anonymous check
 * jumps to the b6.m("Check failed.") block, the fall-through is the real start.
 * Gate: smali2/ks2.smali:241 (`if-nez v0, :cond_48`).
 */
object DownloadSessionStartFingerprint : Fingerprint(
    definingClass = "Lks2;",
    name = "c",
    parameters = listOf(),
    returnType = "Lf17;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL)
)

/**
 * kl3.a(ug2, z): reads a reference from the local cache. Anonymous accounts short-circuit to the
 * al3.m placeholder and never reach Tasks.await(ug2.e()).
 * Gate: smali2/kl3.smali:109 (`if-eqz p1, :cond_30`) — the later of the two branches on p1; the
 * one at line 48 belongs to the `z` parameter, not to the anonymous check.
 */
object ReadReferenceGateFingerprint : Fingerprint(
    definingClass = "Lkl3;",
    name = "a",
    parameters = listOf("Lug2;", "Z"),
    returnType = "Lat8;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC)
)

/**
 * kl3.c(ug2, value): writes a value. Anonymous accounts short-circuit to the gl3.c placeholder and
 * never reach Tasks.await(ug2.k(value)).
 * Gate: smali2/kl3.smali:796 (`if-eqz v0, :cond_30`).
 *
 * kl3.b() is deliberately left alone: it is the transaction path and forcing it would make the
 * anonymous account wait for a 30s latch timeout.
 */
object SetValueGateFingerprint : Fingerprint(
    definingClass = "Lkl3;",
    name = "c",
    parameters = listOf("Lug2;", "Ljava/lang/Object;"),
    returnType = "Lil3;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC)
)
