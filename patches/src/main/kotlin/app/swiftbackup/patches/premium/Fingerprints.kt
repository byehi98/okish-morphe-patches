package app.swiftbackup.patches.premium

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * V.getA(): master premium gate (all premium features read it).
 * Pinned by definingClass + Ljx <init>/invoke order — no obfuscated names.
 * See analysis/swiftbackup/notes/offline-premium.md.
 */
object PremiumGateFingerprint : Fingerprint(
    definingClass = "Lorg/swiftapps/swiftbackup/common/V;",
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    filters = listOf(
        methodCall(
            definingClass = "Ljx;",
            name = "<init>"
        ),
        methodCall(
            definingClass = "Ljx;",
            name = "invoke"
        )
    )
)

/**
 * d45.a(): auth-state root (cached anon else Firebase user else null; null poisons intro + exit guards).
 * Pinned by MFirebaseUser return + V.getNon/getInstance order — no obfuscated names.
 * See analysis/swiftbackup/notes/offline-premium.md.
 */
object AuthStateRootFingerprint : Fingerprint(
    returnType = "Lorg/swiftapps/swiftbackup/anonymous/MFirebaseUser;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf(),
    filters = listOf(
        methodCall(
            definingClass = "Lorg/swiftapps/swiftbackup/common/V;",
            name = "getNon"
        ),
        methodCall(
            definingClass = "Lcom/google/firebase/auth/FirebaseAuth;",
            name = "getInstance"
        )
    )
)

/**
 * b45.a(): offline anon mint (pure local compute; same call real anon sign-in makes). Callee only, no standalone patch.
 * Pinned by two anon strings in smali order. See analysis/swiftbackup/notes/offline-premium.md.
 */
object OfflineAnonMintFingerprint : Fingerprint(
    returnType = "Lorg/swiftapps/swiftbackup/anonymous/MFirebaseUser;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf(),
    filters = listOf(
        string("anonymous@swiftbackup.app"),
        string("Anonymous user")
    )
)

/**
 * c64.j(Z): schedule-engine bridge (V.setA + AlarmReceiver toggle).
 * Pinned by V.setA + DECLARED_SYNCHRONIZED exact-match (SYNCHRONIZED would fail) — no obfuscated names.
 * See analysis/swiftbackup/notes/offline-premium.md.
 */
object ScheduleEnableGuardFingerprint : Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL, AccessFlags.DECLARED_SYNCHRONIZED),
    parameters = listOf("Z"),
    filters = listOf(
        methodCall(
            definingClass = "Lorg/swiftapps/swiftbackup/common/V;",
            name = "setA"
        )
    )
)
