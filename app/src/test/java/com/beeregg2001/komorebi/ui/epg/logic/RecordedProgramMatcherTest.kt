package com.beeregg2001.komorebi.ui.epg.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordedProgramMatcherTest {
    private val epgStart = "2026-09-05T20:00:00+09:00"
    private val epgEnd = "2026-09-05T21:00:00+09:00"

    private fun c(id: Int, start: String, end: String) =
        RecordedProgramMatcher.Candidate(id, start, end)

    @Test
    fun `マージン付きで丸ごと録画したものが一致する`() {
        val best = RecordedProgramMatcher.pickBest(
            epgStart, epgEnd,
            listOf(c(1, "2026-09-05T19:59:30+09:00", "2026-09-05T21:00:30+09:00"))
        )
        assertEquals(1, best?.id)
    }

    @Test
    fun `番組時間の半分未満しか重ならない録画は一致しない`() {
        // 20:40〜21:30 の録画 → 重なりは20分 (< 30分)
        val best = RecordedProgramMatcher.pickBest(
            epgStart, epgEnd,
            listOf(c(1, "2026-09-05T20:40:00+09:00", "2026-09-05T21:30:00+09:00"))
        )
        assertNull(best)
    }

    @Test
    fun `半分ちょうど重なる録画は一致する`() {
        val best = RecordedProgramMatcher.pickBest(
            epgStart, epgEnd,
            listOf(c(1, "2026-09-05T20:30:00+09:00", "2026-09-05T21:30:00+09:00"))
        )
        assertEquals(1, best?.id)
    }

    @Test
    fun `複数候補は重なりが最大のものを選ぶ`() {
        val best = RecordedProgramMatcher.pickBest(
            epgStart, epgEnd,
            listOf(
                c(1, "2026-09-05T20:30:00+09:00", "2026-09-05T21:30:00+09:00"),
                c(2, "2026-09-05T19:55:00+09:00", "2026-09-05T21:05:00+09:00")
            )
        )
        assertEquals(2, best?.id)
    }

    @Test
    fun `時刻が壊れている候補は無視する`() {
        val best = RecordedProgramMatcher.pickBest(
            epgStart, epgEnd,
            listOf(c(1, "broken", "2026-09-05T21:05:00+09:00"))
        )
        assertNull(best)
    }

    @Test
    fun `候補なしはnull`() {
        assertNull(RecordedProgramMatcher.pickBest(epgStart, epgEnd, emptyList()))
    }
}

class RecordedProgramMatcherIdsTest {
    private fun prog(id: String, chId: String, start: String, end: String) =
        com.beeregg2001.komorebi.data.model.EpgProgram(
            id = id, channel_id = chId, network_id = 1, service_id = 1, event_id = 1,
            title = id, description = "", extended = null, detail = null,
            start_time = start, end_time = end, duration = 0, is_free = true,
            genres = null, video_type = null, audio_type = null, audio_sampling_rate = null
        )

    private fun rec(id: Int, chId: String, start: String, end: String) =
        RecordedProgramMatcher.RecordedRange(id, chId, start, end)

    @Test
    fun `録画が重なる番組のIDだけを返す`() {
        val programs = listOf(
            prog("p1", "gr011", "2026-09-05T20:00:00+09:00", "2026-09-05T21:00:00+09:00"),
            prog("p2", "gr011", "2026-09-05T21:00:00+09:00", "2026-09-05T22:00:00+09:00"),
            prog("p3", "gr021", "2026-09-05T20:00:00+09:00", "2026-09-05T21:00:00+09:00")
        )
        val recordings = listOf(
            rec(1, "gr011", "2026-09-05T19:59:30+09:00", "2026-09-05T21:00:30+09:00")
        )
        assertEquals(setOf("p1"), RecordedProgramMatcher.matchedProgramIds(programs, recordings))
    }

    @Test
    fun `チャンネルが違う録画は一致しない`() {
        val programs = listOf(
            prog("p1", "gr011", "2026-09-05T20:00:00+09:00", "2026-09-05T21:00:00+09:00")
        )
        val recordings = listOf(
            rec(1, "gr021", "2026-09-05T20:00:00+09:00", "2026-09-05T21:00:00+09:00")
        )
        assertEquals(emptySet<String>(), RecordedProgramMatcher.matchedProgramIds(programs, recordings))
    }
}
