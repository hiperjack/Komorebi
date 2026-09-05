package com.beeregg2001.komorebi.ui.epg.logic

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * 時間割ジャンプ (日付 × 6時間区切りの4枠) の枠計算。
 */
object EpgTimeSlots {
    const val SLOT_HOURS = 6
    const val SLOTS_PER_DAY = 24 / SLOT_HOURS

    /** [earliest, latest] に含まれる日付を古い順に返す。latest が 0:00 ちょうどならその日は含めない。 */
    fun dates(earliest: OffsetDateTime, latest: OffsetDateTime): List<LocalDate> {
        val first = earliest.toLocalDate()
        val lastExclusive = latest.toLocalDate().let {
            if (latest.toLocalTime() == java.time.LocalTime.MIDNIGHT) it else it.plusDays(1)
        }
        val result = mutableListOf<LocalDate>()
        var d = first
        while (d.isBefore(lastExclusive)) {
            result.add(d)
            d = d.plusDays(1)
        }
        return result
    }

    fun slotStart(date: LocalDate, slotIndex: Int, offset: ZoneOffset): OffsetDateTime =
        date.atTime(slotIndex * SLOT_HOURS, 0).atOffset(offset)

    fun slotIndexOf(time: OffsetDateTime): Int = time.hour / SLOT_HOURS

    fun slotLabel(slotIndex: Int): String =
        "${slotIndex * SLOT_HOURS}-${(slotIndex + 1) * SLOT_HOURS}"
}
