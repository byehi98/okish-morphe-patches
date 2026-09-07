package app.ubisoftpop.patches.il2cpp

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.PatchException
import app.ubisoftpop.patches.shared.Constants.COMPATIBILITY_POP
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/**
 * Nops every call site of the hardener's native signature verifier
 * LPYfMYTzXgBXhkhkCt;->a(Landroid/content/Context;)V.
 *
 * The verifier is a native method in libGMFotUwvHx.so injected into every
 * activity's init path; it SIGSEGV-kills any re-signed build ~2-4 s after
 * launch (stock re-signed APK with zero patches dies — device-verified in
 * notes/crack-case-study.md).
 *
 * Call-site nopping is used instead of method surgery: replacing the body of
 * a single-method default-package class corrupted the dex in the patcher
 * (ins_size > registers_size).
 */
@Suppress("unused")
val antiTamperBypassPatch = bytecodePatch(
    name = "Prince of Persia: Anti-tamper bypass",
    description = "The game has a built-in self-check that shuts it down if it detects the app was modified. This patch quietly turns that check off so the game can start.",
    default = true
) {
    compatibleWith(COMPATIBILITY_POP)

    execute {
        val verifierClass = "LPYfMYTzXgBXhkhkCt;"
        val verifierMethod = "a"
        var patched = 0

        classDefForEach { classDef ->
            val touchesVerifier = classDef.methods.any { m ->
                val impl = m.implementation ?: return@any false
                impl.instructions.any { ins ->
                    val ref = (ins as? ReferenceInstruction)?.reference as? MethodReference
                    ref != null &&
                        ref.definingClass == verifierClass &&
                        ref.name == verifierMethod
                }
            }
            if (!touchesVerifier) return@classDefForEach

            val mutable = mutableClassDefBy(classDef)
            for (method in mutable.methods) {
                val impl = method.implementation ?: continue
                var idx = 0
                while (idx < impl.instructions.size) {
                    val ref = (impl.instructions[idx] as? ReferenceInstruction)
                        ?.reference as? MethodReference
                    if (ref != null && ref.definingClass == verifierClass && ref.name == verifierMethod) {
                        method.replaceInstruction(idx, "nop")
                        patched++
                        break
                    }
                    idx++
                }
            }
        }

        if (patched == 0) {
            throw PatchException("No call sites of the native verifier found - app layout changed?")
        }
        println("PopFullGame: nopped $patched verifier call site(s)")
    }
}
