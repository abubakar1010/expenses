package com.app.finance.data.export

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.SequenceInputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The backup container — FR-DAT-11 and FR-DAT-12.
 *
 * **Not a new file format.** What travels inside is the same `DayBookExport` JSON
 * [Exporter] already writes and [Importer] already reads, byte for byte. This
 * wraps it and nothing more, which is why adding it leaves every one of
 * `ImportValidationTest`'s cases untouched: [decode] hands back a stream of
 * plain JSON whatever came in.
 *
 * Three things it adds, in order of why:
 *
 * 1. **Compression.** Five years is a few megabytes of JSON and FR-DAT-09 keeps
 *    several generations of it. Gzip takes that to a few hundred kilobytes, and
 *    the user is storing them on a phone.
 * 2. **A magic number.** A folder full of backups needs the app to recognise its
 *    own files without opening each one, and the user needs a wrong file refused
 *    by name rather than by a JSON parse error.
 * 3. **Optional encryption.** A backup lands somewhere the app does not control —
 *    a shared folder, a cloud client's sync directory, a chat app. NFR-SEC-06.
 *
 * ### NFR-SEC-06 is not NFR-SEC-05
 *
 * NFR-SEC-05 put database encryption at rest out of scope, and the reason it
 * gives is specific: it "requires bundling a **native** crypto library at
 * material size and startup cost". That is SQLCipher, and NFR-COMP-02 restates
 * the refusal. None of it reaches here. `AES/GCM/NoPadding` and
 * `PBKDF2WithHmacSHA256` are in the platform at API 26, this file adds no
 * dependency, and nothing in it runs unless the user asks for a backup.
 *
 * ### The layout
 *
 * ```
 * off  size  field
 * 0    9     magic, DAYBOOK1 and a newline
 * 9    1     mode: 0 = gzip, 1 = gzip then AES-256-GCM
 * --- mode 1 only ---
 * 10   16    salt
 * 26   4     PBKDF2 iterations, big-endian
 * 30   12    nonce (the per-frame IV is this, with the counter mixed in)
 * 42   4     verifier, see below
 * 46   ...   frames
 * --- mode 0 ---
 * 10   ...   gzip stream to end of file
 * ```
 *
 * A frame is `flag(1)`, `length(4, big-endian)`, `ciphertext(length)`, the
 * ciphertext carrying its own 16-byte GCM tag, over at most [FRAME_BYTES] of the
 * gzip stream. `flag` is 1 on the last frame and 0 on every other, and both it
 * and the frame's index go into the AAD.
 *
 * ### Why frames rather than a CipherStream
 *
 * `CipherInputStream` **must not** be used for GCM here. Several Android
 * versions have it swallow `AEADBadTagException` at end of stream and return -1
 * instead of throwing, so a tampered backup decodes partially and quietly — the
 * one failure mode a restore path cannot have. Every frame goes through
 * `Cipher.doFinal`, which throws.
 *
 * Framing buys two more things a single stream could not:
 *
 * - **Truncation is detected.** A file that stops before a frame with `flag = 1`
 *   ended early, and says so. Without the flag a truncated file is
 *   indistinguishable from a complete one that happened to end there.
 * - **Frames cannot be reordered, dropped or replayed**, because each one's
 *   index is authenticated by its own tag.
 *
 * The last frame is sealed even when it is empty, which is what makes the end of
 * a complete file provable rather than assumed.
 *
 * ### The verifier
 *
 * Four bytes of `SHA-256(key, salt)`. It exists so a wrong passphrase can be
 * told apart from a damaged file, because AEAD by construction reports both as
 * the same bad tag — and "that passphrase doesn't open this backup" is a very
 * different thing to tell someone than "this backup is broken".
 *
 * It gives an offline attacker nothing: testing a guess against it costs exactly
 * what testing the same guess against the first frame's tag costs, and they hold
 * the file either way. The tag remains the only authority on whether the content
 * is genuine — the verifier decides only which sentence the user reads.
 */
