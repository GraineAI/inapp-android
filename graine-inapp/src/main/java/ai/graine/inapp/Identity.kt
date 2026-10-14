package ai.graine.inapp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.Normalizer
import java.util.TimeZone
import java.util.UUID

/*
 * WHO THE USER IS — the identity channel (contract §1.2, §1.3).
 *
 * identify() keeps doing what it always did: traits become prompt variables
 * and `app_context.user`, stringified and masked. On top of that, the value of
 * the ONE trait the agent's Identity setting names (`user_id` unless the
 * dashboard says otherwise) is kept raw and sent only where Graine derives the
 * user's key from it: the ticket, the device registration, sign-out, the
 * scheduling and outcome routes, forgetting — never in variables, never in a
 * context frame, never in a log line.
 *
 * Pure rules here (JVM-tested); the storage below them is a file.
 */

/** What the session says about who the user is: the trait that names them, and how many devices they may use. */
data class SessionIdentity(
    /** A trait name: `user_id`, `email`, `phone` or a custom one such as `patient_id`. */
    val field: String,
    val devicesPerUser: Int,
)

internal object IdentityRules {
    /** Values that name nobody — contract §1.4 PLACEHOLDERS, compared lower-case after trimming. */
    val PLACEHOLDERS: Set<String> = setOf(
        "", "null", "undefined", "none", "nil", "nan", "unknown", "anonymous", "anon", "guest", "0", "-",
        "n/a", "na", "test", "[phone]", "[email]", "[pan]", "[aadhaar]", "[card]",
    )
    const val DEFAULT_FIELD = "user_id"
    private val FIELD = Regex("^[a-z][a-z0-9_]{0,39}$")

    /**
     * What the server trims (Python's `str.strip()`, contract §1.4) — not Kotlin's `trim()`: it strips U+0085,
     * which `trim()` keeps, and keeps U+FEFF. Pinned by identity-vectors.json.
     */
    internal fun isServerSpace(c: Char): Boolean =
        c in "\t\n\u000B\u000C\r \u0085\u00A0\u1680\u2028\u2029\u202F\u205F\u3000" ||
            c in '\u001C'..'\u001F' || c in '\u2000'..'\u200A'


    /** `normalise_field`: a trait name as the platform spells it — trimmed, lower-case — or null when it cannot be one. */
    fun field(raw: Any?): String? {
        val f = (raw as? String)?.trim(::isServerSpace)?.lowercase() ?: return null
        return f.takeIf { FIELD.matches(it) }
    }

    /**
     * The value as the app handed it, as text: a string as it is, a whole
     * number in decimals. Null for null, a boolean, an object, a list or a
     * fraction. A number that is whole counts whatever its type — a bridge
     * from JavaScript, which has no integers, hands 4242 over as 4242.0.
     */
    fun rawText(raw: Any?): String? = when (raw) {
        null, JSONObject.NULL, is Boolean -> null
        is String -> raw
        is Int, is Long, is Short, is Byte, is java.math.BigInteger -> raw.toString()
        is Double, is Float, is java.math.BigDecimal -> {
            val d = (raw as Number).toDouble()
            if (!d.isNaN() && !d.isInfinite() && d == Math.floor(d) && Math.abs(d) < 9.0e15) d.toLong().toString() else null
        }
        else -> null
    }

    /**
     * The reference `normalise_value` (contract §1.4, held to
     * identity-vectors.json): the value the server keys on, or null when
     * there is none. The SDK never derives the key; it uses this only to
     * decide whether there IS an identity — a placeholder ("guest", a masked
     * "[EMAIL]"), an e-mail with no domain or a five-digit phone names nobody,
     * and the conversation is anonymous rather than keyed on it.
     */
    fun normalisedValue(field: String, raw: Any?): String? {
        val text = rawText(raw) ?: return null
        val v = Normalizer.normalize(text, Normalizer.Form.NFC).trim(::isServerSpace)
        if (v.lowercase() in PLACEHOLDERS) return null
        return when (field) {
            "email" -> v.lowercase().takeIf { it.length <= 254 && looksLikeEmail(it) }
            "phone" -> {
                val digits = v.filter { it in '0'..'9' } // ASCII digits only, as the server counts them
                if (digits.length < 8 || digits.length > 15) null else digits.takeLast(10)
            }
            else -> v.takeIf { it.length <= 256 }
        }
    }

