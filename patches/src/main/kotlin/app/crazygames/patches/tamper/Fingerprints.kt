package app.crazygames.patches.tamper

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

// ── Installer-name tamper check (CrazyGames 1.7.14) ──────────────────────────
// CrazyGamesApplication.onCreate() silently kills the process when it was
// installed by a known patching tool. Verified by installing the Morphe Manager
// build on Android 17:
//
//   ApplicationExitInfo: reason=2 (SIGNALED) subreason=0 (UNKNOWN) status=9
//   logcat:              I Process : Sending signal. PID: <pid> SIG: 9
//
// No Java exception and no ANR — the app SIGKILLs itself.
//
// smali: classes/com/crazygames/crazygamesapp/CrazyGamesApplication.smali
//   .method public final onCreate()V
//     ... InstallSourceInfo (API 30+) / PackageManager.getInstallerPackageName()
//     if-nez v1, :cond_3c            ← null installer (adb install) → skip check
//     const-string v2, "SHA-256"
//     const-string v3, "ddf3401cac0d92a767306e43ec35eaeb"
//     invoke-virtual {v3, v1}, String;->concat(String;)String
//     ... MessageDigest.digest(utf8) → Lhj;->I(...) hex encode ...
//     sget-object v1, Lrr;->a:Ljava/util/Set;
//     invoke-interface {v1, v0}, Ljava/util/Set;->contains(Ljava/lang/Object;)Z
//     move-result v0
//   :goto_6a
//     if-eqz v0, :cond_99            ← contained → DO NOT skip
//     ... ActivityManager.getAppTasks() → finishAndRemoveTask() ...
//     invoke-static {}, Process;->myPid()I
//     invoke-static {p0}, Process;->killProcess(I)V
//   :cond_99
//     ... normal startup continues ...
//
// Lrr.<clinit> has a one-element set:
//   "b5a5c4ad73b440e70092cb6b9aa91e183423ccf4a6a15d3585aa8d36feef4632"
// which is exactly
//   sha256("ddf3401cac0d92a767306e43ec35eaeb" + "app.morphe.manager")
// (reproduced independently with sha256sum). Only Morphe Manager installs are
// blacklisted today; adb/morphe-cli installs have a null installer and skip the
// check, which is why the same APK "works from CLI, crashes from Manager".
//
// FILTER ORDER IS INSTRUCTION ORDER (the patcher matches filters sequentially),
// so the four anchors below must stay in this exact order:
//   getInstallerPackageName  →  "SHA-256"  →  salt  →  Process.killProcess
// The obfuscated helpers (se4/lhj/k21/nb) can change between builds; these four
// anchors cannot, because they are framework API calls plus the app's own salt.
// The salt is NOT a filter requirement on its own — it is included to pin the
// exact method if the vendor ever adds a second SHA-256 site — but the
// fingerprint also matches if only the framework anchors are present, so
// rotating the salt does not silently break the patch.
internal object InstallerTamperFingerprint : Fingerprint(
    definingClass = "Lcom/crazygames/crazygamesapp/CrazyGamesApplication;",
    name = "onCreate",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    filters = listOf(
        methodCall(
            definingClass = "Landroid/content/pm/PackageManager;",
            name = "getInstallerPackageName",
            returnType = "Ljava/lang/String;",
        ),
        string("SHA-256"),
        string("ddf3401cac0d92a767306e43ec35eaeb"),
        methodCall(
            definingClass = "Landroid/os/Process;",
            name = "killProcess",
            returnType = "V",
            parameters = listOf("I"),
        ),
    ),
)