object BackupCodec {

    /** Nine bytes, ASCII: `DAYBOOK1` and a newline, so `head` on the file is tidy. */
    val MAGIC = byteArrayOf(0x44, 0x41, 0x59, 0x42, 0x4F, 0x4F, 0x4B, 0x31, 0x0A)

    const val MODE_PLAIN = 0
    const val MODE_ENCRYPTED = 1

    const val SALT_BYTES = 16
    const val NONCE_BYTES = 12
    const val VERIFIER_BYTES = 4
    const val TAG_BITS = 128
    const val TAG_BYTES = TAG_BITS / 8
    const val KEY_BITS = 256

    /**
     * OWASP's 2023 floor for PBKDF2-HMAC-SHA256. Written into the header rather
     * than assumed, so raising it later still reads every file taken before —
     * FR-DAT-12 makes that a requirement, not a courtesy.
     */
    const val ITERATIONS = 210_000

    /**
     * 64 KB of gzip output per frame. Small enough that a frame plus its tag is
     * nothing against NFR-PERF-08's 80 MB, large enough that the 21 bytes of
     * per-frame overhead round to nothing.
     */
    const val FRAME_BYTES = 64 * 1024

    /** The extension the app writes and looks for. */
    const val EXTENSION = "daybook"

    /**
     * What a backup is created as.
     *
     * Deliberately not a made-up `application/x-daybook`. A document provider
     * decides a new file's extension from its MIME type, and one it has never
     * heard of is where providers start improvising -- octet-stream is the type
     * every one of them leaves alone.
     */
    const val MIME = "application/octet-stream"

    /** What every automatic backup's name begins with. Drives rotation. */
    const val NAME_PREFIX = "daybook-backup-"

    /**
     * The suffix a file carries while it is still being written.
     *
     * A backup is written under this and renamed only after a clean close, so a
     * process killed mid-write leaves something the app will not mistake for a
     * usable backup — and leaves the generation before it intact.
     */
    const val PARTIAL = ".part"

    /** A ceiling on the header's iteration count, so a corrupt one cannot hang. */
    private const val MAX_ITERATIONS = 10_000_000

    /**
     * The magic matched but the mode did not — a backup from a later release.
     *
     * Distinct from [CorruptBackup] because it is not damaged, and the user can
     * do something about it. FR-DAT-05 already extends exactly this courtesy to
     * a newer *schema*; a newer container deserves the same sentence rather than
     * being called broken.
     */
    class NewerFormat(message: String) : IOException(message)

    /** The file is encrypted and no passphrase was supplied. */
    class NeedsPassphrase : IOException("this backup is protected by a passphrase")

    /** The passphrase does not derive the key this file was sealed with. */
    class WrongPassphrase : IOException("that passphrase does not open this backup")

    /** Altered, truncated, or damaged in transit. Never partially applied. */
    class CorruptBackup(message: String) : IOException(message)

    /**
     * A derived key, with the parameters that produced it.
     *
     * ### Why the key is kept rather than the passphrase re-derived
     *
     * FR-DAT-08 runs the backup on launch, with nobody there to type anything.
     * Deriving on the spot is not an option either: 210,000 rounds of
     * HMAC-SHA256 is a second or two on the Cortex-A53 the targets are set
     * against, and NFR-PERF-01 budgets the whole cold start at 800 ms. So the
     * passphrase is turned into a key once, when the user sets it, and the key
     * is stored beside the ledger.
     *
     * **Storing it there costs nothing that is not already spent.** An attacker
     * who can read the app's private storage can read `daybook.db`, which
     * NFR-SEC-05 deliberately leaves unencrypted on the reasoning that "the
     * device lock screen already gates access". Wrapping this key would defend a
     * door that is standing open beside it.
     *
     * What backup encryption is actually for is the file *after* it leaves —
     * sitting in a shared folder, synced to somebody's cloud, forwarded through
     * a chat app. Against that, a key that never leaves the phone is exactly the
     * protection asked for.
     *
     * [salt] and [iterations] are carried in every file, so the passphrase alone
     * rebuilds this key on a phone that has never seen it. That is what makes a
     * restore after a lost device possible at all.
     */
    class Secret internal constructor(
        internal val key: SecretKey,
        internal val salt: ByteArray,
        internal val iterations: Int,
    ) {
        /** The raw key, for storing. 32 bytes. */
        val keyBytes: ByteArray get() = key.encoded

        val saltBytes: ByteArray get() = salt.copyOf()

        val rounds: Int get() = iterations
    }

