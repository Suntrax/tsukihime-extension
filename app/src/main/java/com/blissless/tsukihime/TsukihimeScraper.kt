package com.blissless.tsukihime

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

/**
 * Pure-HTTP scraper for tsukihime.org.
 *
 * The site is a Next.js SPA, but every search call funnels through a public
 * REST API at https://api.tsukihime.org/v1. Discovered via the JS bundle's
 * `fetch("https://api.tsukihime.org/v1/search/torrents?q=...")` call.
 *
 * Endpoint:
 *   GET https://api.tsukihime.org/v1/search/torrents?q=<query>&limit=30&offset=0
 *
 * Response:
 *   { "total": N, "start": 0, "limit": 30, "error": false,
 *     "results": [ { "name": "...", "btih": "<40-char infohash>",
 *                    "anime": {"title": "<romaji>", "english_title": "<en>"},
 *                    ... }, ... ] }
 *
 * Match strategy (exact match, single magnet):
 *   The Main App passes both the English and romaji titles in the URI:
 *     content://.../scrape?anime=<english>&anilistId=<id>&animeRomaji=<romaji>
 *   We walk the API's results page by page and return the FIRST torrent whose
 *   `anime.english_title` matches the English query OR whose `anime.title`
 *   (romaji) matches the romaji query, after normalization. The result is a
 *   single-element List<String> containing that torrent's magnet URI.
 *
 * The API hands us the BitTorrent infohash directly, so we build magnet URIs
 * ourselves — no need to visit tsukihime.org at all, no JS rendering, no
 * WebView, no .torrent file download.
 *
 * Uses only Android built-ins (HttpURLConnection + org.json) — no OkHttp,
 * Jsoup, or Gson — to keep the APK under ~50 KB after R8.
 */
object TsukihimeScraper {

