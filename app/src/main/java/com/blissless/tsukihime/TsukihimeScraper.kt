package com.blissless.tsukihime

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/*
 * Kotlin port of the tsukihime-scraper Python script.
 *
 * The Python original uses Playwright (a headless Chromium) because
 * tsukihime.org renders its search results client-side via JavaScript — a
 * plain HTTP GET returns an empty shell. This port therefore uses Android's
 * built-in WebView in the same headless fashion:
 *
 *   - blocks image/font/media requests          (mirrors route.abort())
 *   - waits for `span.truncate` to appear, 10s  (mirrors wait_for_selector)
 *   - extracts every `div.flex-1.min-w-0` entry's name + .torrent href
 *   - fuzzy-matches names against the query     (WRatio approximation)
 *   - returns the best-matching .torrent href
 *
 * Returns a List<String> (Format 2 — flat list in the Tensei data contract).
 * ScraperProvider serializes this to a JSON array.
 *
 * NOTE: the Python returns a .torrent file URL, NOT a magnet link. The Main
 * App's parser will display it (labelled "Magnet N:") and a torrent client can
 * open it. If you want magnets instead, swap the `a[href*=".torrent"]`
 * selector for `a[href^="magnet:"]` in the injected JS below.
 *
 * Uses only Android built-ins (WebView + org.json) — no Playwright, no Jsoup,
 * no Gson — to keep the APK under ~50 KB after R8.
 *
*/
object TsukihimeScraper {

    private const val BASE_URL = "https://tsukihime.org"

    // A full browser UA — tsukihime.org is behind a JS render and a real UA
    // is less likely to be challenged than Python's default.
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val EMPTY_RESPONSE = WebResourceResponse(
        "text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))
    )

    /**
     * Called by ScraperProvider. Mirrors the Python `scrape_torrent(base_url, anime)`
     * but returns a list so the Main App can display zero, one, or many results.
     *
     * @param context    Application context (used to instantiate the WebView).
     * @param animeName  Anime title (English or Romaji) from the Main App.
     * @param anilistId  AniList ID (not used by tsukihime, but available).
     * @return List of .torrent URLs (may be empty).
     */
    fun scrape(context: Context, animeName: String?, anilistId: String?): Any {
        val anime = animeName?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("No anime name provided")

        val searchUrl = "$BASE_URL/search?q=" +
                URLEncoder.encode(anime, "UTF-8") + "&offset=0"
        val entries = fetchEntries(context, searchUrl)
        return parseBestTorrent(entries, anime)
    }

    // ---- Headless WebView fetch (replaces Playwright) ----------------------

