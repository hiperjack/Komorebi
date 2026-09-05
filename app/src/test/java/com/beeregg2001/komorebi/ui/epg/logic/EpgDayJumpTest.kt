package com.beeregg2001.komorebi.ui.epg.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.OffsetDateTime

class EpgDayJumpTest {
    private val jst = java.time.ZoneOffset.ofHours(9)
    private fun t(s: String) = OffsetDateTime.parse(s)

    // 読み込み済み範囲: 8/29 00:00 〜 9/12 00:00 (今日=9/5)
    private val earliest = t("2026-08-29T00:00:00+09:00")
    private val latest = t("2026-09-12T00:00:00+09:00")

    @Test
    fun `翌日ジャンプは同じ時刻の1日後を返す`() {
        val target = EpgDayJump.target(t("2026-09-05T20:30:00+09:00"), +1, earliest, latest)
        assertEquals(t("2026-09-06T20:30:00+09:00"), target)
    }

    @Test
    fun `前日ジャンプは同じ時刻の1日前を返す`() {
        val target = EpgDayJump.target(t("2026-09-05T20:30:00+09:00"), -1, earliest, latest)
        assertEquals(t("2026-09-04T20:30:00+09:00"), target)
    }

    @Test
    fun `翌日が最終日を超える場合はnull`() {
        assertNull(EpgDayJump.target(t("2026-09-11T20:30:00+09:00"), +1, earliest, latest))
    }

    @Test
    fun `前日が最古日より前なら範囲外としてnull`() {
        assertNull(EpgDayJump.target(t("2026-08-29T05:00:00+09:00"), -1, earliest, latest))
    }

    @Test
    fun `前日が最古日ちょうどなら範囲内`() {
        val target = EpgDayJump.target(t("2026-08-30T05:00:00+09:00"), -1, earliest, latest)
        assertEquals(t("2026-08-29T05:00:00+09:00"), target)
    }
}

class EpgDayJumpRangeTest {
    private fun t(s: String) = OffsetDateTime.parse(s)
    private val earliest = t("2026-08-29T00:00:00+09:00")
    private val latest = t("2026-09-12T00:00:00+09:00")

    @Test
    fun `範囲内の時刻はtrue`() {
        assertEquals(true, EpgDayJump.isWithinRange(t("2026-09-05T20:00:00+09:00"), earliest, latest))
        assertEquals(true, EpgDayJump.isWithinRange(earliest, earliest, latest))
    }

    @Test
    fun `最古日より前と最終日以降はfalse`() {
        assertEquals(false, EpgDayJump.isWithinRange(t("2026-08-28T23:59:00+09:00"), earliest, latest))
        assertEquals(false, EpgDayJump.isWithinRange(latest, earliest, latest))
    }
}
