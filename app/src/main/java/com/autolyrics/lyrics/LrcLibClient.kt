package com.autolyrics.lyrics

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object LrcLibClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private const val BASE_URL = "https://lrclib.net/api"
    private const val USER_AGENT = "AutoLyrics v1.0 (https://github.com/user/auto-lyrics)"
    private const val AUTO_MATCH_THRESHOLD = 0.85
    private const val MIN_PICKER_SCORE = 0.35
    private const val MAX_CANDIDATES = 30

    data class LrcLibResponse(
        @SerializedName("id") val id: Int?,
        @SerializedName("trackName") val trackName: String?,
        @SerializedName("artistName") val artistName: String?,
        @SerializedName("albumName") val albumName: String?,
        @SerializedName("duration") val duration: Int?,
        @SerializedName("instrumental") val instrumental: Boolean?,
        @SerializedName("plainLyrics") val plainLyrics: String?,
        @SerializedName("syncedLyrics") val syncedLyrics: String?
    )

    fun getLyrics(
        trackName: String,
        artistName: String,
        albumName: String,
        durationSec: Int
    ): LrcLibResponse? {
        // Priority 1: exact match with synced lyrics
        if (durationSec > 0 && albumName.isNotBlank()) {
            val exact = getExact(trackName, artistName, albumName, durationSec)
            if (exact?.syncedLyrics != null) return exact
        }

        // Priority 2: search for synced lyrics (duration-guarded)
        val searchResults = searchAll(trackName, artistName)
        val syncedResult = searchResults.firstOrNull {
            it.syncedLyrics != null && withinDuration(it, durationSec)
        }
        if (syncedResult != null) return syncedResult

        // Priority 3: exact match without album for synced lyrics
        if (durationSec > 0) {
            val exactNoAlbum = getExact(trackName, artistName, "", durationSec)
            if (exactNoAlbum?.syncedLyrics != null) return exactNoAlbum
        }

        // Priority 3b: alternative spellings (feat. tags, joined artists, swapped fields).
        // Computed once, only when the steps above found nothing.
        val altCandidates by lazy { alternativeCandidates(trackName, artistName, durationSec) }
        altCandidates.firstOrNull { !it.response.syncedLyrics.isNullOrBlank() }
            ?.let { return it.response }

        // Priority 4: exact match with any lyrics (including plain)
        if (durationSec > 0 && albumName.isNotBlank()) {
            val exact = getExact(trackName, artistName, albumName, durationSec)
            if (exact?.plainLyrics != null) return exact
        }

        // Priority 5: search result with plain lyrics (duration-guarded)
        val plainResult = searchResults.firstOrNull {
            it.plainLyrics != null && withinDuration(it, durationSec)
        }
        if (plainResult != null) return plainResult

        // Fallback: plain lyrics from the alternative spellings tried above
        altCandidates.firstOrNull { !it.response.plainLyrics.isNullOrBlank() }
            ?.let { return it.response }

        return null
    }

    /**
     * Retries the search with cleaned-up spellings (no "feat." / remaster suffix, first artist only)
     * and a free-text query, keeping only results that are a confident match for the played track.
     */
    private fun alternativeCandidates(
        trackName: String,
        artistName: String,
        durationSec: Int
    ): List<TrackMatcher.Candidate> {
        val altTitle = TrackMatcher.stripFeat(trackName)
        val altArtist = TrackMatcher.firstArtist(artistName)
        val tried = mutableSetOf(trackName to artistName)
        val found = LinkedHashMap<Int, LrcLibResponse>()

        val variants = listOf(
            altTitle to artistName,
            trackName to altArtist,
            altTitle to altArtist
        )
        for ((t, a) in variants) {
            if (!tried.add(t to a)) continue
            for (r in searchAll(t, a)) {
                val id = r.id ?: continue
                found.putIfAbsent(id, r)
            }
        }
        for (r in searchFree("$altArtist $altTitle")) {
            val id = r.id ?: continue
            found.putIfAbsent(id, r)
        }

        return TrackMatcher.rank(trackName, artistName, durationSec, found.values)
            .filter { it.score >= AUTO_MATCH_THRESHOLD && withinDuration(it.response, durationSec) }
    }

    /**
     * Candidate lyrics for the manual picker: several searches merged, best match first.
     * Network call; run off the main thread.
     */
    fun searchCandidates(
        trackName: String,
        artistName: String,
        durationSec: Int
    ): List<TrackMatcher.Candidate> {
        val title = TrackMatcher.stripFeat(trackName)
        val artist = TrackMatcher.firstArtist(artistName)
        val found = LinkedHashMap<Int, LrcLibResponse>()

        fun addAll(list: List<LrcLibResponse>) {
            for (r in list) {
                val id = r.id ?: continue
                found.putIfAbsent(id, r)
            }
        }

        val freeQueries = linkedSetOf(
            "$artistName $trackName".trim(),
            "$artist $title".trim(),
            title
        )
        for (q in freeQueries) addAll(searchFree(q))
        addAll(searchAll(trackName, artistName))
        if (title != trackName || artist != artistName) addAll(searchAll(title, artist))

        return TrackMatcher.rank(trackName, artistName, durationSec, found.values)
            .filter { it.score >= MIN_PICKER_SCORE }
            .take(MAX_CANDIDATES)
    }

    private fun searchFree(query: String): List<LrcLibResponse> {
        if (query.isBlank()) return emptyList()
        val urlBuilder = "$BASE_URL/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query.trim())

        val request = Request.Builder()
            .url(urlBuilder.build())
            .header("User-Agent", USER_AGENT)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val type = object : TypeToken<List<LrcLibResponse>>() {}.type
                    gson.fromJson(response.body?.string(), type) ?: emptyList()
                } else emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun withinDuration(result: LrcLibResponse, durationSec: Int): Boolean {
        if (durationSec <= 0 || result.duration == null) return true
        return kotlin.math.abs(result.duration - durationSec) <= 10
    }

    private fun getExact(
        trackName: String,
        artistName: String,
        albumName: String,
        durationSec: Int
    ): LrcLibResponse? {
        val urlBuilder = "$BASE_URL/get".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", trackName)
            .addQueryParameter("artist_name", artistName)
            .addQueryParameter("album_name", albumName)
            .addQueryParameter("duration", durationSec.toString())

        val request = Request.Builder()
            .url(urlBuilder.build())
            .header("User-Agent", USER_AGENT)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    gson.fromJson(response.body?.string(), LrcLibResponse::class.java)
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun searchAll(trackName: String, artistName: String): List<LrcLibResponse> {
        val urlBuilder = "$BASE_URL/search".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", trackName)

        if (artistName.isNotBlank()) {
            urlBuilder.addQueryParameter("artist_name", artistName)
        }

        val request = Request.Builder()
            .url(urlBuilder.build())
            .header("User-Agent", USER_AGENT)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val type = object : TypeToken<List<LrcLibResponse>>() {}.type
                    gson.fromJson(response.body?.string(), type) ?: emptyList()
                } else emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