    /** `^[^@\s]+@[^@\s]+\.[^@\s]+$` as Python reads it: one "@" with something before it, and a dot inside what follows. */
    private fun looksLikeEmail(s: String): Boolean {
        if (s.any { Character.isWhitespace(it) || Character.isSpaceChar(it) }) return false
        val at = s.indexOf('@')
        if (at <= 0 || s.indexOf('@', at + 1) >= 0) return false
        val domain = s.substring(at + 1)
        return domain.indices.any { i -> domain[i] == '.' && i > 0 && i < domain.length - 1 }
    }

    /** The key in `traits` that holds `field`: the exact one first, else the same name in other capitals or spaces. */
    fun keyFor(traits: Map<String, Any?>?, field: String?): String? {
        if (traits == null || field == null) return null
        if (traits.containsKey(field)) return field
        return traits.keys.firstOrNull { it.trim(::isServerSpace).lowercase() == field }
    }

    /** The identity value to send for `traits` under `field`: the trait's raw text, when it is an identity at all. */
    fun pick(traits: Map<String, Any?>?, field: String?): String? {
        val key = keyFor(traits, field) ?: return null
        val raw = traits!![key]
        if (normalisedValue(field!!, raw) == null) return null
        return rawText(raw)?.let { Normalizer.normalize(it, Normalizer.Form.NFC).trim(::isServerSpace) }
    }

    /** The session's identity block; null when it is absent (an older server). A field that cannot be one is the default. */
    fun parse(raw: Any?): SessionIdentity? {
        val o = raw as? JSONObject ?: return null
        val devices = Js.finite(o.opt("devicesPerUser"))?.let { Math.round(it).toInt() }?.coerceIn(1, 5) ?: 2
        return SessionIdentity(field(o.opt("field")) ?: DEFAULT_FIELD, devices)
    }

    /** The device's zone, e.g. `Asia/Kolkata`. */
    fun timezone(): String? = runCatching { TimeZone.getDefault().id }.getOrNull()?.takeIf { it.isNotBlank() }

    /** SHA-256 of `text`, its first 16 bytes in lower-case hex. */
    fun sha16(text: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        val hex = "0123456789abcdef"
        val sb = StringBuilder(32)
        for (i in 0 until 16) { val v = d[i].toInt() and 0xff; sb.append(hex[v shr 4]).append(hex[v and 15]) }
        return sb.toString()
    }

    /** Where a memory choice is filed: a one-way hash of the field and the normalised value — never the value itself. */
    fun memoryKey(field: String, value: String): String =
        sha16("graine:memory:v1\n" + field + "\n" + (normalisedValue(field, value) ?: value))

    /** A short one-way name for anything that must be compared without being kept. */
    fun fingerprint(value: String): String = sha16(value)
}

/**
 * What a request to Graine says about who is using the app (contract §1.3):
 * `identity: {value}` when there is one, `installId`, `memory` and `timezone`.
 * The ticket, device, sign-out, scheduling, outcome and forget routes take it.
 */
internal data class IdentityEnvelope(
    val value: String?,
    val installId: String?,
    val memory: Boolean,
    val timezone: String?,
) {
    /** `installId` and, only with one, `identity: {value}` — what every route in WIRE.md starts from. */
    fun who(o: JSONObject): JSONObject {
        installId?.let { o.put("installId", it) }
        value?.let { o.put("identity", JSONObject().put("value", it)) }
        return o
    }

    /** The ticket's (and the engine page's) fields: `identity?`, `installId`, `memory`, `timezone`. */
    fun into(o: JSONObject): JSONObject {
        value?.let { o.put("identity", JSONObject().put("value", it)) }
        installId?.let { o.put("installId", it) }
        o.put("memory", memory)
        timezone?.let { o.put("timezone", it) }
        return o
    }

    /** For a log line: never the value, only whether there is one. */
    override fun toString(): String = "IdentityEnvelope(identity=${if (value == null) "none" else "set"}, memory=$memory, timezone=$timezone)"
}

