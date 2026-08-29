package com.app.finance.data.export

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.random.Random

/**
 * The backup container — FR-DAT-11 and FR-DAT-12.
 *
 * The point of most of these is not that a good file round-trips. It is that a
 * **bad** one is refused, loudly and in full, because the alternative is a
 * restore that silently applies half a ledger. `NFR-REL-04` already makes the
 * import transactional; this is the layer below it, where a stream that decodes
 * partway and then stops would hand `Importer` a truncated document it has no
 * way to know was truncated.
 */
class BackupCodecTest {

    private val pass = "cholish taka".toCharArray()

    // --- round trips ---------------------------------------------------------

    @Test
    fun `a plain backup round-trips`() {
        val payload = JSON.toByteArray()
        assertArrayEquals(payload, decode(encode(payload, null), null))
    }

    @Test
    fun `an encrypted backup round-trips`() {
        val payload = JSON.toByteArray()
        assertArrayEquals(payload, decode(encode(payload, pass), pass))
    }

    @Test
    fun `a payload spanning many frames round-trips`() {
        // Deliberately incompressible, so gzip cannot fold it back under one
        // frame and the multi-frame path is the one actually exercised.
        val payload = Random(7).nextBytes(5 * BackupCodec.FRAME_BYTES)
        assertArrayEquals(payload, decode(encode(payload, pass), pass))
        assertTrue(frameCount(encode(payload, pass)) > 4)
    }

    @Test
    fun `an empty payload still produces a readable backup`() {
        // The final frame is sealed even with nothing in it, which is what makes
        // "this file is complete" provable rather than assumed.
        assertArrayEquals(ByteArray(0), decode(encode(ByteArray(0), pass), pass))
    }

    @Test
    fun `compression is real`() {
        val payload = JSON.repeat(400).toByteArray()
        assertTrue(encode(payload, null).size < payload.size / 4)
    }

    // --- FR-DAT-12: files written before this class existed -------------------

    @Test
    fun `a plain export from an earlier release passes straight through`() {
        // The whole of FR-DAT-12. Every daybook-export.json already on somebody's
        // phone has no magic number, and refusing it would mean the app stopped
        // reading its own files.
        val payload = JSON.toByteArray()
        assertArrayEquals(payload, BackupCodec.decode(ByteArray(0).stream(payload)).readBytes())
    }

    @Test
    fun `a passphrase is ignored on a file that does not want one`() {
        val payload = JSON.toByteArray()
        assertArrayEquals(payload, decode(encode(payload, null), pass))
    }

    @Test
    fun `a short file that is not ours is passed through rather than refused`() {
        val payload = "{}".toByteArray()
        assertArrayEquals(payload, BackupCodec.decode(payload.asStream()).readBytes())
    }

    // --- needsPassphrase ------------------------------------------------------

    @Test
    fun `needsPassphrase distinguishes the three kinds of file`() {
        assertTrue(BackupCodec.needsPassphrase(encode(JSON.toByteArray(), pass).asStream()))
        assertFalse(BackupCodec.needsPassphrase(encode(JSON.toByteArray(), null).asStream()))
        assertFalse(BackupCodec.needsPassphrase(JSON.toByteArray().asStream()))
    }

    // --- the passphrase -------------------------------------------------------

    @Test
    fun `a wrong passphrase is named as such`() {
        // Told apart from a damaged file on purpose. AEAD reports both as one
        // bad tag, and these two need very different sentences.
        val file = encode(JSON.toByteArray(), pass)
        assertThrows(BackupCodec.WrongPassphrase::class.java) {
            decode(file, "cholish taka ".toCharArray())
        }
    }

    @Test
    fun `an encrypted file with no passphrase asks for one`() {
        val file = encode(JSON.toByteArray(), pass)
        assertThrows(BackupCodec.NeedsPassphrase::class.java) { decode(file, null) }
        assertThrows(BackupCodec.NeedsPassphrase::class.java) { decode(file, CharArray(0)) }
    }

    @Test
    fun `the iteration count is read from the file, not assumed`() {
        // FR-DAT-12 in the other direction: raising ITERATIONS later must not
        // orphan the backups taken before it. Patching the stored count changes
        // the key that gets derived, which the verifier then rejects — proof the
        // header value is what the derivation actually uses.
        val file = encode(JSON.toByteArray(), pass)
        val patched = file.copyOf().also { it[ITERATIONS_AT + 3] = (it[ITERATIONS_AT + 3] + 1).toByte() }
        assertNotEquals(file[ITERATIONS_AT + 3], patched[ITERATIONS_AT + 3])
        assertThrows(BackupCodec.WrongPassphrase::class.java) { decode(patched, pass) }
    }

