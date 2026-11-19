package app.cadence.backend.engine

/**
 * Removes what must not sit in a log file: signed URL parameters, account tokens, e-mail
 * addresses, the device id and FairPlay context handles. Logs get exported and shared when
 * something fails, so every line passes through here before it is written anywhere.
 */
object LogRedactor {
    private val urlQuery = Regex("(https?://[^\\s?#\"']+)[?#][^\\s\"']*")
    private val jwt = Regex("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")
    private val bearer = Regex("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]{12,}")
    private val email = Regex("([A-Za-z0-9._%+-])[A-Za-z0-9._%+-]*@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})")
    private val deviceId = Regex("(?i)(device(?:Id)?=)[0-9a-f]{12,}")
    private val handle = Regex("(handle=)-?\\d{6,}")
    // The device's own address in socket errors ("failed to connect to /host from /ADDR"): it can be
    // a public IPv6 address. The server side of those messages stays, it is what a log is read for.
    private val localAddress = Regex("(\\bfrom /)[^\\s)]+")
    // Private and carrier-grade NAT IPv4 addresses (10/8, 172.16/12, 192.168/16, 100.64/10).
    private val privateV4 = Regex(
        "(?<![\\d.])(?:10\\.\\d{1,3}|192\\.168|172\\.(?:1[6-9]|2\\d|3[01])|100\\.(?:6[4-9]|[7-9]\\d|1[01]\\d|12[0-7]))\\.\\d{1,3}\\.\\d{1,3}(?![\\d.])",
    )
    // A long base64/base64url run, with its padding. `=` is not part of the run, so the name in
    // `name=value` is kept and only the value goes.
    private val opaque = Regex("(?<![A-Za-z0-9+/_-])[A-Za-z0-9+/_-]{48,}={0,2}")

    private val url = Regex("https?://[^\\s\"']+")

    /** URLs keep their path (it names the resource) and lose their parameters; everything else is scanned for secrets. */
    fun clean(message: String): String {
        val out = StringBuilder(message.length)
        var at = 0
        for (match in url.findAll(message)) {
            out.append(scrub(message.substring(at, match.range.first)))
            out.append(match.value.replace(urlQuery, "$1?<redacted>").replace(jwt, "<jwt>"))
            at = match.range.last + 1
        }
        return out.append(scrub(message.substring(at))).toString()
    }

    private fun scrub(text: String): String = text
        .replace(jwt, "<jwt>")
        .replace(bearer, "$1<token>")
        .replace(email, "$1***@$2")
        .replace(deviceId, "$1<id>")
        .replace(handle, "$1<kd>")
        .replace(localAddress, "$1<local>")
        .replace(privateV4, "<lan-ip>")
        .replace(opaque) { "<secret:${it.value.length}>" }
}
