package com.blissless.tsukihime

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * ContentProvider queried by the Tensei Main App.
 *
 * Query URI:
 *   content://com.blissless.tsukihime.provider/scrape
 *     ?anime=<english-title>&anilistId=<id>&animeRomaji=<romaji-title>&category=sub
 *
 * The `animeRomaji` query parameter was added so the scraper can match
 * against the API's `anime.title` (romaji) field in addition to the
 * `anime.english_title` field. This is what makes the exact-match strategy
 * reliable: AniList's romaji title usually matches tsukihime's romaji title
 * verbatim, even when the English titles differ slightly between sources.
 *
 * `category` is currently ignored by the scraper (tsukihime's API doesn't
 * expose a category filter), but it's accepted for forward compatibility.
 *
 * Returns a single-row MatrixCursor whose "data" column holds a JSON string:
 *   - 1 magnet -> ["magnet:..."]                  (the exact-matching torrent)
 *   - 0 magnets -> {"error":"No results found."}  (no exact title match)
 *   - failure  -> {"error":"Scraping failed: ..."}
 */
class ScraperProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.blissless.tsukihime.provider"
        const val PATH_SCRAPE = "scrape"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/$PATH_SCRAPE")
        private const val CODE_SCRAPES = 1
    }

    private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
        addURI(AUTHORITY, PATH_SCRAPE, CODE_SCRAPES)
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? {
        if (uriMatcher.match(uri) != CODE_SCRAPES) return null

        val animeName    = uri.getQueryParameter("anime")
        val anilistId    = uri.getQueryParameter("anilistId")
        val animeRomaji  = uri.getQueryParameter("animeRomaji")
        // val category   = uri.getQueryParameter("category")  // unused, accepted for compat
        val cursor = MatrixCursor(arrayOf("data"))

        val json: String = try {
            val result = TsukihimeScraper.scrape(context!!, animeName, anilistId, animeRomaji)
            serialize(result)
        } catch (e: Exception) {
            val msg = e.message?.replace("\\", "\\\\")?.replace("\"", "\\\"") ?: "Unknown error"
            "{\"error\":\"Scraping failed: $msg\"}"
        }

        cursor.addRow(arrayOf(json))
        return cursor
    }

    /**
     * Turn the scraper's `Any` return value into the JSON the Main App expects.
     * Each branch binds to an explicitly-typed local so the compiler never has
     * to smart-cast `Any`.
     */
    private fun serialize(result: Any): String {
        when (result) {
            is List<*> -> {
                val list: List<*> = result
                return if (list.isEmpty()) {
                    "{\"error\":\"No results found.\"}"
                } else {
                    val arr = JSONArray()
                    for (item in list) arr.put(item.toString())
                    arr.toString()
                }
            }
            is Map<*, *> -> {
                val map: Map<*, *> = result
                return if (map.isEmpty()) {
                    "{\"error\":\"No results found.\"}"
                } else {
                    val obj = JSONObject()
                    for ((key, value) in map) {
                        @Suppress("UNCHECKED_CAST")
                        obj.put(key.toString(), JSONObject(value as Map<String, Any>))
                    }
                    obj.toString()
                }
            }
        }
        return "{\"error\":\"Unexpected scraper result: ${result::class.java.simpleName}\"}"
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int = 0
}