/**
 * THIS INSTALL'S OWN LITTLE STORE — the install id, the FCM token, and the
 * memory choice per user — in the app's no-backup files directory.
 *
 * The contract asks for preferences excluded from backup. A library cannot
 * add rules to the app's backup configuration, and SharedPreferences are
 * backed up unless the app says otherwise; a file under `noBackupFilesDir`
 * is excluded by the platform itself, with nothing for the app to configure.
 * A restored phone is therefore a new install, which is the point: a bound
 * device is this phone, not a copy of it.
 *
 * Any failure to read or write leaves the values in memory for this process
 * — calls and chat never fail because a file could not be written.
 */
internal class DeviceStore(private val file: File?) {
    private var values: JSONObject? = null

    @Synchronized
    fun get(key: String): String? {
        val v = load()
        return if (v.has(key)) v.optString(key).ifEmpty { null } else null
    }

    @Synchronized
    fun put(key: String, value: String?) {
        val v = load()
        if (value == null) v.remove(key) else v.put(key, value)
        save(v)
    }

    private fun load(): JSONObject {
        values?.let { return it }
        val read = runCatching { file?.takeIf { it.exists() }?.readText()?.let { JSONObject(it) } }.getOrNull() ?: JSONObject()
        values = read
        return read
    }

    private fun save(v: JSONObject) {
        val f = file ?: return
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(v.toString())
            if (!tmp.renameTo(f)) { f.writeText(v.toString()); tmp.delete() }
        }
    }

    // ── what is kept ───────────────────────────────────────────────────────

    /** A UUIDv4 made on this install's first launch, then the same one for as long as the app stays installed. */
    @Synchronized
    fun installId(): String {
        get(KEY_INSTALL)?.takeIf { InstallIds.valid(it) }?.let { return it }
        val fresh = InstallIds.fresh()
        put(KEY_INSTALL, fresh)
        return fresh
    }

    var pushToken: String?
        get() = get(KEY_TOKEN)
        set(v) = put(KEY_TOKEN, v?.takeIf { it.isNotBlank() })

    /** The last memory choice made for this user (filed under [IdentityRules.memoryKey]) on this install; null when none was. */
    @Synchronized
    fun memoryFor(field: String, value: String): Boolean? {
        val key = IdentityRules.memoryKey(field, value)
        val list = memoryList()
        for (i in 0 until list.length()) {
            val row = list.optJSONArray(i) ?: continue
            if (row.optString(0) == key) return row.optBoolean(1, true)
        }
        return null
    }

    /** Remember a choice; the newest [MEMORY_CHOICES] users' are kept. */
    @Synchronized
    fun rememberMemory(field: String, value: String, enabled: Boolean) {
        val key = IdentityRules.memoryKey(field, value)
        val old = memoryList()
        val next = JSONArray()
        for (i in 0 until old.length()) {
            val row = old.optJSONArray(i) ?: continue
            if (row.optString(0) != key) next.put(row)
        }
        next.put(JSONArray().put(key).put(enabled))
        while (next.length() > MEMORY_CHOICES) next.remove(0)
        put(KEY_MEMORY, next.toString())
    }

    /**
     * The last device registration that went through: a fingerprint of what
     * it said, and when. Each agent of an app keeps its own, under its
     * [owner] (its key and base URL, fingerprinted); null is the one record
     * kept before agents had names.
     */
    @Synchronized
    fun lastRegistration(owner: String? = null): Pair<String, Long>? {
        val o = runCatching { JSONObject(get(registrationKey(owner)) ?: return null) }.getOrNull() ?: return null
        val f = o.optString("f").ifEmpty { return null }
        return f to o.optLong("at", 0L)
    }

    @Synchronized
    fun registered(fingerprint: String?, atMs: Long, owner: String? = null) {
        put(registrationKey(owner), fingerprint?.let { JSONObject().put("f", it).put("at", atMs).toString() })
    }

    private fun registrationKey(owner: String?): String = if (owner.isNullOrEmpty()) KEY_REGISTERED else "$KEY_REGISTERED.$owner"

    private fun memoryList(): JSONArray = runCatching { JSONArray(get(KEY_MEMORY) ?: "[]") }.getOrNull() ?: JSONArray()

    companion object {
        private const val KEY_INSTALL = "installId"
        private const val KEY_TOKEN = "fcmToken"
        private const val KEY_MEMORY = "memory"
        private const val KEY_REGISTERED = "registered"
        const val MEMORY_CHOICES = 32

        @Volatile private var shared: DeviceStore? = null

        /** The one store of this process, in `noBackupFilesDir/graine-inapp/device.json`. */
        fun of(context: Context): DeviceStore = shared ?: synchronized(this) {
            shared ?: DeviceStore(
                runCatching { File(File(context.applicationContext.noBackupFilesDir, "graine-inapp"), "device.json") }.getOrNull(),
            ).also { shared = it }
        }
    }
}

