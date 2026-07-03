package net.guacamaya.backend

/**
 * One official `ChannelRecord` as served by `GET /channels/:id/records`.
 *
 * [payloadRaw] is the **exact JSON text of the `payload` value as it arrived on the
 * wire** — never re-serialized. The backend signs `sha256("channel:timestamp:ttl:
 * author:verified:" + JSON.stringify(payload))`, so verification must hash the same
 * bytes the server produced; re-encoding the payload (e.g. via org.json, which does
 * not preserve key order) would change those bytes and break the signature.
 * See [OfficialRecordVerifier].
 */
data class OfficialRecord(
    val channel: String,
    val timestamp: Long,
    val ttl: Int,
    val author: String,
    val verified: Boolean,
    val id: String,
    val sig: String?,
    val payloadRaw: String,
)

/**
 * Minimal, order-preserving JSON reader for the records endpoint. Hand-rolled (no
 * org.json) precisely so it can return each record's `payload` as the verbatim source
 * substring. Tolerant of unknown keys; assumes the compact output of the backend.
 */
object RecordJson {

    fun parseRecords(body: String): List<OfficialRecord> {
        val sc = Scanner(body)
        val out = mutableListOf<OfficialRecord>()
        sc.skipWs()
        if (sc.atEnd()) return out
        sc.expect('[')
        sc.skipWs()
        if (sc.peekOrNull() == ']') { sc.next(); return out }
        while (true) {
            out += parseRecord(sc)
            sc.skipWs()
            when (sc.next()) {
                ',' -> sc.skipWs()
                ']' -> break
                else -> throw IllegalArgumentException("expected ',' or ']' in records array")
            }
        }
        return out
    }

    private fun parseRecord(sc: Scanner): OfficialRecord {
        sc.expect('{')
        var channel: String? = null
        var timestamp: Long? = null
        var ttl: Int? = null
        var author: String? = null
        var verified: Boolean? = null
        var id: String? = null
        var sig: String? = null
        var payloadRaw: String? = null

        sc.skipWs()
        if (sc.peekOrNull() == '}') { sc.next() } else {
            while (true) {
                sc.skipWs()
                val key = sc.readString()
                sc.skipWs(); sc.expect(':'); sc.skipWs()
                when (key) {
                    "channel" -> channel = sc.readString()
                    "timestamp" -> timestamp = sc.readRawValue().trim().toLong()
                    "ttl" -> ttl = sc.readRawValue().trim().toInt()
                    "author" -> author = sc.readString()
                    "verified" -> verified = sc.readRawValue().trim().toBoolean()
                    "id" -> id = sc.readString()
                    "sig" -> { val raw = sc.readRawValue().trim(); sig = if (raw == "null") null else stripQuotes(raw) }
                    "payload" -> payloadRaw = sc.readRawValue()
                    else -> sc.readRawValue() // skip unknown
                }
                sc.skipWs()
                when (sc.next()) {
                    ',' -> {}
                    '}' -> break
                    else -> throw IllegalArgumentException("expected ',' or '}' in record object")
                }
            }
        }

        return OfficialRecord(
            channel = channel ?: throw IllegalArgumentException("record missing channel"),
            timestamp = timestamp ?: throw IllegalArgumentException("record missing timestamp"),
            ttl = ttl ?: throw IllegalArgumentException("record missing ttl"),
            author = author ?: throw IllegalArgumentException("record missing author"),
            verified = verified ?: throw IllegalArgumentException("record missing verified"),
            id = id ?: throw IllegalArgumentException("record missing id"),
            sig = sig,
            payloadRaw = payloadRaw ?: throw IllegalArgumentException("record missing payload"),
        )
    }

    private fun stripQuotes(raw: String): String =
        if (raw.length >= 2 && raw.first() == '"' && raw.last() == '"') {
            // decode via the scanner so escapes are handled
            Scanner(raw).readString()
        } else raw

    /** Cursor over the JSON text. Exposes both decoded strings and verbatim value spans. */
    private class Scanner(private val s: String) {
        private var i = 0

        fun skipWs() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun atEnd(): Boolean { skipWs(); return i >= s.length }
        fun peekOrNull(): Char? { skipWs(); return if (i < s.length) s[i] else null }
        fun next(): Char { skipWs(); return s[i++] }
        fun expect(c: Char) {
            skipWs()
            if (i >= s.length || s[i] != c) throw IllegalArgumentException("expected '$c' at $i")
            i++
        }

        /** Decode a JSON string (handles standard escapes). Leaves cursor after closing quote. */
        fun readString(): String {
            skipWs()
            if (i >= s.length || s[i] != '"') throw IllegalArgumentException("expected string at $i")
            i++
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        val e = s[i++]
                        when (e) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> throw IllegalArgumentException("bad escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
            throw IllegalArgumentException("unterminated string")
        }

        /** Return the verbatim source text of the next JSON value, cursor left after it. */
        fun readRawValue(): String {
            skipWs()
            val start = i
            skipValue()
            return s.substring(start, i)
        }

        private fun skipValue() {
            skipWs()
            when (s[i]) {
                '{' -> skipBalanced('{', '}')
                '[' -> skipBalanced('[', ']')
                '"' -> readString()
                else -> { // number, true, false, null
                    while (i < s.length && s[i] !in ",}]" && !s[i].isWhitespace()) i++
                }
            }
        }

        private fun skipBalanced(open: Char, close: Char) {
            var depth = 0
            while (i < s.length) {
                when (s[i]) {
                    '"' -> { readString(); continue }
                    open -> depth++
                    close -> { depth--; if (depth == 0) { i++; return } }
                }
                i++
            }
            throw IllegalArgumentException("unbalanced '$open'")
        }
    }
}
