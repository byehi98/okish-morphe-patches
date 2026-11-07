package app.dumbbellworkout.patches.antitamper

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * The app's certificate reader — `L?08;->b(<Activity>)Ljava/lang/String;`.
 *
 * Dumbbell Workout ships this method 38 times, once per duplicated anti-tamper
 * class (`a08 … w08` and `kz7 … zz7`, all in `smali/classes3/`). Each copy has the
 * same shape (verified in smali, e.g. `classes3/a08.smali:60`):
 *
 * ```
 * .method public static b(Ldumbbellworkout/…/SetFirstReminderActivity;)Ljava/lang/String;
 *     .registers 5
 *     const-string v0, ""
 *     …
 *     const-string v2, "mPM"
 *     invoke-virtual {v1, v2}, Ljava/lang/Class;->getDeclaredField(Ljava/lang/String;)Ljava/lang/reflect/Field;
 *     …
 *     invoke-virtual {v0}, Landroid/content/pm/Signature;->toCharsString()Ljava/lang/String;
 * ```
 *
 * MATCH STRATEGY — deliberately minimal and structural, never name-based:
 *  - `string("mPM")` is the reflective `PackageManager.mPM` field lookup and occurs
 *    exactly once per reader class (and nowhere else in it).
 *  - `methodCall(name = "toCharsString")` is the `Signature.toCharsString()` call
 *    that produces the 1826-char certificate hex.
 * R8 renames the class and the method every release, so neither `name` nor
 * `definingClass` is set. The single parameter differs per class
 * (`SetFirstReminderActivity`, `AppCompatActivity`, `Lc90;`, …), so `parameters`
 * is deliberately left unset — constraining it would drop readers.
 *
 * The filters are ordered to match the smali instruction order: the `"mPM"`
 * `const-string` precedes the `toCharsString` invoke.
 *
 * Expected match count: **38** (asserted by the patch, which refuses to emit an
 * APK when it sees anything else).
 */
object SignatureReaderFingerprint : Fingerprint(
    returnType = "Ljava/lang/String;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    filters = listOf(
        string("mPM"),
        methodCall(name = "toCharsString"),
    )
)