    /**
     * Derives a key from [passphrase] under a fresh salt.
     *
     * Slow on purpose, and the only slow call in this file. Runs when the user
     * sets a passphrase and never again.
     */
    fun secretFrom(passphrase: CharArray): Secret {
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        return Secret(deriveKey(passphrase, salt, ITERATIONS), salt, ITERATIONS)
    }

    /** Rebuilds a key already derived, so the KDF is paid once and not per backup. */
    fun secretFrom(keyBytes: ByteArray, salt: ByteArray, iterations: Int): Secret =
        Secret(SecretKeySpec(keyBytes, "AES"), salt, iterations)

    /**
     * Wraps [sink] so that everything written to the result lands in [sink] as a
     * backup. Closing the returned stream finishes the file and closes [sink];
     * `Exporter.writeJson` closes what it is given, so the chain completes on its
     * own.
     *
     * A null [secret] writes mode 0. Encryption is opt-in by requirement
     * (FR-DAT-11) and the default here says so.
     */
    fun encode(sink: OutputStream, secret: Secret? = null): OutputStream {
        sink.write(MAGIC)
        if (secret == null) {
            sink.write(MODE_PLAIN)
            return GZIPOutputStream(sink, FRAME_BYTES)
        }

        // Fresh per file, never derived from anything the file already carries.
        // The key is reused across backups by design; the nonce is what keeps
        // every one of them a different ciphertext.
        val nonce = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes)

        sink.write(MODE_ENCRYPTED)
        sink.write(secret.salt)
        sink.writeIntBE(secret.iterations)
        sink.write(nonce)
        sink.write(verifier(secret.key, secret.salt))

