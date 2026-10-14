package app.crashofcars.patches.expansion

import app.crashofcars.patches.shared.Constants.COMPATIBILITY_CRASH_OF_CARS
import app.morphe.patcher.Match
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.Opcode

private const val LICENSE_CHECKER = "Lcom/unity3d/plugin/downloader/d/f;"
private const val DOWNLOAD_STATE = "Lcom/unity3d/plugin/downloader/d/b;"
private const val LICENSE_RESPONSE = "Lcom/unity3d/plugin/downloader/d/h;"
private const val LICENSE_CALLBACK = "Lcom/unity3d/plugin/downloader/d/g;"
private const val EXPANSION_ERROR = "Lcom/unity3d/plugin/downloader/d/l;"

/**
 * Crash of Cars — Sideload / APK-Expansion bypass (Unity `com.unity3d.plugin.downloader`)
 * (com.notdoppler.crashofcars v1.9.34 / versionCode 1090034)
 *
 * The game ships its 153 MB of assets as a Google Play **APK Expansion (OBB)**.
 * On a sideloaded / re-signed install the expansion gate never clears and the
 * game dies on *"Please download the game from Google Play"* →
 * *"Download failed because you may not have purchased this app"*
 * (`res/values/strings.xml:210`, `state_failed_unlicensed`). The OBB itself is
 * present, correctly named and md5-clean — the downloader simply never looks at
 * it locally, it trusts Play's licensing service unconditionally.
 *
 * ⚠️ **This patch does not supply the expansion data.** It clears the licensing
 * gate only. The 153 MB `.obb` must already be on the device at
 * `Android/obb/com.notdoppler.crashofcars/main.<versionCode>.com.notdoppler.crashofcars.obb`
 * or the game stalls on the loading screen anyway — hence the explicit warning at
 * the top of the user-facing `description`. Verified by building this MPP with
 * this patch disabled: the game cannot be made to start without it.
 *
 * The **stock unpatched APK fails identically**, so this is a distribution-model
 * problem rather than a patch regression — see
 * `analysis/com.notdoppler.crashofcars/notes/deployment-waydroid.md` §Blocker #2.
 *
 * Two hooks, and they interlock — either one alone leaves the state machine stuck:
 *
 * 1. [`ExpansionInitiateDownloadFingerprint`] — `impl/k.initiateDownload(...)`
 *    returns 0 **and still boots the downloader service**. The service's `"LVLDL"`
 *    loop calls it at `i.smali:372` and branches on the result:
 *    `0 → state 5 (COMPLETED)`; `1 → "In LVL checking loop!" → a.a(0xf) →`
 *    `state 15 (failed_UNLICENSED)` plus a `RuntimeException`.
 *
 * 2. [`LicenseResponseAllowFingerprint`] — the LVL response handler always takes
 *    the *allowed* branch (`Ld/g;->b(I)V`). Without it a sideloaded install
 *    dispatches the denied branch (`Ld/g;->a(I)V`, `i.smali:29`), which sets
 *    **state 16 (failed_fetching_url)** and calls `k.a(false)` to stop the
 *    service — a terminal dead end hook 1 could never rescue.
 *
 * Deliberately NOT patched:
 *
 * - **`Ld/b;->a()Z`** (smali `b.smali:735`) — this is the *download-progress*
 *   predicate, not a license predicate: it compares `System.currentTimeMillis()`
 *   against the download's start/end window. It has three callers (`f.smali:321`,
 *   `f.smali:450`, `h.smali:49`) and forcing it true would make the service
 *   believe the OBB was verified without ever checking anything.
 * - **The OBB verification loop** in `i.b(I)V` (`i.smali:110-320`) — it md5-checks
 *   every entry against the manifest before committing. That part of the gate is
 *   legitimate and correct as shipped.
 * - **The service's public key** — it is the unedited template literal
 *   `"REPLACE THIS WITH YOUR PUBLIC KEY - DONE FROM C#"`
 *   (`UnityDownloaderService.smali:6`), so signature verification was never going
 *   to hard-fail anyway and there was nothing to gain by touching it.
 */
