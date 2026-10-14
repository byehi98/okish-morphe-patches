package app.crashofcars.patches.expansion

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

// ── Google Play APK-Expansion downloader (Unity `com.unity3d.plugin.downloader`) ──
//
// Crash of Cars 1.9.34 (versionCode 1090034) ships its 153 MB game data as a
// Google Play **APK Expansion (OBB)**. On a sideloaded / re-signed install the
// expansion gate never clears, so the game never gets past
// `GoogleOBBDownloadScene:Start()` → `UnityDownloaderActivity` →
// `LicenseChecker: checkLicense` → "Download failed because you may not have
// purchased this app" (`res/values/strings.xml:210` → `state_failed_unlicensed`).
//
// The stock, unpatched APK fails identically, so this is a distribution-model
// problem, not a patch regression — see
// `analysis/com.notdoppler.crashofcars/notes/deployment-waydroid.md` §"Blocker #2".
//
// Smali verified against `analysis/com.notdoppler.crashofcars/smali/`:
//   L195  classes/com/google/android/vending/expansion/downloader/impl/k.smali
//         .method public static a(Context, PendingIntent, Class)I
//   L305  classes4/com/unity3d/plugin/downloader/d/f.smali
//         .method private declared-synchronized a(h)V
//
// Both names are the ProGuard-obfuscated `a` in their own package, but the
// *class* descriptors, the parameter types and the return types are unique and
// each signature occurs exactly once per class (verified by `rg`), so no body
// filters are needed.
//
// `accessFlags` matching in Morphe folds the given flags into an int and
// compares it EXACTLY — an over-specified flag list silently resolves to zero
// matches. Note the asymmetry below: `k` is `public static`, `f` is
// **`private declared-synchronized`** (ACC_DECLARED_SYNCHRONIZED = 0x20000,
// *not* ACC_SYNCHRONIZED = 0x20; baksmali prints `declared-synchronized`, and
// that is the flag the matcher wants).

