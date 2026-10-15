package ai.graine.inapp

import java.security.MessageDigest

/**
 * DIGEST AUTHENTICATION, AS THE VOICE BAR'S SIP STACK ANSWERS A CHALLENGE —
 * RFC 2617 (and the MD5 of RFC 7616), with `qop=auth` and its client nonce and
 * count when the server offers it. Pure, so the RFC's own example is a test.
 *
 * The server challenges every new request (REGISTER, INVITE, un-REGISTER) with
 * a fresh nonce, and each is answered once: the count is always 00000001 and
 * the client nonce is new. The header's parameters go in the browser's order —
 * algorithm, username, realm, nonce, uri, response, opaque, qop, cnonce, nc —
 * with qop and nc unquoted, which is what the server has always accepted.
 */
internal object SipDigest {
    fun md5Hex(s: String): String {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(32)
        for (b in d) {
            val v = b.toInt() and 0xff
            sb.append("0123456789abcdef"[v shr 4]).append("0123456789abcdef"[v and 15])
        }
        return sb.toString()
    }

    /**
     * The `Authorization` (or `Proxy-Authorization`) value answering
     * [challenge] for [method] [uri], or null when it cannot be answered: an
     * algorithm other than MD5, or qop options with neither auth nor auth-int.
     * `auth-int` is preferred when offered, as the browser's stack prefers it.
     */
    fun authorize(
        challenge: SipChallenge,
        username: String,
        password: String,
        method: String,
        uri: String,
        body: String?,
        cnonce: String,
        nc: Int = 1,
    ): String? {
        val algorithm = challenge.algorithm ?: "MD5"
        if (!algorithm.equals("MD5", ignoreCase = true)) return null
        val qop = when {
            challenge.qop.isEmpty() -> null
            "auth-int" in challenge.qop -> "auth-int"
            "auth" in challenge.qop -> "auth"
            else -> return null
        }
        val ncHex = Integer.toHexString(nc).padStart(8, '0')
        val response = response(username, challenge.realm, password, method, uri, challenge.nonce, qop, ncHex, cnonce, body)
        val sb = StringBuilder("Digest ")
        sb.append("algorithm=").append(algorithm)
        sb.append(", username=\"").append(username).append('"')
        sb.append(", realm=\"").append(challenge.realm).append('"')
        sb.append(", nonce=\"").append(challenge.nonce).append('"')
        sb.append(", uri=\"").append(uri).append('"')
        sb.append(", response=\"").append(response).append('"')
        challenge.opaque?.let { sb.append(", opaque=\"").append(it).append('"') }
        if (qop != null) {
            sb.append(", qop=").append(qop)
            sb.append(", cnonce=\"").append(cnonce).append('"')
            sb.append(", nc=").append(ncHex)
        }
        return sb.toString()
    }

    /** RFC 2617 §3.2.2.1: the request-digest. */
    fun response(
        username: String,
        realm: String,
        password: String,
        method: String,
        uri: String,
        nonce: String,
        qop: String?,
        ncHex: String,
        cnonce: String,
        body: String? = null,
    ): String {
        val ha1 = md5Hex("$username:$realm:$password")
        val ha2 = if (qop == "auth-int") md5Hex("$method:$uri:${md5Hex(body.orEmpty())}") else md5Hex("$method:$uri")
        return if (qop == null) md5Hex("$ha1:$nonce:$ha2") else md5Hex("$ha1:$nonce:$ncHex:$cnonce:$qop:$ha2")
    }
}
