package app.appusage.patches.signature

import app.appusage.patches.shared.Constants.COMPATIBILITY_APP_USAGE
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch

/**
 * Restore the original signing certificate (App Usage 6.20).
 *
 * The APK was signed by the upstream developer (CN=Sam Lu, OU=A0Soft). Two
 * integers derived from that certificate are hardcoded in the DEX and checked in
 * ~21 places, so any re-signed build looks tampered. The harshest consequence is
 * an anti-tamper kill switch: the window lifecycle schedules an `Lhx` coroutine
 * that sleeps 1933 ms and calls `Ly31;->ఓ()V` (Process.killProcess +
 * System.exit(10)), which is why an otherwise working patched build dies a few
 * seconds after the first window transition.
 *
 * Rather than patching 21 comparison branches we make the accessors return the
 * real certificate, recovered from the APK Signing Block of the unmodified
 * bundle (the APK ships no v1 signature files, only a v2/v3 signing block):
 *
 *   DER length 774 bytes
 *   Arrays.hashCode(DER) = -1105923880        (matches Signature.hashCode())
 *   SHA-1                = 190D8057CD872B2F7EF67A2A7DEA5117E0CC8945
 *   String.hashCode(SHA-1) = 559284713        (matches the Lyf2/Lj11 sites)
 *
 * Implementation: the DER is embedded as base64 and decoded at runtime, which is
 * two instructions plus a constructor instead of ~774 aput-byte instructions.
 * android.util.Base64 is already used by the app.
 *
 * Target B also writes the decoded array into the `j11.齶` cache field, which is
 * exactly what the original method does on its first call. That keeps
 * `Le11;->鱮()Z` (6.19 `p31;->糴()Z`, "cert already loaded") answering `true`
 * like an unpatched install instead of `false` forever.
 */
@Suppress("unused")
val appUsageSignaturePatch = bytecodePatch(
    name = "Spoof original signature",
    description = "Makes the app see the original developer certificate instead of the one used " +
        "to re-sign the APK. Stops the ~2 second anti-tamper process kill (Process.killProcess + " +
        "System.exit) scheduled from the window lifecycle, prevents the PRO flag from being reset " +
        "to the free version, and restores the trusted paths used by widgets, export/backup and " +
        "history queries.",
) {
    compatibleWith(COMPATIBILITY_APP_USAGE)

    execute {
        // Target A — Signature object (direct PackageManager consumers, and the
        // source that `Lj11;->灒` converts to DER bytes).
        GetSignatureFingerprint.method.addInstructions(
            0,
            """
                const-string v0, "$ORIGINAL_CERT_BASE64"
                const/4 v1, 0x0
                invoke-static {v0, v1}, Landroid/util/Base64;->decode(Ljava/lang/String;I)[B
                move-result-object v0
                new-instance v1, Landroid/content/pm/Signature;
                invoke-direct {v1, v0}, Landroid/content/pm/Signature;-><init>([B)V
                return-object v1
            """,
        )

        // Target B — raw cert bytes (Le11.ڡ / Le11.鶱, i.e. all SHA-1 and widget
        // checks). Returns before the original monitor-enter, so the lock is
        // never taken; the cache field is filled first so `鱮()` matches the
        // unpatched first-call state.
        GetSignatureBytesFingerprint.method.addInstructions(
            0,
            """
                const-string v0, "$ORIGINAL_CERT_BASE64"
                const/4 v1, 0x0
                invoke-static {v0, v1}, Landroid/util/Base64;->decode(Ljava/lang/String;I)[B
                move-result-object v0
                iput-object v0, p0, Lj11;->齶:Ljava/lang/Object;
                return-object v0
            """,
        )
    }
}

// Original signing certificate DER (774 bytes), recovered from the v2 APK
// Signing Block of App Usage 6.20. Verified: Arrays.hashCode == -1105923880 and
// String.hashCode(SHA-1 hex) == 559284713, i.e. it satisfies both hardcoded
// constants.
private const val ORIGINAL_CERT_BASE64 =
    "MIIDAjCCAsCgAwIBAgIESKqV3zALBgcqhkjOOAQDBQAwYzELMAkGA1UEBhMCVFcxDzANBgNVBAgTBlRhaXdhbjEQMA4GA1UEBxMHSHNpbmNodTEPMA0GA1UEChMGQTBTb2Z0MQ8wDQYDVQQLEwZBMFNvZnQxDzANBgNVBAMTBlNhbSBMdTAgFw0wODA4MTkwOTQzNTlaGA8yMTA4MDcyNjA5NDM1OVowYzELMAkGA1UEBhMCVFcxDzANBgNVBAgTBlRhaXdhbjEQMA4GA1UEBxMHSHNpbmNodTEPMA0GA1UEChMGQTBTb2Z0MQ8wDQYDVQQLEwZBMFNvZnQxDzANBgNVBAMTBlNhbSBMdTCCAbgwggEsBgcqhkjOOAQBMIIBHwKBgQD9f1OBHXUSKVLfSpwu7OTn9hG3UjzvRADDHj+AtlEmaUVdQCJR+1k9jVj6v8X1ujD2y5tVbNeBO4AdNG/yZmC3a5lQpaSfn+gEexAiwk+7qdf+t8Yb+DtX58aophUPBPuD9tPFHsMCNVQTWhaRMvZ1864rYdcq7/IiAxmd0UgBxwIVAJdgUI8VIwvMspK5gqLrhAvwWBz1AoGBAPfhoIXWmz3ey7yrXDa4V7l5lK+7+jrqgvlXTAs9B4JnUVlXjrrUWU/mcQcQgYC0SRZxI+hMKBYTt88JMozIpuE8FnqLVHyNKOCjrh4rs6Z1kW6jfwv6ITVi8ftiegEkO8yk8b6oUZCJqIPf4VrlnwaSi2ZegHtVJWQBTDv+z0kqA4GFAAKBgQDUSh3jayGx9jPjQJEoPA82bBbMZqyK1I2jSKXb+SVX/Jd5LTP4LQRqxb9xK/XD2Aonv7MoaxfOaO3BusHWOPQjJmxlnbyYPIT4fqsyGmz0cznQDzQUCd7tPNY0QLtHtjG2RJaoOfMYJQb+BqO3DUfSf5CsZRIPoYDrL/K2R1z92zALBgcqhkjOOAQDBQADLwAwLAIUPqu67scdTJ9nHR1Az/Vb6RcG57QCFElTwaomYUdTg6zxHv+GC3866ODb"
