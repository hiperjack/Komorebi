package com.beeregg2001.komorebi.ui.epg.logic

import java.time.OffsetDateTime

/**
 * 番組表の日ジャンプ (▶▶/◀◀、上下キーでの日またぎ) に使う範囲判定の純粋ロジック。
 * 読み込み済みデータ範囲は [earliest, latest) として扱う。
 */
object EpgDayJump {
    fun isWithinRange(target: OffsetDateTime, earliest: OffsetDateTime, latest: OffsetDateTime): Boolean =
        !target.isBefore(earliest) && target.isBefore(latest)

    /** 同じ時刻の ±N 日を返し、読み込み済み範囲を外れる場合は null。 */
    fun target(
        current: OffsetDateTime,
        deltaDays: Long,
        earliest: OffsetDateTime,
        latest: OffsetDateTime
    ): OffsetDateTime? {
        val target = current.plusDays(deltaDays)
        return if (isWithinRange(target, earliest, latest)) target else null
    }
}
