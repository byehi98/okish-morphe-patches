package app.lemmings.patches.shared

import app.morphe.patcher.patch.PatchException
import java.io.File
import java.io.RandomAccessFile

/**
 * Tiny in-place native byte editor shared by the Lemmings patches.
 *
 * Every call is anchored: the bytes on disk must match [anchorHex] before
 * anything is written, so a drifted `libil2cpp.so` fails loudly instead of
 * being blindly corrupted. Anchors may be LONGER than the write (e.g. a 12-byte
 * multi-word guard for a 4-byte edit); the write always starts at [offset].
 */
internal object NativeBytePatcher {
    /** Parses a plain hex string (no separators) into bytes. */
    fun hexBytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** "XX XX XX XX" formatter for mismatch messages. */
    fun toHex(bytes: ByteArray): String =
        bytes.joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase() }

    /**
     * Read-only guard: throws [PatchException] unless the bytes at [offset]
     * match [expectedHex]. [abortHint] is appended to the message.
     */
    fun verify(
        lib: File,
        offset: Long,
        expectedHex: String,
        label: String,
        tag: String,
        abortHint: String = "aborted, lib left unmodified.",
    ) {
        val expected = hexBytes(expectedHex)
        val actual = readAt(lib, offset, expected.size, label, tag)
        if (!actual.contentEquals(expected)) {
            throw PatchException(
                "$tag: $label mismatch at file 0x${offset.toString(16)} " +
                    "(found ${toHex(actual)}, expected $expectedHex) — $abortHint",
            )
        }
        println("$tag: $label verified at file 0x${offset.toString(16)} (read-only guard).")
    }

    /**
     * Verifies the anchor at [offset] and writes [writeHex] there. Returns
     * `false` (with a log line) when the anchor does not match; never writes.
     */
    fun apply(
        lib: File,
        offset: Long,
        anchorHex: String,
        writeHex: String,
        label: String,
        tag: String,
    ): Boolean {
        val anchor = hexBytes(anchorHex)
        RandomAccessFile(lib, "rw").use { raf ->
            if (raf.length() < offset + anchor.size) {
                println("$tag: $label short lib — file 0x${offset.toString(16)} out of range.")
                return false
            }
            raf.seek(offset)
            val actual = ByteArray(anchor.size)
            raf.readFully(actual)
            if (!actual.contentEquals(anchor)) {
                println(
                    "$tag: $label anchor mismatch at file 0x${offset.toString(16)} " +
                        "(found ${toHex(actual)}).",
                )
                return false
            }
            raf.seek(offset)
            raf.write(hexBytes(writeHex))
        }
        println(
            "$tag: $label file 0x${offset.toString(16)}: " +
                "${anchorHex.take(8)} -> $writeHex " +
                "(${writeHex.chunked(8).joinToString(" ") { decodeHint(it) }})",
        )
        return true
    }

    /** Human hint for the byte patterns this project uses; falls back to hex. */
    private fun decodeHint(wordHex: String): String = when (wordHex.uppercase()) {
        "20008052" -> "mov w0,#1"
        "C0035FD6" -> "ret"
        "0100A0E3" -> "mov r0,#1"
        "1EFF2FE1" -> "bx lr"
        else -> wordHex
    }

    private fun readAt(lib: File, offset: Long, length: Int, label: String, tag: String): ByteArray {
        RandomAccessFile(lib, "r").use { raf ->
            if (raf.length() < offset + length) {
                throw PatchException(
                    "$tag: $label short lib — file 0x${offset.toString(16)} out of range.",
                )
            }
            raf.seek(offset)
            return ByteArray(length).also { raf.readFully(it) }
        }
    }
}