    @Test
    fun `a key kept from an earlier derivation opens the same as the passphrase`() {
        // FR-DAT-08 runs with nobody there to type anything, so a backup is
        // sealed with a key derived once and stored. What matters is that the
        // passphrase still opens the result on a phone that never held the key —
        // otherwise a stored key would be a backup only this device can read,
        // which is the opposite of the point.
        val derived = BackupCodec.secretFrom(pass)
        val kept = BackupCodec.secretFrom(derived.keyBytes, derived.saltBytes, derived.rounds)

        val out = ByteArrayOutputStream()
        BackupCodec.encode(out, kept).use { it.write(JSON.toByteArray()) }

        assertArrayEquals(JSON.toByteArray(), decode(out.toByteArray(), pass))
    }

    @Test
    fun `two backups under one key are different ciphertexts`() {
        // The key is reused across backups by design; the per-file nonce is what
        // keeps GCM safe about it. Identical files would mean a repeated IV.
        val secret = BackupCodec.secretFrom(pass)
        val first = ByteArrayOutputStream().also { o -> BackupCodec.encode(o, secret).use { it.write(JSON.toByteArray()) } }
        val second = ByteArrayOutputStream().also { o -> BackupCodec.encode(o, secret).use { it.write(JSON.toByteArray()) } }

        assertFalse(first.toByteArray().contentEquals(second.toByteArray()))
        assertArrayEquals(JSON.toByteArray(), decode(second.toByteArray(), pass))
    }

    // --- tampering ------------------------------------------------------------

    @Test
    fun `one flipped byte of ciphertext is refused`() {
        val file = encode(JSON.toByteArray(), pass)
        val frames = frameOffsets(file)
        val target = frames.first() + FRAME_HEADER + 2
        val tampered = file.copyOf().also { it[target] = (it[target].toInt() xor 0x01).toByte() }

        assertThrows(BackupCodec.CorruptBackup::class.java) { decode(tampered, pass) }
    }

    @Test
    fun `flipping the last-block marker is refused`() {
        // The marker is not merely read, it is authenticated: it goes into the
        // AAD, so an attacker cannot make a short file claim to be a whole one.
        val file = encode(Random(3).nextBytes(3 * BackupCodec.FRAME_BYTES), pass)
        val first = frameOffsets(file).first()
        val tampered = file.copyOf().also { it[first] = 1 }

        assertThrows(BackupCodec.CorruptBackup::class.java) { decode(tampered, pass) }
    }

    @Test
    fun `a backup truncated at a block boundary is refused`() {
        // The one that matters. Cutting here leaves a perfectly well-formed
        // prefix — every block intact, every tag valid — and without the
        // last-block marker it would decode as a complete, shorter ledger.
        val payload = Random(11).nextBytes(4 * BackupCodec.FRAME_BYTES)
        val file = encode(payload, pass)
        val boundaries = frameOffsets(file)
        assertTrue("needs several blocks to cut between", boundaries.size > 2)

        val cut = file.copyOf(boundaries[1])
        assertThrows(BackupCodec.CorruptBackup::class.java) { decode(cut, pass) }
    }

    @Test
    fun `a backup truncated mid-block is refused`() {
        val file = encode(Random(13).nextBytes(3 * BackupCodec.FRAME_BYTES), pass)
        assertThrows(BackupCodec.CorruptBackup::class.java) { decode(file.copyOf(file.size - 40), pass) }
    }

    @Test
    fun `a truncated header is refused`() {
        val file = encode(JSON.toByteArray(), pass)
        assertThrows(BackupCodec.CorruptBackup::class.java) { decode(file.copyOf(20), pass) }
    }

    @Test
    fun `an unknown mode is reported as a newer format, not as damage`() {
        // The magic matched, so this file *is* ours -- it was simply written by
        // a release that knows a container this one does not. FR-DAT-05 already
        // says "update, then import again" for a newer schema, and telling
        // somebody their perfectly good backup is broken would be both wrong and
        // unactionable.
        val file = encode(JSON.toByteArray(), null)
        val newer = file.copyOf().also { it[BackupCodec.MAGIC.size] = 9 }
        assertThrows(BackupCodec.NewerFormat::class.java) { decode(newer, null) }
    }