        return GZIPOutputStream(FramedGcmOutputStream(sink, secret.key, nonce), FRAME_BYTES)
    }

    /**
     * Unwraps [source] into a stream of plain `DayBookExport` JSON, which is what
     * `Importer` consumes unchanged.
     *
     * A file that does not start with [MAGIC] is passed through untouched rather
     * than refused. That is FR-DAT-12: `Exporter.writeJson` writes a plain
     * `daybook-export.json` with no magic at all, and `Importer` has to keep
     * reading it — the container wraps that format, it does not replace it.
     */
    fun decode(source: InputStream, passphrase: CharArray? = null): InputStream {
        val head = source.take(MAGIC.size)
        if (!head.contentEquals(MAGIC)) {
            // Not ours. Hand back the bytes already read, followed by the rest.
            return head.before(source)
        }

        // Past the magic, so anything wrong from here is a damaged backup of
        // ours rather than somebody else's file. `readByteOrThrow` said
        // "NotABackup" here, which would have told a user holding a truncated
        // DayBook file to go and find a different one.
        val mode = source.read()
        if (mode == -1) throw CorruptBackup("this backup ends before its mode byte")

        return when (mode) {
            MODE_PLAIN -> GZIPInputStream(source, FRAME_BYTES)

            MODE_ENCRYPTED -> {
                if (passphrase == null || passphrase.isEmpty()) throw NeedsPassphrase()
                val salt = source.readFullyOrThrow(SALT_BYTES, "salt")
                val iterations = source.readIntBEOrThrow("iteration count")
                if (iterations !in 1..MAX_ITERATIONS) {
                    throw CorruptBackup("implausible iteration count: " + iterations)
                }
                val nonce = source.readFullyOrThrow(NONCE_BYTES, "nonce")
                val stated = source.readFullyOrThrow(VERIFIER_BYTES, "check bytes")

                val key = deriveKey(passphrase, salt, iterations)
                if (!verifier(key, salt).contentEquals(stated)) throw WrongPassphrase()

                GZIPInputStream(FramedGcmInputStream(source, key, nonce), FRAME_BYTES)
            }

            else -> throw NewerFormat("this backup uses format " + mode + ", which this version cannot read")
        }
    }

    /**
     * Whether [source] will need a passphrase, read from the header alone.
     *
     * The screen asks this before offering the field, so a plain file is not met
     * with a demand for a secret it does not have. It consumes the header; the
     * caller holds a `() -> InputStream?` and opens the file again to restore it.
     */
    fun needsPassphrase(source: InputStream): Boolean {
        val head = source.take(MAGIC.size + 1)
        if (head.size <= MAGIC.size) return false
        if (!head.copyOf(MAGIC.size).contentEquals(MAGIC)) return false
        return head[MAGIC.size].toInt() == MODE_ENCRYPTED
    }

    /** The name a backup taken at [stamp] is written under. Sorts lexically. */
    fun fileName(stamp: String) = NAME_PREFIX + stamp + "." + EXTENSION

    /** True for a name [fileName] would have produced. */
    fun isBackupName(name: String) =
        name.startsWith(NAME_PREFIX) && name.endsWith("." + EXTENSION)

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKey {
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun verifier(key: SecretKey, salt: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256")
            .apply { update(key.encoded); update(salt) }
            .digest()
            .copyOf(VERIFIER_BYTES)

    /**
     * The IV for frame [index]: the nonce with the counter mixed into its low
     * four bytes.
     *
     * Derived rather than stored, so a frame cannot be replayed at another
     * position — and mixed rather than appended, because a counter occupying
     * bytes of its own would shorten the nonce and bring an IV repeat within
     * reach. GCM tolerates exactly one IV reuse before it stops protecting
     * anything at all.
     */
    private fun ivFor(nonce: ByteArray, index: Int): ByteArray {
        val iv = nonce.copyOf()
        val last = iv.size - 1
        iv[last] = (iv[last].toInt() xor (index and 0xFF)).toByte()
        iv[last - 1] = (iv[last - 1].toInt() xor ((index ushr 8) and 0xFF)).toByte()
        iv[last - 2] = (iv[last - 2].toInt() xor ((index ushr 16) and 0xFF)).toByte()
        iv[last - 3] = (iv[last - 3].toInt() xor ((index ushr 24) and 0xFF)).toByte()
        return iv
    }

    /** Frame index and the last-frame flag, both authenticated. */
    private fun aad(index: Int, last: Boolean) = byteArrayOf(
        (index ushr 24).toByte(), (index ushr 16).toByte(),
        (index ushr 8).toByte(), index.toByte(),
        if (last) 1 else 0,
    )

    private fun cipher(mode: Int, key: SecretKey, iv: ByteArray, index: Int, last: Boolean) =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(TAG_BITS, iv))
            updateAAD(aad(index, last))
        }

    // --- the framing streams -------------------------------------------------

    private class FramedGcmOutputStream(
        private val sink: OutputStream,
        private val key: SecretKey,
        private val nonce: ByteArray,
    ) : OutputStream() {

        private val pending = ByteArray(FRAME_BYTES)
        private var filled = 0
        private var index = 0
        private var closed = false

        override fun write(b: Int) {
            if (filled == pending.size) seal(last = false)
            pending[filled++] = b.toByte()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var start = off
            var left = len
            while (left > 0) {
                if (filled == pending.size) seal(last = false)
                val take = minOf(left, pending.size - filled)
                System.arraycopy(b, start, pending, filled, take)
                filled += take
                start += take
                left -= take
            }
        }

        /**
         * Deliberately does not seal.
         *
         * `GZIPOutputStream` flushes, and a flush that closed the frame would
         * produce a file of one-byte frames each carrying a 16-byte tag. The
         * only things that end a frame are filling it and closing the stream.
         */
        override fun flush() = Unit

        override fun close() {
            if (closed) return
            closed = true
            try {
                // Sealed even when empty: an authenticated final frame is what
                // makes the end of a complete file provable rather than assumed.
                seal(last = true)
                sink.flush()
            } finally {
                // The sink closes even when sealing threw — a card pulled during
                // the final write is exactly when this happens, and leaving the
                // document open would strand it. The file is incomplete either
                // way, and the caller deletes it; what must not also happen is a
                // leaked handle on top of a failed backup.
                sink.close()
            }
        }

        private fun seal(last: Boolean) {
            val sealed = cipher(Cipher.ENCRYPT_MODE, key, ivFor(nonce, index), index, last)
                .doFinal(pending, 0, filled)
            sink.write(if (last) 1 else 0)
            sink.writeIntBE(sealed.size)
            sink.write(sealed)
            index++
            filled = 0
        }
    }

    private class FramedGcmInputStream(
        private val source: InputStream,
        private val key: SecretKey,
        private val nonce: ByteArray,
    ) : InputStream() {

        private var plain = ByteArray(0)
        private var pos = 0
        private var index = 0
        private var finished = false

        override fun read(): Int {
            if (!ensure()) return -1
            return plain[pos++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!ensure()) return -1
            val take = minOf(len, plain.size - pos)
            System.arraycopy(plain, pos, b, off, take)
            pos += take
            return take
        }

        override fun available(): Int = plain.size - pos

        override fun close() = source.close()

        /** True while there is plaintext left to hand out. */
        private fun ensure(): Boolean {
            while (pos == plain.size) {
                if (finished) return false
                nextFrame()
            }
            return true
        }

        private fun nextFrame() {
            val flag = source.read()
            if (flag == -1) {
                // The stream ran out without ever presenting a final frame.
                // Without the flag this would look like an ordinary end of file.
                throw CorruptBackup("this backup ends before its last block")
            }
            if (flag != 0 && flag != 1) throw CorruptBackup("bad block marker: " + flag)

            val length = source.readIntBEOrThrow("block length")
            if (length < TAG_BYTES || length > FRAME_BYTES + TAG_BYTES) {
                throw CorruptBackup("bad block length: " + length)
            }
            val sealed = source.readFullyOrThrow(length, "block " + index)

            val last = flag == 1
            plain = try {
                cipher(Cipher.DECRYPT_MODE, key, ivFor(nonce, index), index, last).doFinal(sealed)
            } catch (e: AEADBadTagException) {
                // The key already matched the verifier, so this is the file
                // having changed since it was written — not the passphrase.
                throw CorruptBackup("this backup has been altered since it was written")
            }
            pos = 0
            index++
            finished = last
        }
    }

    // --- stream helpers ------------------------------------------------------

    private fun OutputStream.writeIntBE(value: Int) {
        write((value ushr 24) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 8) and 0xFF)
        write(value and 0xFF)
    }

    /** Reads up to [n] bytes, returning fewer only at end of stream. */
    private fun InputStream.take(n: Int): ByteArray {
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val got = read(buf, read, n - read)
            if (got == -1) break
            read += got
        }
        return if (read == n) buf else buf.copyOf(read)
    }

    private fun InputStream.readFullyOrThrow(n: Int, what: String): ByteArray {
        val buf = take(n)
        if (buf.size != n) throw CorruptBackup("this backup ends part-way through its " + what)
        return buf
    }

    private fun InputStream.readIntBEOrThrow(what: String): Int {
        val b = readFullyOrThrow(4, what)
        return ((b[0].toInt() and 0xFF) shl 24) or
            ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or
            (b[3].toInt() and 0xFF)
    }

    /** [this] followed by the remainder of [rest], as one stream. */
    private fun ByteArray.before(rest: InputStream): InputStream =
        SequenceInputStream(ByteArrayInputStream(this), rest)
}