@Suppress("unused")
val crashOfCarsExpansionLicenseBypassPatch = bytecodePatch(
    name = "Crash of Cars Sideload / APK Expansion Bypass",
    description = "IMPORTANT: this patch only removes the licensing gate — it does NOT supply the game data. The 153 MB expansion file must already be on your device before the game will start. Copy main.<versionCode>.com.notdoppler.crashofcars.obb into Android/obb/com.notdoppler.crashofcars/ (on most phones that is the internal Android/obb folder, not the one on the SD card) using a file manager or an ADB push. If the file is missing or in the wrong place the game will still stall on the loading screen no matter what this patch does. Aside from that: lets the game start from a sideloaded APK instead of a Google Play install. The downloader refuses to use the expansion file even when it is present because Play's licensing server rejects a re-signed package, so the game stops on \"Download failed because you may not have purchased this app\" before it ever reaches the menu. This forces the expansion downloader past both gates: it always reports success instead of a stale or unlicensed error, and it always takes the licensed path when Play answers. The expansion data is still md5-verified file by file exactly as shipped, so a genuinely incomplete or corrupt download is still reported rather than silently ignored.",
    default = true
) {
    compatibleWith(COMPATIBILITY_CRASH_OF_CARS)

    execute {
        // ── Fail loudly rather than ship a half-bypassed APK ──
        ExpansionInitiateDownloadFingerprint.matchAllOrNull()
            .requireExactlyOne("impl/k.a(Context, PendingIntent, Class)I")
        LicenseResponseAllowFingerprint.matchAllOrNull()
            .requireExactlyOne("d/f.a(h)V")

        // ═══ 1. initiateDownload — always start the service, always return 0 ═══
        //
        // `.registers 15` → locals v0..v11, p0=v12 (Context), p1=v13
        // (PendingIntent), p2=v14 (Class).
        //
        // The original body carries **no exception table and no labels**
        // (verified: `impl.getTryBlocks()` is empty, 61 plain instructions), so a
        // label-free straight-line replacement at index 0 is safe and ART skips
        // verifying the now-unreachable remainder.
        //
        // Reproducing the `Intent` is NOT optional. At `k.smali:311-315` the
        // original only reaches `startService` when the result is non-zero, so a
        // bare `const/4 v0, 0x0; return v0` would leave the downloader service
        // dead on the first `UnityDownloaderActivity.onCreate` call and strand
        // `UnityDownloaderActivity.GetState()` on `1 = idle` forever. The block
        // below is the original's `:cond_62` verbatim — same Intent, same `"EPI"`
        // extra, same `startService` — followed by the forced `0`.
        //
        // Parameter registers: p0 = Context, p1 = PendingIntent, p2 = Class.
        // Getting p0/p1 backwards is a *silent* dex-verification failure, not a
        // crash at this line: passing the PendingIntent where the verifier expects a
        // Context rejects the whole `impl.k` class, and because
        // `UnityDownloaderService` extends `k`, ART then refuses to load it too
        // ("Rejecting class ... that attempts to sub-type erroneous class
        // com.google.android.vending.expansion.downloader.impl.k"). Both Context
        // operands below MUST be `p0`.
        ExpansionInitiateDownloadFingerprint.method.addInstructions(0, """
            new-instance v0, Landroid/content/Intent;
            invoke-direct {v0}, Landroid/content/Intent;-><init>()V
            invoke-virtual {p0}, Landroid/content/Context;->getPackageName()Ljava/lang/String;
            move-result-object v1
            invoke-virtual {p2}, Ljava/lang/Class;->getName()Ljava/lang/String;
            move-result-object v2
            invoke-virtual {v0, v1, v2}, Landroid/content/Intent;->setClassName(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;
            const-string v1, "EPI"
            invoke-virtual {v0, v1, p1}, Landroid/content/Intent;->putExtra(Ljava/lang/String;Landroid/os/Parcelable;)Landroid/content/Intent;
            invoke-virtual {p0, v0}, Landroid/content/Context;->startService(Landroid/content/Intent;)Landroid/content/ComponentName;
            const/4 v0, 0x0
            return v0
        """.trimIndent())

        // ═══ 2. LVL response handler — always take the ALLOWED branch ═══
        //
        // `.registers 5` → locals v0..v2, p0=v3 (the `f` listener),
        // p1=v4 (the `h` license response). No wide values, no label overlap.
        //
        //   v0 = the shared `d:Ld/b;` download-state object
        //   v1 = the null second argument to `Ld/b;->a(ILd/l;)V`
        //   v2 = 0x123, EXPANSION_ERROR_UNLICENSED (carried over from the original)
        //
        // `move-result-object p1` deliberately clobbers p1 with the `Ld/g;`
        // callback, exactly as the original body does.
        LicenseResponseAllowFingerprint.method.addInstructions(
            indexInsideProtectedRegion(LicenseResponseAllowFingerprint.method),
            """
                # Keep the original retryCount bookkeeping (d/b.smali:156).
                iget-object v0, p0, $LICENSE_CHECKER->d:$DOWNLOAD_STATE
                const/4 v1, 0x0
                const/16 v2, 0x123
                invoke-virtual {v0, v2, v1}, $DOWNLOAD_STATE->a(I$EXPANSION_ERROR)V

                # The original branched on Ld/b;->a()Z here and dispatched
                # Ld/g;->a(I)V (denied) when it was false — which lands in
                # impl/i.smali:29, sets state 16 and calls k.a(false) to STOP the
                # service. Always take the allowed branch instead.
                invoke-virtual {p1}, $LICENSE_RESPONSE->a()$LICENSE_CALLBACK
                move-result-object p1
                invoke-interface {p1, v2}, $LICENSE_CALLBACK->b(I)V

                # Release the monitor that DECLARED_SYNCHRONIZED expanded to at the
                # top of the body. Reaching the .catchall instead routes through
                # the handler's own monitor-exit, so the lock is balanced either way.
                monitor-exit p0
                return-void
            """.trimIndent()
        )
    }
}

