package com.beeregg2001.komorebi.ui.epg.logic

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.OffsetDateTime

class EpgTimeSlotsTest {
    private fun t(s: String) = OffsetDateTime.parse(s)

    @Test
    fun `日付一覧は最古日から最終日の前日までを日単位で返す`() {
        val dates = EpgTimeSlots.dates(
            earliest = t("2026-08-29T00:00:00+09:00"),
            latest = t("2026-09-12T00:00:00+09:00")
        )
        assertEquals(LocalDate.of(2026, 8, 29), dates.first())
        assertEquals(LocalDate.of(2026, 9, 11), dates.last())
        assertEquals(14, dates.size)
    }

    @Test
    fun `最終日が日の途中でもその日を含める`() {
        val dates = EpgTimeSlots.dates(
            earliest = t("2026-09-05T00:00:00+09:00"),
            latest = t("2026-09-12T23:00:00+09:00")
        )
        assertEquals(LocalDate.of(2026, 9, 12), dates.last())
    }

    @Test
    fun `枠の開始時刻は6時間区切り`() {
        val date = LocalDate.of(2026, 9, 5)
        val zone = java.time.ZoneOffset.ofHours(9)
        assertEquals(t("2026-09-05T00:00:00+09:00"), EpgTimeSlots.slotStart(date, 0, zone))
        assertEquals(t("2026-09-05T06:00:00+09:00"), EpgTimeSlots.slotStart(date, 1, zone))
        assertEquals(t("2026-09-05T12:00:00+09:00"), EpgTimeSlots.slotStart(date, 2, zone))
        assertEquals(t("2026-09-05T18:00:00+09:00"), EpgTimeSlots.slotStart(date, 3, zone))
    }

    @Test
    fun `時刻から枠番号を求める`() {
        assertEquals(0, EpgTimeSlots.slotIndexOf(t("2026-09-05T05:59:00+09:00")))
        assertEquals(1, EpgTimeSlots.slotIndexOf(t("2026-09-05T06:00:00+09:00")))
        assertEquals(3, EpgTimeSlots.slotIndexOf(t("2026-09-05T23:30:00+09:00")))
    }

    @Test
    fun `枠ラベルは24時間表記`() {
        assertEquals("0-6", EpgTimeSlots.slotLabel(0))
        assertEquals("18-24", EpgTimeSlots.slotLabel(3))
    }
}
