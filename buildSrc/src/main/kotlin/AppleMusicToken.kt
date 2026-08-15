import java.io.File
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * Mints an Apple Music **developer token** (ES256 JWT) from the MusicKit `.p8`
 * private key at build time.
 *
 * Why this exists (parachord-mobile#186): the token was previously a
 * pre-generated string hand-pasted into TWO files — Android `local.properties`
 * and `iosApp/Parachord/Secrets.xcconfig`. Rotating meant remembering both, and
 * `AppleMusicArtistProvider.isAvailable()` only checked `isNotBlank()`, never
 * `exp` — so an expired or forgotten token failed SILENTLY (Apple Music artist
 * images just stopped appearing). Minting from the long-lived `.p8` on every
 * build removes both failure modes: one source of truth, always fresh.
 *
 * This is the single implementation for BOTH platforms. Android consumes it
 * directly in `app/build.gradle.kts`; iOS calls the `printAppleMusicToken` task
 * from an Xcode build phase. Do not add a second signer — a parallel
 * implementation is exactly what drifts.
 *
 * Uses only JDK APIs (`java.security`), so it adds no dependency to any build.
 */
object AppleMusicToken {

    /** Apple caps developer tokens at 6 months; 180 days stays safely inside. */
    const val DEFAULT_EXPIRY_DAYS = 180L

    private const val SECONDS_PER_DAY = 24L * 60 * 60

    /**
     * `iat` floored to the start of the UTC day.
     *
     * Load-bearing for build performance: the token is embedded in Android's
     * `BuildConfig`, so a per-second `iat` would change that generated source on
     * EVERY build and force a full recompile every time. Flooring to the day
     * makes the token byte-identical for all builds on a given date — it still
     * regenerates daily, which is far fresher than the old hand-pasted value,
     * while keeping incremental builds warm. It also makes a build reproducible
     * within a day. Both consumers (Android BuildConfig and the
     * `printAppleMusicToken` task iOS calls) default to this, so a single
     * machine mints the SAME token for both platforms on a given day.
     */
    fun dayFlooredNow(nowMillis: Long = System.currentTimeMillis()): Long =
        (nowMillis / 1000 / SECONDS_PER_DAY) * SECONDS_PER_DAY

    /**
     * [generate], but reusing a cached token while it is still from today.
     *
     * **Required for incremental build performance.** ECDSA signing is
     * non-deterministic (RFC 6979 deterministic-k is not available in the plain
     * JDK), so signing identical claims twice produces DIFFERENT bytes. Without
     * a cache, every Gradle configuration would mint a new token, rewrite
     * Android's generated `BuildConfig`, and force a full recompile on EVERY
     * build — [dayFlooredNow] stabilizes the claims but cannot stabilize the
     * signature.
     *
     * The cache lives under `build/`, so `clean` discards it — which costs
     * nothing, since a clean build recompiles everything regardless. It is
     * shared by both consumers (Android's BuildConfig and the
     * `printAppleMusicToken` task the iOS phase calls), so a given day's builds
     * embed the SAME token on both platforms.
     */
    fun generateCached(
        cacheFile: File,
        p8Path: String,
        keyId: String,
        teamId: String,
        expiryDays: Long = DEFAULT_EXPIRY_DAYS,
        nowEpochSeconds: Long = dayFlooredNow(),
    ): String {
        val cached = runCatching { cacheFile.takeIf { it.isFile }?.readText()?.trim() }.getOrNull()
        if (!cached.isNullOrBlank() && issuedAt(cached) == nowEpochSeconds) return cached

        val token = generate(p8Path, keyId, teamId, expiryDays, nowEpochSeconds)
        runCatching {
            cacheFile.parentFile?.mkdirs()
            cacheFile.writeText(token)
        }
        return token
    }

    /** The `iat` claim of an existing token, or null if unreadable. */
    private fun issuedAt(token: String): Long? {
        val parts = token.split(".")
        if (parts.size != 3) return null
        return runCatching {
            val p = parts[1]
            val padded = p + "=".repeat((4 - p.length % 4) % 4)
            val json = String(Base64.getUrlDecoder().decode(padded), Charsets.UTF_8)
            Regex("\"iat\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toLong()
        }.getOrNull()
    }

    /**
     * @param p8Path   path to `AuthKey_<KEYID>.p8` (PKCS#8 PEM, never committed)
     * @param keyId    10-char MusicKit Key ID → JWT header `kid`
     * @param teamId   10-char Apple Team ID → JWT claim `iss`
     * @param nowEpochSeconds injectable for testing; defaults to [dayFlooredNow]
     */
    fun generate(
        p8Path: String,
        keyId: String,
        teamId: String,
        expiryDays: Long = DEFAULT_EXPIRY_DAYS,
        nowEpochSeconds: Long = dayFlooredNow(),
    ): String {
        val keyFile = File(p8Path)
        require(keyFile.isFile) { "Apple Music .p8 not found at: $p8Path" }
        require(keyId.isNotBlank()) { "APPLE_MUSIC_KEY_ID is blank" }
        require(teamId.isNotBlank()) { "APPLE_MUSIC_TEAM_ID is blank" }

        val privateKey = loadEcPrivateKey(keyFile)

        val header = """{"alg":"ES256","kid":"$keyId"}"""
        val exp = nowEpochSeconds + expiryDays * SECONDS_PER_DAY
        val payload = """{"iss":"$teamId","iat":$nowEpochSeconds,"exp":$exp}"""

        val signingInput = "${header.b64Url()}.${payload.b64Url()}"

        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(signingInput.toByteArray(Charsets.UTF_8))
            sign()
        }

        return "$signingInput.${derToJose(der).b64Url()}"
    }

    /** Reads a PKCS#8 PEM (`-----BEGIN PRIVATE KEY-----`) into an EC private key. */
    private fun loadEcPrivateKey(file: File): java.security.PrivateKey {
        val der = file.readText()
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .filterNot { it.isWhitespace() }
            .let { Base64.getDecoder().decode(it) }
        return KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(der))
    }

    /**
     * Convert a DER-encoded ECDSA signature to the JOSE fixed-width `R||S` form.
     *
     * **This conversion is required and easy to miss.** Java's
     * `SHA256withECDSA` emits ASN.1 DER (`SEQUENCE { INTEGER r, INTEGER s }`),
     * but JWS ES256 mandates the raw 64-byte concatenation of R and S, each
     * left-padded to 32 bytes. Emitting the DER bytes directly produces a JWT
     * that Apple rejects (and the failure is a silent 401 at request time, not
     * a build error). DER INTEGERs are signed, so a leading 0x00 pad byte is
     * stripped and short values are left-padded back out to 32.
     */
    private fun derToJose(der: ByteArray): ByteArray {
        var offset = 0
        require(der[offset++] == 0x30.toByte()) { "Bad DER signature: no SEQUENCE" }
        // Length byte: >0x80 means multi-byte long form (skip that many bytes).
        val firstLen = der[offset++].toInt() and 0xFF
        if (firstLen >= 0x80) offset += firstLen - 0x80

        fun readInt(): ByteArray {
            require(der[offset++] == 0x02.toByte()) { "Bad DER signature: no INTEGER" }
            val len = der[offset++].toInt() and 0xFF
            val bytes = der.copyOfRange(offset, offset + len)
            offset += len
            // Strip DER's sign-padding zeros, then left-pad to exactly 32 bytes.
            val trimmed = bytes.dropWhile { it == 0.toByte() }.toByteArray()
            require(trimmed.size <= 32) { "ECDSA component longer than 32 bytes" }
            return ByteArray(32 - trimmed.size) + trimmed
        }

        return readInt() + readInt()
    }

    private fun String.b64Url(): String = toByteArray(Charsets.UTF_8).b64Url()

    private fun ByteArray.b64Url(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(this)
}
