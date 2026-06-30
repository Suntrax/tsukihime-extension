# Tensei: Tsukihime

A headless background scraper extension for the **Tensei Scraper** app. This extension scrapes `tsukihime.org` to find the best-matching torrent release for a given anime and returns its `.torrent` file URL.

## 📖 How it Works

1. **Discovery:** The Main App (Tensei Scraper) finds this extension via the `EXTENSION_BEACON` receiver.
2. **Query:** When you search an anime, the Main App passes the English or Romaji anime name (plus the AniList ID) to this extension's `ContentProvider`.
3. **Scraping:**
- **Step 1 (WebView):** Because tsukihime.org renders its search results client-side via JavaScript, the extension uses Android's built-in `WebView` to load `https://tsukihime.org/search?q=<anime>&offset=0`. Image, font, and media requests are blocked to cut load times (mirroring the Python original's Playwright route-abort).
- **Step 2 (Poll):** It actively polls the DOM every 200ms until a `span.truncate` element appears (10s timeout), exactly like Playwright's `wait_for_selector`.
- **Step 3 (Extract):** Once loaded, it injects JavaScript to traverse `div.flex-1.min-w-0` entries, pulling each release's name (`span.truncate`) and `.torrent` file URL (`a[href*=".torrent"]`). To bypass Android's `WebView` string-size limits, the data is passed back to Kotlin using a `JavascriptInterface` bridge.
- **Step 4 (Match):** Each release name is fuzzy-matched against the query using a Levenshtein-based approximation of `rapidfuzz`'s WRatio (`max(ratio, partialRatio)`). The `.torrent` URL of the highest-scoring release is kept.
4. **Return:** The best-matching `.torrent` URL is packaged into a single-element JSON array and sent back to the Main App.

## 🛠️ Technical Details

- **Dependencies:** Zero. Uses only built-in Android APIs (`WebView`, `org.json`).
- **APK Size:** ~40KB (Heavily shrunk via R8/ProGuard).
- **Data Format Returned:** Flat Torrent URL List (`["https://tsukihime.org/….torrent"]`)

## 🏗️ Building

1. Place your release keystore at `app/release.jks` and update the passwords in `app/build.gradle.kts`.
2. Run `./gradlew assembleRelease` to build a shrunk, signed APK.
3. Install the APK on your device alongside the main Tensei Scraper app.