package com.beeregg2001.komorebi.ui.epg.logic

import com.beeregg2001.komorebi.data.model.EpgProgram
import java.time.Duration
import java.time.OffsetDateTime

/**
 * 番組表の番組 (放送時間) に対応する録画を、放送時間の重なりで選ぶ。
 * 録画マージンや延長を考慮し、番組時間の半分以上重なるものを一致とみなす。
 */
object RecordedProgramMatcher {
    data class Candidate(val id: Int, val startTime: String, val endTime: String)

    /** 録画1件の「どのチャンネルで、いつからいつまで」 (番組表に録画済み枠を描くための軽量データ) */
    data class RecordedRange(val id: Int, val channelId: String, val startTime: String, val endTime: String)

    /** 番組表の番組群のうち、対応する録画がある番組の ID 集合を返す。 */
    fun matchedProgramIds(
        programs: List<EpgProgram>,
        recordings: List<RecordedRange>
    ): Set<String> {
        if (programs.isEmpty() || recordings.isEmpty()) return emptySet()
        val byChannel = recordings.groupBy { it.channelId }
        val result = HashSet<String>()
        for (p in programs) {
            val candidates = byChannel[p.channel_id] ?: continue
            val best = pickBest(
                p.start_time, p.end_time,
                candidates.map { Candidate(it.id, it.startTime, it.endTime) }
            )
            if (best != null) result.add(p.id)
        }
        return result
    }

    fun pickBest(epgStart: String, epgEnd: String, candidates: List<Candidate>): Candidate? {
        val start = parse(epgStart) ?: return null
        val end = parse(epgEnd) ?: return null
        val programSec = Duration.between(start, end).seconds
        if (programSec <= 0) return null

        var best: Candidate? = null
        var bestOverlap = 0L
        for (c in candidates) {
            val cs = parse(c.startTime) ?: continue
            val ce = parse(c.endTime) ?: continue
            val overlapStart = if (cs.isAfter(start)) cs else start
            val overlapEnd = if (ce.isBefore(end)) ce else end
            val overlap = Duration.between(overlapStart, overlapEnd).seconds
            if (overlap * 2 < programSec) continue
            if (overlap > bestOverlap) {
                bestOverlap = overlap
                best = c
            }
        }
        return best
    }

    private fun parse(s: String): OffsetDateTime? =
        try { OffsetDateTime.parse(s) } catch (e: Exception) { null }
}
