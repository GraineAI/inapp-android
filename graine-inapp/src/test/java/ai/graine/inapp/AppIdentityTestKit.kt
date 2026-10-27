package ai.graine.inapp

import org.json.JSONObject
import java.io.File
import java.util.Collections

/*
 * WHAT THE APP-IDENTITY TESTS SHARE (APP-IDENTITY.md): the contract file —
 * read, never skipped: a missing file fails every test that needs it — and
 * fakes for the platform's signing certificates, Play Integrity and Graine's
 * routes. Nothing here reaches a host.
 */

/**
 * Two JSON values with the same content, whatever the order of their keys —
 * org.json's own `similar` is not in the Android stubs the tests compile against.
 */
internal fun sameJson(a: Any?, b: Any?): Boolean = when {
    a is JSONObject && b is JSONObject -> a.length() == b.length() && a.keys().asSequence().all { k -> b.has(k) && sameJson(a.opt(k), b.opt(k)) }
    a is org.json.JSONArray && b is org.json.JSONArray -> a.length() == b.length() && (0 until a.length()).all { sameJson(a.opt(it), b.opt(it)) }
    a is Number && b is Number -> a.toDouble() == b.toDouble()
    else -> a == b
}

/** sdk/contract/app-identity.json (contract/ in the standalone repository). Missing: the test FAILS. */
internal object AppIdentityContract {
    val json: JSONObject by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "sdk/contract/app-identity.json").exists() && !File(dir, "contract/app-identity.json").exists()) dir = dir.parentFile
        val f = listOf(File(dir, "sdk/contract/app-identity.json"), File(dir, "contract/app-identity.json")).firstOrNull { it.exists() }
            ?: error("sdk/contract/app-identity.json missing — this contract is never skipped")
        JSONObject(f.readText())
    }

    fun strings(name: String): List<String> = json.getJSONArray(name).let { a -> (0 until a.length()).map { a.getString(it) } }
}

/** The platform's signing certificates, as a test hands them in: every call counted, any of them throwing on demand. */
internal class FakeSigning(
    var info: SigningCertificates? = null,
    var legacy: List<ByteArray>? = null,
    var fail: Boolean = false,
) : PackageSigning {
    val asked = mutableListOf<String>()

    override fun signingInfo(packageName: String): SigningCertificates? {
        asked += "signingInfo:$packageName"
        if (fail) throw SecurityException("no")
        return info
    }

    override fun signatures(packageName: String): List<ByteArray>? {
        asked += "signatures:$packageName"
        if (fail) throw SecurityException("no")
        return legacy
    }
}

/** Play Integrity, scripted: each request takes the next answer (the last repeats), and every nonce asked is kept. */
internal class FakeProver(vararg answers: IntegrityAnswer) : IntegrityProver {
    private val queue = ArrayDeque(answers.toList())
    val nonces: MutableList<String> = Collections.synchronizedList(ArrayList())
    val cloudNumbers: MutableList<Long?> = Collections.synchronizedList(ArrayList())
    /** Called before answering: a test that wants the proof to take a while blocks here. */
    var before: () -> Unit = {}

    override fun token(nonce: String, cloudProjectNumber: Long?, timeoutMs: Long): IntegrityAnswer {
        nonces += nonce
        cloudNumbers += cloudProjectNumber
        before()
        return synchronized(queue) { if (queue.size > 1) queue.removeFirst() else queue.first() }
    }
}

/**
 * GRAINE'S ROUTES, FAKED: every request kept with its path and body, and an
 * answer per path (200 `{}` unless the test says otherwise) — a list of
 * answers is used in order, its last repeating.
 */
internal class FakeEmbed(val base: String = "https://www.graine.ai") : HttpPost {
    class Sent(val url: String, val body: JSONObject) {
        /** The route after the embed prefix: `ticket`, `app-verify/challenge`, … */
        val path: String get() = url.substringAfter("/embed/")
    }

    val sent: MutableList<Sent> = Collections.synchronizedList(ArrayList())
    private val answers = HashMap<String, ArrayDeque<(JSONObject) -> HttpReply>>()

    /** Answer [path] (`ticket`, `app-verify/challenge`, …) with each of [replies] in turn, the last repeating. */
    fun answer(path: String, vararg replies: (JSONObject) -> HttpReply) {
        synchronized(answers) { answers[path] = ArrayDeque(replies.toList()) }
    }

    override fun post(url: String, json: String): HttpReply {
        val s = Sent(url, JSONObject(json))
        sent += s
        val reply = synchronized(answers) {
            val q = answers[s.path] ?: return@synchronized null
            if (q.size > 1) q.removeFirst() else q.first()
        }
        return reply?.invoke(s.body) ?: HttpReply(200, "{}")
    }

    fun to(path: String): List<JSONObject> = synchronized(sent) { sent.filter { it.path == path }.map { it.body } }

    companion object {
        /** A challenge of the contract's shape: 48 base64url characters. */
        fun challenge(n: Int): String = ("c$n" + "_".repeat(48)).take(48)

        fun ok(json: JSONObject): (JSONObject) -> HttpReply = { HttpReply(200, json.toString()) }

        fun refused(status: Int, code: String, reason: String? = null, retryAfter: Long? = null): (JSONObject) -> HttpReply = {
            HttpReply(status, JSONObject().put("error", "refused").put("code", code).apply { reason?.let { r -> put("reason", r) } }.toString(), retryAfter)
        }

        fun status(code: Int, retryAfter: Long? = null): (JSONObject) -> HttpReply = { HttpReply(code, "{}", retryAfter) }

        /** app-verify's 200: a pass of [ttl] seconds with [verdict]. */
        fun pass(pass: String, ttl: Long = 86_400, verdict: String = "verified"): (JSONObject) -> HttpReply =
            ok(JSONObject().put("verification", JSONObject().put("app", verdict).put("appPass", pass).put("appPassExpiresIn", ttl)))
    }
}
