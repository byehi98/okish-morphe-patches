package app.ubisoftpop.patches.il2cpp

import app.morphe.patcher.patch.rawResourcePatch
import app.ubisoftpop.patches.shared.Constants.COMPATIBILITY_POP

/**
 * **Prince of Persia: The Lost Crown — full game unlock as a pure native (hex-class) patch.**
 *
 * This is the ONLY patch in the PoP set: a single raw-resource replacement of
 * the game's own security helper, `lib/arm64-v8a/libGMFotUwvHx.so`
 * (original size 1,854,704 B, shipped in the arm64 split). The **DEX is 100 %
 * untouched** — no fingerprints, no `bytecodePatch`, no injected
 * `System.loadLibrary`, no nopped verifier call sites. The engine binary
 * bytes live in [PopFullGameSoBytes] (regenerated from the nix/NDK r27 build
 * by `ndk/lib/gen_so_bytes.py`).
 *
 * ### Why overwriting that exact lib works (maintainer notes)
 *
 * The hardener loads its lib in every activity's init path:
 * `System.loadLibrary("GMFotUwvHx")` inside `KKVmaehEHIdkQfv`, which
 * `UnityPlayerActivity.onCreate` calls AFTER the original Unity body
 * (`qVJjNpZTozO(p1)` — UnityPlayer construction + native init). The game
 * itself therefore:
 *
 * 1. **Extracts and loads our file for us** — `get(path, true)` below returns
 *    the extracted entry and `writeBytes` overwrites it in place; the APK's
 *    zip entry `lib/arm64-v8a/libGMFotUwvHx.so` becomes our combined engine
 *    (same path → no manifest/zip-layout changes, no DEX trigger needed).
 * 2. **Runs `JNI_OnLoad` at exactly the right time** — inside the hardener
 *    block, i.e. AFTER Unity's native init, so il2cpp's packed runtime has
 *    already been initialised before our worker starts touching it.
 *
 * ### Anti-tamper verifier, killed natively
 *
 * The hardener's only DEX-visible native entry is the default-package class
 * `PYfMYTzXgBXhkhkCt`'s single method `a(Landroid/content/Context;)V`
 * (DEX: `LPYfMYTzXgBXhkhkCt;`), called from every activity's init path. It
 * signature-checks re-signed builds and SIGSEGV-kills them ~2–4 s in
 * (device-verified, see `notes/crack-case-study.md` §3.1). Our `JNI_OnLoad`
 * neutralises it two ways, in order:
 *
 * - **Primary:** decode the XOR-obfuscated (key 0x5A) class/method/signature
 *   strings and `RegisterNatives` them to the no-op stub `vfy_stub` — runs
 *   before `System.loadLibrary` returns, hence before the first `a()` call.
 * - **Fallback:** the exported symbol
 *   `Java_PYfMYTzXgBXhkhkCt_a` (default visibility) lets ART resolve the
 *   method by name if registration failed (FindClass null in an unusual
 *   classloader context). This leaks the obfuscated class name in the ELF
 *   dynamic strings — accepted: it is already plaintext in the DEX.
 *
 * Logcat (tag `PopFullGame`) reports only booleans:
 * `verifier stub bound=1` (registered) or `bound=0` (symbol fallback).
 *
 * ### Gated unlock (engine v6 — fixes the v5 timing defects)
 *
 * v5 polled the IAP singleton and fired the cheat for only 5 rounds: runs
 * that started during the content download lost every round (trial screen),
 * and runs that won fired the cheat ~0–1 s into boot, mid-AssetManager —
 * the resulting ownership-changed → `ReloadToBootstrap` reset zeroed the
 * download state machine mid-flight → NREs → frozen spinner. v6 is
 * gate-driven (see `notes/grant-timing-and-startflow.md`), all bounds are
 * attempt-counts, never wall-clock sleeps:
 *
 * 1. **Readiness** (unchanged from v5): poll
 *    `dlopen("libil2cpp.so", RTLD_NOW|RTLD_NOLOAD)` (side-effect free) until
 *    Unity has mapped the lib (up to 2 min), then wait in 100 ms steps for
 *    the il2cpp runtime thread `GC Scheduler` to exist (fallback ceiling 8 s).
 * 2. **`[gate]`** — class poll extended to ~10 min, then poll the
 *    `POP_Mobile.AssetManager` singleton's raw fields via the il2cpp field
 *    API: gate opens when `m_currentState >= 3 && m_currentStateStatus == 2`
 *    twice ~1 s apart (the proven ~+30 s window where the reload-reset is
 *    harmless). 900 × 2 s attempts (30 min) — download runs simply wait
 *    until the download finishes instead of losing the race.
 * 3. **`[grant]`/`[verify]`** — invoke the game's own `CheatBuyFullGame()`
 *    once on the gate and verify via `get_HasFullGamePurchased == 1`.
 * 4. **`[hook]`** — after a 5 s settle, overwrite the code prologues of
 *    `DlcManager.get_IsFreeTrial → false` (31 callers: boot-flow and
 *    main-menu start gates — the real trial gate) and
 *    `IAPManager.get_HasFullGamePurchased → true` (0 direct callers; covers
 *    reflection readers). Original prologue bytes are checked before
 *    patching; any failure logs `[hook] hook failed: <reason>` and continues
 *    grant-only — the historical known-good behavior. Never crashes.
 * 5. **`[monitor]`** — 60 × 30 s: read the RAW
 *    `m_lastFullGamePurchasedStatus` field (not the hooked getter); if a
 *    server revalidation reverts it to 0, re-invoke the cheat and re-check.
 *
 * The engine stays alive through the monitor loop and then logs
 * `engine finished (granted=<0|1>, hooked=<0|1>)`. All v6 names and log
 * lines are XOR-obfuscated (key 0x5A) like the rest of the engine — the
 * built `.so` contains no plain `AssetManager`/`m_currentState`/
 * `IsFreeTrial` strings.
 *
 * ### Fast unlock lineage (v4/v5 readiness — kept in v6)
 *
 * The previous set shipped a stale v3 engine whose worker did a fixed
 * `sleep(30)` before touching il2cpp. v5 waits actively instead:
 *
 * 1. `pre_probe_delay_sec = 0`, then poll
 *    `dlopen("libil2cpp.so", RTLD_NOW|RTLD_NOLOAD)` (side-effect free) until
 *    Unity has mapped the lib (`map_wait_attempts = 120` → up to 2 min for
 *    non-Unity-first load paths);
 * 2. **GC-Scheduler readiness fast path:** every 100 ms scan the per-task
 *    comm files under `/proc/self/task/<tid>/comm` for the il2cpp runtime
 *    thread `GC Scheduler` (spawned during Unity's native init; 12 chars,
 *    fits comm's 15-char limit). The moment it exists the runtime is
 *    initialised/unpacked → proceed immediately (~1–2 s after startup
 *    instead of 30 s).
 * 3. Fallback ceiling: if the thread never appears within
 *    `unpack_grace_sec = 8`, log `readiness timeout … proceeding anyway`
 *    and continue (the exact device-proven v4 grace behavior).
 *
 * After readiness the core resolves the il2cpp exports (including the
 * field/parent APIs the gate uses), then hands off to the v6 phase, which
 * scans **every** assembly (never filter by image name — `IAPManager` lives
 * in `Alkawa.Engine.dll`, `AssetManager` in a `POP_Mobile` namespace).
 *
 * ### Honest caveat
 *
 * The original hardener lib's non-verifier functionality (its possible
 * attestation/telemetry) is now **absent** — the replacement only stubs the
 * verifier and runs the unlock engine. See `notes/rebuild-log.md`
 * ("Engine v5").
 *
 * Build flow: `nix build .#libPopFullGame` in
 * `analysis/com.ubisoft.princeofpersia.thelostcrown/ndk/` →
 * `gen_so_bytes.py` → this file.
 */
@Suppress("unused")
val hardenerLibPatch = rawResourcePatch(
    name = "Prince of Persia: Full Game Unlock (native)",
    description = "Replaces the game's built-in security helper with a friendly one that unlocks the full game right at startup — no waiting.",
    default = true
) {
    compatibleWith(COMPATIBILITY_POP)

    execute {
        // In-place raw replacement: get(path, true) extracts the existing
        // zip entry; writeBytes overwrites it with the engine bytes. This IS
        // the hex/native-class patch — the DEX is never read or written.
        val f = get("lib/arm64-v8a/libGMFotUwvHx.so", true)
        f.writeBytes(PopFullGameSoBytes.bytes())
    }
}
