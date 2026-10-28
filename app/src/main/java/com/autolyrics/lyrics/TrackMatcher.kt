package com.autolyrics.lyrics

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Helpers for matching the track reported by the music player against LRCLIB entries
 * whose title / artist formatting differs (feat. tags, joined artists, swapped fields, etc).
 */
object TrackMatcher {

    data class Candidate(
        val response: LrcLibClient.LrcLibResponse,
        val score: Double
    ) {
        val percent: Int get() = (score * 100).toInt().coerceIn(0, 100)
    }

    private val FEAT_BRACKET = Regex(
        """\s*[(\[]\s*(feat|ft|featuring)\b[^)\]]*[)\]]""",
        RegexOption.IGNORE_CASE
    )
    private val FEAT_TRAILING = Regex(
        """\s+(feat|ft|featuring)\.?\s+.*$""",
        RegexOption.IGNORE_CASE
    )
    private val VERSION_SUFFIX = Regex(
        """\s+[-–—]\s+(\d{4}\s+)?(remaster(ed)?|radio edit|single version|album version|mono|stereo)\b.*$""",
        RegexOption.IGNORE_CASE
    )
    private val ARTIST_SPLIT = Regex(
        """\s*(?:,|;|\s&\s|\sfeat\.?\s|\sft\.?\s|\sfeaturing\s)\s*""",
        RegexOption.IGNORE_CASE
    )
    private val MARKS = Regex("""\p{M}+""")
    private val NON_ALNUM = Regex("""[^\p{L}\p{N}]+""")

    /** Removes "(feat. X)", trailing "feat. X" and "- Remastered 2011" style suffixes. */
    fun stripFeat(title: String): String {
        var s = title.replace(FEAT_BRACKET, "")
        s = s.replace(FEAT_TRAILING, "")
        s = s.replace(VERSION_SUFFIX, "")
        s = s.trim()
        return s.ifBlank { title.trim() }
    }

    /** First artist of a joined list such as "A, B", "A & B" or "A feat. B". */
    fun firstArtist(artist: String): String {
        val first = artist.split(ARTIST_SPLIT).firstOrNull()?.trim().orEmpty()
        return first.ifBlank { artist.trim() }
    }

    fun normalize(s: String): String {
        val decomposed = Normalizer.normalize(s, Normalizer.Form.NFD).replace(MARKS, "")
        return decomposed.lowercase().replace(NON_ALNUM, " ").trim()
    }

    private fun tokens(s: String): Set<String> =
        normalize(s).split(' ').filter { it.isNotEmpty() }.toSet()

    private fun bigrams(s: String): Map<String, Int> {
        val m = HashMap<String, Int>()
        if (s.length < 2) {
            if (s.isNotEmpty()) m[s] = 1
            return m
        }
        for (i in 0 until s.length - 1) {
            val g = s.substring(i, i + 2)
            m[g] = (m[g] ?: 0) + 1
        }
        return m
    }

    private fun dice(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val ma = bigrams(a)
        val mb = bigrams(b)
        var overlap = 0
        for ((g, c) in ma) {
            overlap += min(c, mb[g] ?: 0)
        }
        val total = ma.values.sum() + mb.values.sum()
        return if (total == 0) 0.0 else 2.0 * overlap / total
    }

    /** Similarity of two strings in 0..1 after normalisation. */
    fun textSim(a: String, b: String): Double {
        val na = normalize(a)
        val nb = normalize(b)
        if (na.isEmpty() || nb.isEmpty()) return 0.0
        if (na == nb) return 1.0
        val ta = na.split(' ').toSet()
        val tb = nb.split(' ').toSet()
        val inter = ta.count { it in tb }
        val containment = inter.toDouble() / min(ta.size, tb.size)
        return max(dice(na, nb), 0.9 * containment)
    }

    private fun artistSim(query: String, cand: String): Double {
        if (normalize(query).isEmpty()) return 0.5
        val tq = tokens(query)
        val tc = tokens(cand)
        if (tq.isEmpty() || tc.isEmpty()) return 0.0
        val inter = tq.count { it in tc }
        val contQ = inter.toDouble() / tq.size
        val contC = inter.toDouble() / tc.size
        return max(textSim(query, cand), 0.95 * max(contQ, contC))
    }

    /** Score in 0..1: how likely [cand] is the same recording as the track being played. */
    fun score(
        trackName: String,
        artistName: String,
        durationSec: Int,
        cand: LrcLibClient.LrcLibResponse
    ): Double {
        val candTrack = cand.trackName ?: ""
        val candArtist = cand.artistName ?: ""
        val qTitle = stripFeat(trackName)

        val straight = 0.65 * textSim(qTitle, stripFeat(candTrack)) +
            0.35 * artistSim(artistName, candArtist)
        // Some sources put the artist in the title field and the title in the artist field.
        val swapped = 0.9 * (0.65 * textSim(qTitle, stripFeat(candArtist)) +
            0.35 * artistSim(artistName, candTrack))
        val text = max(straight, swapped)

        val candDur = cand.duration
        val dur = if (durationSec > 0 && candDur != null && candDur > 0) {
            max(0.0, 1.0 - min(abs(candDur - durationSec), 30) / 30.0)
        } else 0.5

        var total = 0.8 * text + 0.2 * dur
        if (!cand.syncedLyrics.isNullOrBlank()) total += 0.03
        return min(total, 1.0)
    }

    /** Candidates that have lyrics, best match first. */
    fun rank(
        trackName: String,
        artistName: String,
        durationSec: Int,
        list: Collection<LrcLibClient.LrcLibResponse>
    ): List<Candidate> {
        return list
            .filter { !it.syncedLyrics.isNullOrBlank() || !it.plainLyrics.isNullOrBlank() }
            .map { Candidate(it, score(trackName, artistName, durationSec, it)) }
            .sortedByDescending { it.score }
    }
}