/**
 * `com.google.android.vending.expansion.downloader.impl.k.a(Context,
 * PendingIntent, Class)I` — smali `k.smali:195`, `.registers 15`
 * (locals `v0..v11`, `p0=v12` Context, `p1=v13` PendingIntent, `p2=v14` Class),
 * **`public static`**.
 *
 * This is Play's `DownloaderImpl.initiateDownload(...)`, the single entry point
 * that decides whether the expansion downloader service boots. It is called from
 * three places, all of which are on the critical path:
 *
 * | Caller | Line | What it does with the result |
 * |---|---|---|
 * | `UnityDownloaderActivity.onCreate` | `:918` | `if-eqz v1, :cond_4f` — **non-zero** calls `a()V`, the error dialog. Zero just `finish()`es. |
 * | `com.google.android.vending.expansion.downloader.impl.i` (DownloaderService, "LVLDL" loop) | `:372` | `v0 == 0` → `:cond_124` → `state 5` = **COMPLETED**; `v0 == 1` → `:cond_10a` → `"In LVL checking loop!"` → `a.a(0xf)` → **state 15 = failed_UNLICENSED** + `RuntimeException`. |
 * | `UnityAlarmReceiver` | `:34` | return value ignored. |
 *
 * Return codes, read straight off the smali body:
 * ```
 *   :195-239  v1 = (m.e /*stored expansion versionCode*/ == versionCode) ? 0 : 1
 *   :243-247  m.f /*DOWNLOADSTATUS*/ != 0            -> v1 = 2
 *   :249-299  any OBB file fails md5 verify          -> DOWNLOADSTATUS = -1, v1 = 2
 *   :311-315  if (v1 == 1 || v1 == 2) goto :cond_62  // startService(...)
 *             else                                   return v1   // v1 == 0
 * ```
 *
 * On a fresh sideloaded install `m.e` is 0 while `versionCode` is 1090034, so
 * `v1` is **1** on every call — which is precisely the state-15 UNLICENSED
 * failure observed on device.
 *
 * ⚠️ **The patch replaces the body with "always start the service, always return
 * 0"** — NOT with a bare `const/4 v0, 0x0; return v0`. The reason is the
 * `:311-315` branch above: `startService` sits on the *non-zero* path, so a
 * bare `return 0` would skip it entirely and the downloader service would never
 * boot on the first `UnityDownloaderActivity.onCreate` call. The service is what
 * verifies the OBB and drives the state machine to `COMPLETED`; killing it would
 * strand `UnityDownloaderActivity.GetState()` on `1 = idle` forever. The
 * replacement therefore reproduces the original's `:cond_62` block verbatim
 * (same `Intent`, same `"EPI"` extra, same `startService`) and then returns 0.
 *
 * The original body carries **no exception table and no labels**, so a
 * label-free straight-line replacement injected at index 0 is safe; the
 * unreachable remainder is not verified by ART.
 *
 * NOTE: `a(Context, PendingIntent, Class)I` is the only method in `k` with this
 * signature — `k;->a(Z)V`, `k;->a(J)V`, `k;->a(m;)I`, `k;->a(String)Ljava/lang/String;`
 * and the two `k;->a(k;)` synthetic accessors all differ in parameters.
 */
object ExpansionInitiateDownloadFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/vending/expansion/downloader/impl/k;",
    name = "a",
    returnType = "I",
    parameters = listOf(
        "Landroid/content/Context;",
        "Landroid/app/PendingIntent;",
        "Ljava/lang/Class;"
    ),
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC)
)

/**
 * `com.unity3d.plugin.downloader.d.f.a(Lcom/unity3d/plugin/downloader/d/h;)V` —
 * smali `f.smali:305`, `.registers 5` (locals `v0..v2`, `p0=v3` the listener
 * `f`, `p1=v4` the license-response `h`), **`private declared-synchronized`**,
 * and it carries a `.catchall`:
 * ```
 *   .catchall {:try_start_1 .. :try_end_20} :catchall_22
 *   :catchall_22  move-exception p1
 *   :try_start_23 monitor-exit p0
 *   :try_end_24   .catchall {:try_start_23 .. :try_end_24} :catchall_22
 *                 throw p1
 * ```
 *
 * This is the **Play-Licensing (LVL) response handler** — `f` is the
 * `LicenseChecker` service-connection. Its body is:
 * ```
 *   iget-object v0, p0, ...->d:Ld/b;
 *   const/16 v2, 0x123                       // EXPANSION_ERROR_UNLICENSED
 *   invoke-virtual {v0, v2, v1}, Ld/b;->a(ILd/l;)V     // persist retryCount
 *   invoke-virtual {v0}, Ld/b;->a()Z         // "expansion fully downloaded?"
 *   move-result v0
 *   if-eqz v0, :cond_19
 *     invoke-interface {p1, v2}, Ld/g;->b(I)V   // ALLOWED  -> Lvdl verification loop
 *   goto :goto_20
 *   :cond_19
 *     invoke-interface {p1, v2}, Ld/g;->a(I)V   // DENIED   -> hard dead end
 * ```
 *
 * `Ld/g;` is the callback interface implemented by the downloader service
 * `impl/i.smali`, and the two methods are **not** symmetric:
 *
 * | Dispatch | Implementation | Effect |
 * |---|---|---|
 * | `Ld/g;->a(I)V` (denied) | `i.smali:29` | `p1 == 0x123` → `state 0x10` = **16 = failed_fetching_url**, then `k.a(false)` → **stop the service**. Terminal. |
 * | `Ld/g;->b(I)V` (allowed) | `i.smali:88` | the `"LVLDL"` loop at `i.smali:110` — md5-verifies every OBB file, commits the download status, then re-enters [ExpansionInitiateDownloadFingerprint] at `i.smali:372`, which reaches `state 5` = COMPLETED. |
 *
 * A sideloaded install always takes the denied branch, so the patch forces the
 * allowed dispatch. Both patches interlock: B is what keeps the flow out of
 * `i.a(I)V`'s stop-the-service dead end, and A is what makes the `LVLDL` loop's
 * `i.smali:372` call land on `state 5` instead of `state 15`.
 *
 * The `Ld/b;->a(ILd/l;)V` call is **kept** even though its only effect
 * (`d:J` retryCount → prefs via `b.a(J)V` at `b.smali:156`) is not the gate —
 * dropping it would silently change how many times the service retries. The
 * `Ld/b;->a()Z` predicate is **dropped**: its result is discarded, and it was
 * verified side-effect-free (`b.smali:735` only reads `System.currentTimeMillis()`
 * and fields `a:J`/`b:J`/`c:J`/`e:J`/`f:I`, and returns `v4=1` or `v5=0`).
 *
 * ⚠️ **`declared-synchronized` + exception table.** baksmali expands
 * `ACC_DECLARED_SYNCHRONIZED` into explicit `monitor-enter p0` / `monitor-exit p0`
 * inside the body, so the replacement must keep the monitor balanced. The patch
 * therefore injects **after** `monitor-enter p0` *and* after the `:try_start_1`
 * label — i.e. inside the protected region — and emits its own
 * `monitor-exit p0` before returning. That way:
 *  * on the normal path the lock is entered by the original `monitor-enter` and
 *    released by ours, exactly once;
 *  * if `Ld/g;->b(I)V` throws, the existing `.catchall` releases the lock
 *    (`:try_start_23 monitor-exit p0`) and rethrows.
 *
 * Injecting *before* `:try_start_1` would put the new code outside the
 * protected region and leak the monitor on any throw — see the note on the
 * insertion index in `ExpansionLicenseBypassPatch`.
 */
object LicenseResponseAllowFingerprint : Fingerprint(
    definingClass = "Lcom/unity3d/plugin/downloader/d/f;",
    name = "a",
    returnType = "V",
    parameters = listOf("Lcom/unity3d/plugin/downloader/d/h;"),
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.DECLARED_SYNCHRONIZED)
)