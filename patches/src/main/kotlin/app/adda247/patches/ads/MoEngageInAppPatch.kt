package app.adda247.patches.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.adda247.patches.shared.Constants.COMPATIBILITY_ADDA247

/**
 * Adda247 — MoEngage In-App Blocker
 *
 * This is the app's OWN advertising surface, and it is NOT Google Mobile Ads. It is a
 * completely separate pipeline from `Adda247AdsPatch` (which neutralizes the
 * google_mobile_ads Flutter plugin's MethodChannel dispatcher and has zero overlap with
 * anything touched here). The recurring "recommended for you" nudge that has no close
 * button is a MoEngage In-App 10.2.0 campaign — MoEngage is a marketer-console
 * engagement SDK, not a paid ad network, which is exactly why it survived the GMA block.
 *
 * MoEngage In-App has two sub-paths, and only one of them is reachable from DEX:
 *
 *  1. STANDARD / native in-app — MoEngage renders the campaign itself, as HTML, inside
 *     its own `com.moengage.inapp.internal.html.InAppWebView`. Fully DEX-resident, so
 *     patchable. This includes the NON_INTRUSIVE nudge (the user-visible complaint) and
 *     full-screen standard in-app. THIS is what the patch blocks.
 *  2. SELF-HANDLED in-app — MoEngage hands a JSON payload to the Flutter plugin, which
 *     forwards it over a MethodChannel to Dart, and `libapp.so` builds the UI. Not
 *     reachable from bytecode at all (see "Scope" below).
 *
 * TARGET — the render+attach chokepoint (index 0, `return-void`):
 *
 *   InApp_10.2.0_HtmlViewEngine.Q(Ljava/lang/String;Landroid/view/ViewGroup;)V
 *   (R8: `Lkv/i0;->Q`; jadx: `p311kv.AbstractC22653i0.m90723Q`;
 *    confirmed classes5/kv/i0.smali:975)
 *
 * `Q` is the single sink for ALL standard MoEngage HTML in-app messages. Its whole body
 * is "build the WebView, load the campaign HTML, attach it to the container":
 *
 *   new InAppWebView(activity)                                  // kv/i0.smali:1021/1037
 *   webView.setWebViewClient(new InAppWebViewClient(payload))    // kv/i0.smali:1173/1181
 *   webView.addJavascriptInterface(bridge, "moengageInternal")   // kv/i0.smali:1224
 *   webView.loadDataWithBaseURL(base, html, "text/html", ...)    // kv/i0.smali:1260
 *   containerLayout.addView(webView)                             // kv/i0.smali:1329
 *
 * Returning at index 0 means the WebView is never constructed, the campaign HTML is
 * never fetched into a view, the JS bridge is never installed and nothing is ever
 * attached to the window. Because the block is at the RENDER layer rather than at a
 * trigger/decision layer, it holds regardless of what the MoEngage backend pushes or
 * which trigger fires (app open, screen view, event) — so the "recurring" nature of the
 * nudge is defeated too, not merely delayed.
 *
 * BOTH concrete engines funnel through it, so one no-op covers both formats:
 *   - `Lkv/a0;` — NON_INTRUSIVE nudge (the "recommended for you" banner). Its `E()`
 *     builds a `RelativeLayout`, then calls `Lkv/i0;->F(ViewGroup, String)`.
 *   - `Lkv/r;`  — full-screen standard in-app. Same `F(...)` call.
 *   `F(...)` posts a `Lkv/h0;` runnable to the UI thread, which calls the synthetic
 *   `z` → `G` → `Q`. Verified: `Lkv/i0;->Q(` has exactly ONE call site in the entire
 *   APK (kv/i0.smali:252, inside `G`), so there is no alternate route to the WebView.
 *
 * VERIFIER SAFETY of the index-0 `return-void` (all points read from smali):
 *  - Return type is `V` (`.method public final Q(Ljava/lang/String;Landroid/view/ViewGroup;)V`),
 *    so `return-void` is the correct and only required instruction — there is no return
 *    value to fake and therefore no risk of handing the SDK a bogus object.
 *  - `.registers 17` with 3 parameter registers → v0..v13 are true locals (uninitialized
 *    at entry) and p0=v14, p1=v15, p2=v16. `return-void` reads NO register, so nothing
 *    has to be re-established: no local is touched, and p0/p1/p2 are left pristine.
 *    (Register widths cross-check: `iget-object p1, p0, ...` at kv/i0.smali:1237 is
 *    format 22c with 4-bit operands, which is only legal if p1 ≤ v15 — consistent with
 *    p0=v14/p1=v15, and with the `move-object/from16 p1, p2` at :1308 needing the
 *    16-bit form for p2=v16.)
 *  - There are NO `monitor-enter`/`monitor-exit` instructions anywhere in kv/i0.smali
 *    (grep-verified), so returning early cannot strand a lock.
 *  - The method IS wrapped in `.catchall {:try_start_0 .. :try_end_b3} :catchall_ac`
 *    and `:try_start_0` sits on the first instruction. That is still safe: dexlib2's
 *    `MutableMethodImplementation` binds try-block bounds to the `MethodLocation` of
 *    their instruction, not to a numeric index, so inserting at index 0 shifts the whole
 *    try region down by one and the injected `return-void` ends up OUTSIDE the try
 *    block — the handler stays intact and correctly scoped over the (now unreachable)
 *    original body.
 *  - Everything after the injected return becomes unreachable dead code. That is
 *    accepted by the verifier (the code was already type-correct and this patch does not
 *    alter any register type on any path), and it is the same shape already used
 *    elsewhere in this repo for index-0 no-ops.
 *
 * SDK CONTROL FLOW — no callback is starved by this:
 * `Q` is a pure view-construction routine. It returns `void`, its single caller `G`
 * immediately returns, and `F(...)` fire-and-forgets it onto the UI thread, so no caller
 * inspects a result or waits on it. The campaign's own bookkeeping lives OUTSIDE `Q`:
 * `Lkv/i0;->B()` (getView) still runs `H()` (asset download) and `E()` (container
 * build), and the window attach + impression/dismiss bookkeeping is driven by
 * `Liv/wd;` — all untouched, so MoEngage's state machine advances exactly as it does on
 * a successful show. That matters: the campaign is still marked as delivered, so the SDK
 * does not enter a retry storm re-attempting the same nudge. `Q` also already had a
 * catch-all failure path of its own (`:catchall_ac` → `Lfw/c;->J(Z)V`), which proves the
 * SDK is written to tolerate this method not completing.
 *
 * KNOWN RESIDUAL (cosmetic, by design of patching at the sink):
 * `E()` still creates the campaign's container view and `Liv/wd;` still attaches it, so
 * an EMPTY container remains in the view tree for the campaign's lifetime. For the nudge
 * this is harmless and invisible: `Lkv/a0;->E()` builds a bare `RelativeLayout` with no
 * background and no click listener (`i0(...)` only calls `setMargins`), and a
 * non-clickable ViewGroup does not consume touches, so `FrameLayout` dispatch continues
 * to the views underneath it. The one visible edge case is the FULL-SCREEN engine
 * `Lkv/r;`, which calls `Lkv/h;->m(...)` — that can blur/dim the decor view behind the
 * container when the SDK's config gate (`Lru/f0;->I0(Context)`) is on, leaving a dimmed
 * backdrop with no content that the user dismisses with Back (`Lkv/r;->g0` installs the
 * key listener). If that ever proves annoying in practice, the graceful follow-up is to
 * additionally force `Lkv/i0;->B()` to return null — a null view is ALREADY an
 * SDK-supported outcome (it is what happens when `H()` reports an asset-download
 * failure), so no container would be built at all.
 *
 * SCOPE — what this patch deliberately does NOT cover:
 * MoEngage SELF-HANDLED campaigns still appear, by design. For those the native side
 * only delivers a JSON payload (`PluginSelfHandledInAppListener` →
 * `MoEngageFlutterPlugin` MethodChannel) and the view is constructed in Dart inside
 * `libapp.so`, which no bytecode patch can reach. Blocking the channel instead would
 * only stop payload DELIVERY (Dart may still render cached campaigns) while risking
 * side effects on unrelated plugin traffic, so it is intentionally left alone. The
 * user-reported nudge — a NON_INTRUSIVE campaign — is a STANDARD campaign rendered by
 * the SDK, i.e. exactly what `Q` renders, so it is covered here.
 */
@Suppress("unused")
val adda247MoEngageInAppPatch = bytecodePatch(
    name = "Adda247 MoEngage In-App Blocker",
    description = "Blocks the app's own MoEngage in-app promotions (the recurring \"recommended for you\" nudge with no close button, and full-screen standard in-app messages) by no-oping MoEngage's HTML render chokepoint, so no in-app WebView is ever built or attached. Separate from the Google AdMob block.",
    default = true
) {
    compatibleWith(COMPATIBILITY_ADDA247)

    execute {
        // Return before the InAppWebView is constructed, before the campaign HTML is
        // loaded and before the view is attached. `Q` returns void and no caller reads a
        // result, so a bare return-void at index 0 is the minimal, safest no-op: it
        // touches no register, sits outside the method's catch-all try block, and leaves
        // MoEngage's surrounding bookkeeping untouched.
        MoEngageHtmlViewEngineRenderFingerprint.method.addInstructionsWithLabels(0, """
            return-void
        """.trimIndent())
    }
}