    private const val SEARCH_URL =
        "https://api.tsukihime.org/v1/search/torrents"
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    // Public trackers to attach to each magnet URI. The API doesn't return
    // tracker URLs, but the infohash is enough to bootstrap a DHT lookup;
    // including a few well-known public trackers speeds up peer discovery.
    private val TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.demonii.com:1337/announce",
        "udp://tracker.openbittorrent.com:6969/announce",
        "udp://exodus.desync.com:6969/announce",
        "https://tracker.tamersunion.org:443/announce"
    )

    /**
     * Called by ScraperProvider. Mirrors the original signature so the
     * provider doesn't need changes beyond extracting `animeRomaji` from
     * the URI.
     *
     * The URI now looks like:
     *   content://<authority>/scrape?anime=<english>&anilistId=<id>
     *                              &animeRomaji=<romaji>&category=sub
     *
     * Match strategy:
     *   - Walk the API's search results page by page.
     *   - For each result, normalize `anime.english_title` and `anime.title`
     *     (romaji) and compare against the normalized `animeName` and
     *     `animeRomaji` respectively.
     *   - The first result where EITHER field matches exactly is the anime
     *     we want. Return its magnet URI as a single-element list.
     *   - If no exact match is found across all pages, return empty list.
     *
     * Normalization handles cosmetic differences between AniList and
     * tsukihime titles: trailing "(TV)" suffixes, punctuation, casing,
     * whitespace runs.
     *
     * @param context      Application context (unused for HTTP).
     * @param animeName    English title from the Main App (URI: `anime`).
     * @param anilistId    AniList ID (unused by tsukihime, but available).
     * @param animeRomaji  Romaji title from the Main App (URI: `animeRomaji`).
     * @return Single-element List<String> with the magnet URI, or empty list.
     */
    fun scrape(
        context: Context,
        animeName: String?,
        anilistId: String?,
        animeRomaji: String? = null
    ): Any {
        val english = animeName?.takeIf { it.isNotBlank() }
        val romaji  = animeRomaji?.takeIf { it.isNotBlank() }
        if (english == null && romaji == null) {
            throw IllegalArgumentException("No anime name or romaji provided")
        }

        return searchForExactMatch(english, romaji)
    }

    /**
     * Walk the API's search results page by page until we find a torrent
     * whose `anime.english_title` matches [english] or whose `anime.title`
     * (romaji) matches [romaji]. Returns the first such torrent's magnet URI
     * in a single-element list, or empty list if no exact match exists.
     */
    private fun searchForExactMatch(
        english: String?, romaji: String?
    ): List<String> {
        val englishNorm = english?.let { normalize(it) }
        val romajiNorm  = romaji?.let { normalize(it) }

        // Use whichever name we have for the search query. English is
        // preferred because tsukihime's search seems to weight it more
        // heavily, but romaji works fine for shows without English titles.
        val query = english ?: romaji!!

        var offset = 0
        var total = Int.MAX_VALUE

        while (offset < total) {
            val url = "$SEARCH_URL?q=" +
                    URLEncoder.encode(query.trim(), "UTF-8") +
                    "&limit=30&offset=$offset"
            val raw = fetch(url)

            val root: JSONObject = try {
                JSONObject(raw)
            } catch (e: Exception) {
                throw RuntimeException(
                    "Tsukihime API returned malformed JSON: ${e.message}"
                )
            }

            // {"total":0,"start":0,"limit":5,"error":false,"results":[]}
            // is a valid empty response.
            total = root.optInt("total", 0)
            if (total == 0) break

            val results = root.optJSONArray("results") ?: break
            if (results.length() == 0) break

            for (i in 0 until results.length()) {
                val r = results.optJSONObject(i) ?: continue
                val animeObj = r.optJSONObject("anime") ?: continue

                val apiEnglish = normalize(animeObj.optString("english_title", ""))
                val apiRomaji  = normalize(animeObj.optString("title", ""))

                val englishMatch = englishNorm != null && apiEnglish == englishNorm
                val romajiMatch  = romajiNorm  != null && apiRomaji  == romajiNorm

                if (englishMatch || romajiMatch) {
                    val btih = r.optString("btih").trim().lowercase()
                    if (btih.length != 40) continue  // private releases: "<redacted>"
                    val releaseName = r.optString("name", "")
                    return listOf(buildMagnet(btih, releaseName))
                }
            }

            offset += results.length()
            if (results.length() < 30) break  // last page
        }

        return emptyList()
    }

    /**
     * Normalize a title for exact-match comparison.
     *
     *   "Kimetsu no Yaiba: Mugen Ressha-hen (TV)"
     *   → "kimetsu no yaiba mugen ressha hen"
     *
     *   "Kimetsu no Yaiba: Mugen Ressha-hen"
     *   → "kimetsu no yaiba mugen ressha hen"   (same — handles AniList/API diffs)
     *
     *   "Demon Slayer: Kimetsu no Yaiba"
     *   → "demon slayer kimetsu no yaiba"
     *
     *   "Demon Slayer: Kimetsu no Yaiba Mugen Train Arc"
     *   → "demon slayer kimetsu no yaiba mugen train arc"  (distinct from S01)
     */
    private fun normalize(s: String): String {
        return s.trim().lowercase()
            .replace(Regex("\\([^)]*\\)"), " ")   // drop "(TV)", "(2025)", etc.
            .replace(Regex("[^a-z0-9 ]"), " ")    // punctuation -> space
            .replace(Regex("\\s+"), " ")          // collapse whitespace
            .trim()
    }

    private fun buildMagnet(btih: String, displayName: String): String {
        val sb = StringBuilder("magnet:?xt=urn:btih:").append(btih)
        if (displayName.isNotEmpty()) {
            sb.append("&dn=").append(URLEncoder.encode(displayName, "UTF-8"))
        }
        for (tr in TRACKERS) {
            sb.append("&tr=").append(URLEncoder.encode(tr, "UTF-8"))
        }
        return sb.toString()
    }

    private fun fetch(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Referer", "https://tsukihime.org/")
            connectTimeout = 15_000
            readTimeout = 15_000
            instanceFollowRedirects = true
        }
        try {
            val code = conn.responseCode
            if (code == 429) {
                // Rate-limited (the API allows ~50 req/min). Back off briefly.
                Thread.sleep(2000)
                throw RuntimeException("Tsukihime API rate limit hit (429)")
            }
            if (code !in 200..299) {
                throw RuntimeException("Tsukihime API HTTP $code")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

}
