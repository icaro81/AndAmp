package com.example.data.repository

import android.net.Uri
import com.example.data.local.SavedYouTubePlaylistEntity
import com.example.data.local.YouTubePlaylistDao
import com.example.domain.model.YouTubePlaylistSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class YouTubeRepository(
    private val youTubePlaylistDao: YouTubePlaylistDao
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    val recentPlaylistsFlow: Flow<List<YouTubePlaylistSummary>> =
        youTubePlaylistDao.observeRecentPlaylists().map { list ->
            list.map { it.toDomain() }
        }

    suspend fun saveRecentPlaylist(summary: YouTubePlaylistSummary) = withContext(Dispatchers.IO) {
        youTubePlaylistDao.upsertPlaylist(SavedYouTubePlaylistEntity.fromDomain(summary))
    }

    suspend fun deleteRecentPlaylist(playlistId: String) = withContext(Dispatchers.IO) {
        youTubePlaylistDao.deletePlaylist(playlistId)
    }

    suspend fun searchPlaylists(query: String): Result<List<YouTubePlaylistSummary>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val trimmed = query.trim()
                if (trimmed.isEmpty()) return@runCatching emptyList()

                // 1. Direct URL or Playlist ID detection
                extractPlaylistId(trimmed)?.let { directId ->
                    val resolved = fetchPlaylistOEmbed(directId) ?: YouTubePlaylistSummary(
                        playlistId = directId,
                        title = "Playlist de YouTube ($directId)",
                        channelTitle = "YouTube Remoto",
                        videoCountText = "Lista directa"
                    )
                    return@runCatching listOf(resolved)
                }

                // 2. YouTube public search filtered to playlists (sp=EgIQAw==)
                val encoded = URLEncoder.encode(trimmed, "UTF-8")
                val url = "https://www.youtube.com/results?search_query=$encoded&sp=EgIQAw%253D%253D"
                val request = Request.Builder()
                    .url(url)
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
                    )
                    .header("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
                    .build()

                val html = httpClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) error("Error HTTP ${resp.code} al buscar en YouTube")
                    resp.body?.string().orEmpty()
                }

                val jsonStr = extractYtInitialDataJson(html)
                    ?: error("No se pudieron interpretar los resultados de YouTube")
                val root = JSONObject(jsonStr)
                val results = LinkedHashMap<String, YouTubePlaylistSummary>()
                collectPlaylistsFromJson(root, results)
                results.values.toList().take(25)
            }
        }

    private fun fetchPlaylistOEmbed(playlistId: String): YouTubePlaylistSummary? {
        return runCatching {
            val playlistUrl = URLEncoder.encode("https://www.youtube.com/playlist?list=$playlistId", "UTF-8")
            val req = Request.Builder()
                .url("https://www.youtube.com/oembed?url=$playlistUrl&format=json")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string() ?: return@use null
                val json = JSONObject(body)
                YouTubePlaylistSummary(
                    playlistId = playlistId,
                    title = json.optString("title").ifBlank { "Playlist $playlistId" },
                    channelTitle = json.optString("author_name").ifBlank { "YouTube" },
                    videoCountText = "Playlist",
                    thumbnailUrl = json.optString("thumbnail_url")
                )
            }
        }.getOrNull()
    }

    private fun extractYtInitialDataJson(html: String): String? {
        val markers = listOf("var ytInitialData = ", "window[\"ytInitialData\"] = ")
        for (marker in markers) {
            val startIdx = html.indexOf(marker)
            if (startIdx >= 0) {
                val jsonStart = html.indexOf('{', startIdx + marker.length)
                if (jsonStart >= 0) {
                    var depth = 0
                    var inString = false
                    var escaped = false
                    for (i in jsonStart until html.length) {
                        val ch = html[i]
                        if (escaped) {
                            escaped = false
                            continue
                        }
                        if (ch == '\\' && inString) {
                            escaped = true
                            continue
                        }
                        if (ch == '"') {
                            inString = !inString
                            continue
                        }
                        if (!inString) {
                            if (ch == '{') depth++
                            else if (ch == '}') {
                                depth--
                                if (depth == 0) {
                                    return html.substring(jsonStart, i + 1)
                                }
                            }
                        }
                    }
                }
            }
        }
        return null
    }

    private fun collectPlaylistsFromJson(
        node: Any?,
        out: MutableMap<String, YouTubePlaylistSummary>
    ) {
        if (out.size >= 25) return
        when (node) {
            is JSONObject -> {
                if (node.has("playlistRenderer")) {
                    parsePlaylistRenderer(node.getJSONObject("playlistRenderer"))?.let {
                        out.putIfAbsent(it.playlistId, it)
                    }
                }
                if (node.has("lockupViewModel")) {
                    parseLockupViewModel(node.getJSONObject("lockupViewModel"))?.let {
                        out.putIfAbsent(it.playlistId, it)
                    }
                }
                val keys = node.keys()
                while (keys.hasNext() && out.size < 25) {
                    val key = keys.next()
                    collectPlaylistsFromJson(node.opt(key), out)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    if (out.size >= 25) break
                    collectPlaylistsFromJson(node.opt(i), out)
                }
            }
        }
    }

    private fun parsePlaylistRenderer(obj: JSONObject): YouTubePlaylistSummary? {
        val id = obj.optString("playlistId")
        if (id.isBlank()) return null
        val title = readTextField(obj.optJSONObject("title")) ?: return null
        val channel = readTextField(obj.optJSONObject("longBylineText"))
            ?: readTextField(obj.optJSONObject("shortBylineText"))
            ?: "YouTube"
        val count = obj.optString("videoCount").takeIf { it.isNotBlank() }?.let { "$it videos" }
            ?: readTextField(obj.optJSONObject("videoCountText"))
            ?: ""
        val thumb = obj.optJSONArray("thumbnails")
            ?.optJSONObject(0)
            ?.optJSONArray("thumbnails")
            ?.optJSONObject(0)
            ?.optString("url")
            .orEmpty()
        return YouTubePlaylistSummary(
            playlistId = id,
            title = title,
            channelTitle = channel,
            videoCountText = count,
            thumbnailUrl = thumb
        )
    }

    private fun parseLockupViewModel(obj: JSONObject): YouTubePlaylistSummary? {
        val contentType = obj.optString("contentType")
        if (contentType.isNotBlank() && !contentType.contains("PLAYLIST", ignoreCase = true)) {
            return null
        }
        val id = obj.optString("contentId")
        if (id.isBlank() || (!id.startsWith("PL") && !id.startsWith("OLAK") && !id.startsWith("RD"))) {
            return null
        }
        val meta = obj.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
        val title = meta?.optJSONObject("title")?.optString("content")?.takeIf { it.isNotBlank() }
            ?: return null
        val rows = meta.optJSONObject("metadata")
            ?.optJSONObject("contentMetadataViewModel")
            ?.optJSONArray("metadataRows")
        val channel = rows?.optJSONObject(0)
            ?.optJSONArray("metadataParts")
            ?.optJSONObject(0)
            ?.optJSONObject("text")
            ?.optString("content")
            ?.takeIf { it.isNotBlank() }
            ?: "YouTube"
        val thumb = obj.optJSONObject("contentImage")
            ?.optJSONObject("collectionThumbnailViewModel")
            ?.optJSONObject("primaryThumbnail")
            ?.optJSONObject("thumbnailViewModel")
            ?.optJSONObject("image")
            ?.optJSONArray("sources")
            ?.optJSONObject(0)
            ?.optString("url")
            .orEmpty()
        return YouTubePlaylistSummary(
            playlistId = id,
            title = title,
            channelTitle = channel,
            videoCountText = "Playlist",
            thumbnailUrl = thumb
        )
    }

    private fun readTextField(obj: JSONObject?): String? {
        if (obj == null) return null
        val simple = obj.optString("simpleText")
        if (simple.isNotBlank()) return simple
        val runs = obj.optJSONArray("runs") ?: return null
        val sb = StringBuilder()
        for (i in 0 until runs.length()) {
            sb.append(runs.optJSONObject(i)?.optString("text").orEmpty())
        }
        return sb.toString().takeIf { it.isNotBlank() }
    }

    companion object {
        fun extractPlaylistId(input: String): String? {
            val trimmed = input.trim()
            if (trimmed.contains("youtube.com", ignoreCase = true) ||
                trimmed.contains("youtu.be", ignoreCase = true)
            ) {
                return runCatching { Uri.parse(trimmed).getQueryParameter("list") }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
            }
            if ((trimmed.startsWith("PL") || trimmed.startsWith("OLAK5uy_") || trimmed.startsWith("RD")) &&
                !trimmed.contains(" ") && trimmed.length >= 12
            ) {
                return trimmed
            }
            return null
        }
    }
}