/**
 * Returns the index at which to inject code so that it runs **inside** this
 * method's protected region, immediately after the instruction that carries the
 * `.catchall` try block's `:try_start` label.
 *
 * `d/f.a(h)V` is `declared-synchronized`, so baksmali expanded
 * `ACC_DECLARED_SYNCHRONIZED` into a literal `monitor-enter p0` / `monitor-exit p0`
 * pair in the body, and it also carries
 * `.catchall {:try_start_1 .. :try_end_20} :catchall_22` whose handler does
 * `monitor-exit p0` before rethrowing. Injecting anywhere else risks leaking the
 * lock:
 *
 * - **before `:try_start_1`** → the new code sits outside the `.catchall`, so a
 *   throw from `Ld/g;->b(I)V` escapes with the monitor still held;
 * - **after `:try_end_20`** → the `monitor-exit` would run twice.
 *
 * Inside the region both cases are covered: the happy path releases the monitor
 * with our explicit `monitor-exit p0`, and the throwing path falls into the
 * existing `:catchall_22`.
 *
 * ⚠️ **The `+ 1` is load-bearing and easy to get backwards.** dexlib2 attaches a
 * `Label` to the `MethodLocation` of the **instruction it points at**, so the
 * label *travels with* that instruction. Injecting *at* the try-start
 * instruction's own index therefore pushes the new code *above* `:try_start` and
 * out of the protected region — the happy path still balances (one
 * `monitor-enter`, one `monitor-exit`) so it looks fine, but any throw leaks the
 * lock. Injecting at `start + 1` places the code after the label while the
 * region, which still ends at `:try_end_20`, grows to cover it.
 *
 * The index is resolved from the method's own try block rather than hard-coded,
 * so it survives a baksmali label re-layout. Two dexlib2 details make it work:
 *
 * 1. `tryBlock.start.location` is the `MethodLocation` the `:try_start` label is
 *    attached to, and every `BuilderInstruction` exposes its own `location`, so
 *    the two can be matched by identity to locate that instruction.
 * 2. `MutableMethodImplementation.getInstructions()` exposes only the real
 *    instructions while `addInstruction(index, …)` indexes the underlying list,
 *    which also holds label entries. For this method the two index spaces agree
 *    for every instruction (verified against dexlib2 directly: 22 raw entries for
 *    21 instructions, the single extra being the trailing end-of-method label).
 *
 * Verified end to end on the patched APK: after injection the method holds 30
 * instructions with `.catchall` spanning `[1 .. 25]`, and the 9 injected
 * instructions sit at `[2 .. 10]` — inside it — while `monitor-enter` stays at
 * index 0.
 */
private fun indexInsideProtectedRegion(method: MutableMethod): Int {
    val implementation = method.implementation
        ?: throw PatchException(
            "Crash of Cars Expansion Bypass: ${method.definingClass}->${method.name}" +
                " has no method implementation — nothing to patch."
        )

    val tryBlock = implementation.tryBlocks.firstOrNull()
        ?: throw PatchException(
            "Crash of Cars Expansion Bypass: ${method.definingClass}->${method.name}" +
                " no longer has an exception table, so its declared-synchronized lock" +
                " would leak on any throw. Refusing to patch it blind."
        )

    val startLocation = tryBlock.start.location
    val instructions = implementation.instructions

    val tryStart = instructions.indexOfFirst { it.location === startLocation }
    if (tryStart < 0) {
        throw PatchException(
            "Crash of Cars Expansion Bypass: could not find the instruction carrying" +
                " the try_start label of ${method.definingClass}->${method.name}."
        )
    }

    // The monitor must already be held on entry, otherwise the trailing
    // `monitor-exit p0` has nothing to release.
    val monitorIndex = instructions.indexOfFirst { it.opcode == Opcode.MONITOR_ENTER }
    if (monitorIndex < 0 || monitorIndex > tryStart) {
        throw PatchException(
            "Crash of Cars Expansion Bypass: ${method.definingClass}->${method.name} is no" +
                " longer declared-synchronized with its monitor-enter inside the method" +
                " body — the balanced monitor-exit assumption no longer holds."
        )
    }

    return tryStart + 1
}

/**
 * Throws [PatchException] unless [this] contains exactly one match — i.e. the
 * target method moved, was renamed, or the Unity downloader library changed
 * shape. A silently skipped hook would leave the expansion gate half-bypassed,
 * which is worse than not patching at all.
 */
private fun List<Match>?.requireExactlyOne(target: String) {
    val count = this?.size ?: 0
    if (count != 1) {
        throw PatchException(
            "Crash of Cars Expansion Bypass: $target matched $count time(s), expected" +
                " exactly 1. The Unity APK-Expansion downloader layout has changed —" +
                " refusing to emit a partially bypassed APK."
        )
    }
}