package atlas.net

/**
 * Pure normalization + validation for the user-entered backend URL.
 *
 * Lives in commonMain (and is unit-tested in commonTest) so the logic that decides
 * whether a typed server address is usable runs identically on every target and stays
 * free of Android/SharedPreferences concerns. The Android [Settings] store delegates to
 * [normalize]; the heatmap settings dialog uses it to validate input before persisting.
 */
object ServerUrl {

    /**
     * Default backend for a fresh install: the deployed Cloud Run service (Mumbai),
     * so the app shows the live crowdsourced heatmap out of the box. Users running the
     * backend locally can override this via the in-app Server URL setting (e.g.
     * `http://10.0.2.2:8080` for an emulator, or a LAN IP for a real device).
     */
    const val DEFAULT: String = "https://netatlas-backend-872879151769.asia-south1.run.app"

    // host[.host]* optionally :port, OR a bare IPv4[:port]. Deliberately conservative —
    // enough to reject "not a url" while accepting LAN IPs and DNS names the POC needs.
    private val HOST_PORT = Regex("""^[A-Za-z0-9.-]+(:\d{1,5})?$""")

    /**
     * Normalizes raw user input into a canonical `http(s)://host[:port]` URL, or returns
     * `null` when the result isn't a plausible http(s) address.
     *
     * Rules:
     *  - trims surrounding whitespace;
     *  - strips any trailing slash(es);
     *  - prepends `http://` when no scheme is present;
     *  - only `http`/`https` schemes are accepted (e.g. `ftp://x` → null);
     *  - the host part must look like `host[:port]` (no spaces, no path).
     */
    fun normalize(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        val schemeMatch = Regex("""^([A-Za-z][A-Za-z0-9+.-]*)://(.*)$""").find(trimmed)
        val (scheme, rest) = if (schemeMatch != null) {
            val s = schemeMatch.groupValues[1].lowercase()
            if (s != "http" && s != "https") return null // reject ftp:// and friends
            s to schemeMatch.groupValues[2]
        } else {
            "http" to trimmed
        }

        // Drop trailing slash(es); reject anything with an embedded path segment.
        val hostPort = rest.trimEnd('/')
        if (hostPort.isEmpty() || hostPort.contains('/')) return null
        if (!HOST_PORT.matches(hostPort)) return null

        return "$scheme://$hostPort"
    }
}