    @Test
    fun `a file cut off after the magic is damage, not a stranger`() {
        // Past the magic the file is ours, and saying "this isn't a DayBook
        // backup" would send somebody holding a truncated one off to find a
        // different file. Both branches below are past that point.
        val file = encode(JSON.toByteArray(), pass)
        assertThrows(BackupCodec.CorruptBackup::class.java) {
            decode(file.copyOf(BackupCodec.MAGIC.size), pass)
        }
        assertThrows(BackupCodec.CorruptBackup::class.java) {
            decode(file.copyOf(BackupCodec.MAGIC.size + 1), pass)
        }
    }

    @Test
    fun `the sink is closed even when sealing the last block fails`() {
        // A card pulled during the *final* write is exactly when `seal` throws,
        // and it is the worst moment to also leak the handle: StrictMode's
        // detectLeakedClosableObjects fires, and some providers keep the
        // document locked until the process dies.
        //
        // The failure is armed after `encode` has written its header and after
        // the payload has been buffered, so the only thing left to fail is the
        // last block -- which is written by `close` itself. A throw during an
        // ordinary `write` leaves the whole chain open and is the caller's to
        // handle; `BackupRepository` wraps the sink in `use` for that.
        var closed = false
        var armed = false
        val sink = object : ByteArrayOutputStream() {
            override fun write(b: Int) {
                if (armed) throw IOException("the card was pulled")
                super.write(b)
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                if (armed) throw IOException("the card was pulled")
                super.write(b, off, len)
            }

            override fun close() {
                closed = true
                super.close()
            }
        }

        val out = BackupCodec.encode(sink, BackupCodec.secretFrom(pass))
        out.write(JSON.toByteArray())
        armed = true

        assertThrows(IOException::class.java) { out.close() }
        assertTrue("the sink was left open after a failed close", closed)
    }

    // --- names ----------------------------------------------------------------

    @Test
    fun `backup names sort oldest first`() {
        val names = listOf("2026-08-22-2114", "2026-01-04-0900", "2026-08-09-2114").map(BackupCodec::fileName)
        assertEquals(
            listOf("2026-01-04-0900", "2026-08-09-2114", "2026-08-22-2114").map(BackupCodec::fileName),
            names.sorted(),
        )
    }

    @Test
    fun `only the app's own backups are recognised by name`() {
        assertTrue(BackupCodec.isBackupName(BackupCodec.fileName("2026-08-22-2114")))
        assertFalse(BackupCodec.isBackupName("daybook-export.json"))
        assertFalse(BackupCodec.isBackupName("holiday.jpg"))
        // Still being written, so not yet a backup anything may restore from.
        assertFalse(BackupCodec.isBackupName(BackupCodec.fileName("2026-08-22-2114") + BackupCodec.PARTIAL))
    }

    // --- helpers --------------------------------------------------------------

    private fun encode(payload: ByteArray, passphrase: CharArray?): ByteArray {
        val out = ByteArrayOutputStream()
        val secret = passphrase?.takeIf { it.isNotEmpty() }?.let(BackupCodec::secretFrom)
        BackupCodec.encode(out, secret).use { it.write(payload) }
        return out.toByteArray()
    }

    private fun decode(file: ByteArray, passphrase: CharArray?): ByteArray =
        BackupCodec.decode(file.asStream(), passphrase).use { it.readBytes() }

    private fun ByteArray.asStream() = ByteArrayInputStream(this)

    private fun ByteArray.stream(rest: ByteArray) = ByteArrayInputStream(this + rest)

    /** Where each frame's `flag` byte sits, walking the file as the codec does. */
    private fun frameOffsets(file: ByteArray): List<Int> {
        val offsets = mutableListOf<Int>()
        var at = ENCRYPTED_HEADER
        while (at < file.size) {
            offsets += at
            val length = ((file[at + 1].toInt() and 0xFF) shl 24) or
                ((file[at + 2].toInt() and 0xFF) shl 16) or
                ((file[at + 3].toInt() and 0xFF) shl 8) or
                (file[at + 4].toInt() and 0xFF)
            at += FRAME_HEADER + length
        }
        return offsets
    }

    private fun frameCount(file: ByteArray) = frameOffsets(file).size

    private companion object {
        val JSON = """{"schema_version":1,"exported_at":1755000000000,"expenses":[]}"""

        /** magic + mode. */
        val ITERATIONS_AT = BackupCodec.MAGIC.size + 1 + BackupCodec.SALT_BYTES

        /** magic + mode + salt + iterations + nonce + verifier. */
        val ENCRYPTED_HEADER = ITERATIONS_AT + 4 + BackupCodec.NONCE_BYTES + BackupCodec.VERIFIER_BYTES

        /** flag + length. */
        const val FRAME_HEADER = 5
    }
}