internal object InstallIds {
    /** The shape the server accepts (contract §1.4: 16–64 of hex and dashes, lower case). */
    private val VALID = Regex("^[0-9a-f-]{16,64}$")

    fun valid(s: String?): Boolean = s != null && VALID.matches(s)

    fun fresh(): String = UUID.randomUUID().toString().lowercase()
}

/**
 * WHO IS SIGNED IN, AS THE IDENTITY CHANNEL SEES IT. The traits are held raw,
 * in memory only, until the session says which one names the customer (a
 * ticket is never minted before the session); the value is read from them
 * then. Main-thread writes, any-thread reads.
 */
internal class IdentityState(private val store: () -> DeviceStore) {
    @Volatile private var traits: Map<String, Any?>? = null
    /** The field the session named; null until the session arrives — no identity before then. */
    @Volatile private var field: String? = null
    /** The memory choice the app made for the current user, this launch. */
    @Volatile private var explicit: Boolean? = null

    /** identify(): the traits, and the memory choice when the app made one. */
    fun set(next: Map<String, Any?>?, memory: Boolean?) {
        val before = value()
        traits = next?.let { LinkedHashMap(it) }
        // Another user: their own kept choice applies, not the last user's.
        if (memory != null) explicit = memory else if (value() != before) explicit = null
        remember()
    }

    /** The session named the field (null: it says nothing, so the default). A choice already made follows the user. */
    fun onSession(identity: SessionIdentity?) {
        field = identity?.field ?: IdentityRules.DEFAULT_FIELD
        remember()
    }

    /** The trait that names the user — kept out of the variables and `context.user`. `user_id` until the session says. */
    fun fieldOrDefault(): String = field ?: IdentityRules.DEFAULT_FIELD

    /** The raw value of the configured field, or null: no identity (or no session yet). */
    fun value(): String? = field?.let { IdentityRules.pick(traits, it) }

    /** This user's memory choice: what the app said, else what it said for them before on this install, else on. */
    fun memory(): Boolean {
        explicit?.let { return it }
        val f = field ?: return true
        val v = value() ?: return true
        return runCatching { store().memoryFor(f, v) }.getOrNull() ?: true
    }

    fun setMemory(enabled: Boolean) {
        explicit = enabled
        remember()
    }

    private fun remember() {
        val m = explicit ?: return
        val f = field ?: return
        val v = value() ?: return
        runCatching { store().rememberMemory(f, v, m) }
    }

    fun envelope(): IdentityEnvelope =
        IdentityEnvelope(value(), runCatching { store().installId() }.getOrNull(), memory(), IdentityRules.timezone())
}