    /**
     * Loads [url] in a WebView on the main thread, polls until `span.truncate`
     * appears (10s deadline — mirrors Python's `wait_for_selector` timeout),
     * then extracts every `div.flex-1.min-w-0` entry's (name, .torrent href)
     * and returns them. Blocks the calling (background) thread via a latch.
     *
     * WebView must be created/touched on the main thread (it requires a Looper);
     * the Tensei Main App calls ContentProvider.query() on a background thread,
     * so we hop to the main thread and block here.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun fetchEntries(context: Context, url: String): List<Pair<String, String>> {
        val latch = CountDownLatch(1)
        val result = arrayOfNulls<List<Pair<String, String>>>(1)
        val error = arrayOfNulls<Throwable>(1)
        val webViewHolder = arrayOfNulls<WebView>(1)
        val mainHandler = Handler(Looper.getMainLooper())

        mainHandler.post {
            try {
                webViewHolder[0] = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = false
                    settings.databaseEnabled = false
                    settings.cacheMode = WebSettings.LOAD_NO_CACHE
                    settings.blockNetworkImage = true
                    settings.loadsImagesAutomatically = false
                    settings.userAgentString = USER_AGENT

                    webChromeClient = android.webkit.WebChromeClient()

                    webViewClient = object : WebViewClient() {
                        // Block image/font/media — mirrors Python's route.abort()
                        // on resource_type in ["image", "font", "media"].
                        override fun shouldInterceptRequest(
                            view: WebView?, request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val u = request?.url?.toString() ?: return null
                            val path = u.substringBefore('?').lowercase()
                            if (path.endsWith(".png") || path.endsWith(".jpg") ||
                                path.endsWith(".jpeg") || path.endsWith(".gif") ||
                                path.endsWith(".webp") || path.endsWith(".svg") ||
                                path.endsWith(".ico") || path.endsWith(".bmp") ||
                                path.endsWith(".woff") || path.endsWith(".woff2") ||
                                path.endsWith(".ttf") || path.endsWith(".otf") ||
                                path.endsWith(".eot") || path.endsWith(".mp4") ||
                                path.endsWith(".webm") || path.endsWith(".mp3") ||
                                path.endsWith(".ogg") || path.endsWith(".wav")
                            ) {
                                return EMPTY_RESPONSE
                            }
                            return null
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            // Poll for span.truncate (10s), then extract entries.
                            // Mirrors: page.wait_for_selector("span.truncate", 10000)
                            // then BeautifulSoup(html).select("div.flex-1.min-w-0").
                            //
                            // Done in JS + passed back via the @JavascriptInterface
                            // bridge to bypass WebView string-size / escaping quirks.
                            val js = """
                                (function poll(start){
                                    start = start || Date.now();
                                    if (Date.now()-start > 10000){
                                        AndroidBridge.onResults('[]');
                                        return;
                                    }
                                    if (document.querySelector('span.truncate')){
                                        var out = [];
                                        document.querySelectorAll('div.flex-1.min-w-0').forEach(function(e){
                                            var n = e.querySelector('span.truncate');
                                            var t = e.querySelector('a[href*=".torrent"]');
                                            if (n && t) out.push({name:n.textContent.trim(), href:t.href});
                                        });
                                        AndroidBridge.onResults(JSON.stringify(out));
                                    } else {
                                        setTimeout(function(){ poll(start); }, 200);
                                    }
                                })();
                            """.trimIndent()
                            view?.evaluateJavascript(js, null)
                        }
                    }

                    addJavascriptInterface(object : Any() {
                        @JavascriptInterface
                        fun onResults(json: String) {
                            try {
                                val arr = JSONArray(json)
                                val list = ArrayList<Pair<String, String>>(arr.length())
                                for (i in 0 until arr.length()) {
                                    val o = arr.getJSONObject(i)
                                    list.add(o.getString("name") to o.getString("href"))
                                }
                                result[0] = list
                            } catch (e: Exception) {
                                error[0] = e
                            }
                            latch.countDown()
                        }
                    }, "AndroidBridge")
                }
                webViewHolder[0]!!.loadUrl(url)
            } catch (e: Throwable) {
                error[0] = e
                latch.countDown()
            }
        }

        // Outer safety net (20s) — the inner JS poll caps at 10s, but page
        // load itself can stall; this guarantees we never hang the provider.
        if (!latch.await(20, TimeUnit.SECONDS)) {
            cleanupWebView(mainHandler, webViewHolder[0])
            throw RuntimeException("Timed out loading tsukihime search results")
        }
        cleanupWebView(mainHandler, webViewHolder[0])
        error[0]?.let { throw it }
        return result[0] ?: emptyList()
    }

    private fun cleanupWebView(mainHandler: Handler, webView: WebView?) {
        if (webView == null) return
        mainHandler.post {
            try {
                webView.stopLoading()
                webView.removeJavascriptInterface("AndroidBridge")
                webView.destroy()
            } catch (_: Exception) {
                /* best effort */
            }
        }
    }

    // ---- Fuzzy matching (replaces rapidfuzz.fuzz.WRatio) -------------------

    /**
     * Mirror of the Python loop:
     *   for entry in entries:
     *       score = fuzz.WRatio(anime.lower(), name.lower())
     *       if score > best_score:
     *           best_score, best_torrent = score, href
     *           if score == 100: break
     *   return best_torrent
     */
    private fun parseBestTorrent(
        entries: List<Pair<String, String>>, anime: String
    ): List<String> {
        if (entries.isEmpty()) return emptyList()
        var bestScore = 0
        var bestHref: String? = null
        val animeLower = anime.lowercase()

        for ((name, href) in entries) {
            val score = wRatio(animeLower, name.lowercase())
            if (score > bestScore) {
                bestScore = score
                bestHref = href
                if (score == 100) break   // mirrors Python's early exit
            }
        }
        return bestHref?.let { listOf(it) } ?: emptyList()
    }

    private fun wRatio(a: String, b: String): Int {
        val s1 = a.trim()
        val s2 = b.trim()
        if (s1.isEmpty() && s2.isEmpty()) return 100
        if (s1.isEmpty() || s2.isEmpty()) return 0
        return maxOf(ratio(s1, s2), partialRatio(s1, s2))
    }

    private fun ratio(a: String, b: String): Int {
        val dist = levenshtein(a, b)
        val maxLen = maxOf(a.length, b.length)
        return if (maxLen == 0) 100
        else (((maxLen - dist).toDouble() / maxLen) * 100).toInt()
    }

    private fun partialRatio(a: String, b: String): Int {
        val (short, long) = if (a.length <= b.length) a to b else b to a
        if (short.isEmpty()) return if (long.isEmpty()) 100 else 0
        var best = 0
        var i = 0
        while (i + short.length <= long.length) {
            val window = long.substring(i, i + short.length)
            val dist = levenshtein(short, window)
            val score = (((short.length - dist).toDouble() / short.length) * 100).toInt()
            if (score > best) best = score
            i++
        }
        return best
    }

    private fun levenshtein(a: String, b: String): Int {
        val n = a.length
        val m = b.length
        if (n == 0) return m
        if (m == 0) return n
        val prev = IntArray(m + 1) { it }
        val curr = IntArray(m + 1)
        for (i in 1..n) {
            curr[0] = i
            for (j in 1..m) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
            }
            for (j in 0..m) prev[j] = curr[j]
        }
        return prev[m]
    }
}