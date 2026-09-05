package com.beeregg2001.komorebi.ui.epg.logic

import com.beeregg2001.komorebi.data.model.EpgChannel
import com.beeregg2001.komorebi.data.model.EpgChannelWrapper
import com.beeregg2001.komorebi.data.model.EpgProgram
import org.junit.Assert.assertEquals
import org.junit.Test

class EpgDataMergerTest {
    private fun ch(id: String) = EpgChannel(
        id = id, display_channel_id = id, network_id = 1, service_id = 1,
        transport_stream_id = 1, remocon_id = 1, channel_number = "011",
        type = "GR", name = id, jikkyo_force = null, is_subchannel = false,
        is_radiochannel = false, is_watchable = true
    )

    private fun prog(id: String, chId: String, start: String, end: String) = EpgProgram(
        id = id, channel_id = chId, network_id = 1, service_id = 1, event_id = 1,
        title = id, description = "", extended = null, detail = null,
        start_time = start, end_time = end, duration = 0, is_free = true,
        genres = null, video_type = null, audio_type = null, audio_sampling_rate = null
    )

    @Test
    fun `同じチャンネルの番組を結合し開始時刻順に並べる`() {
        val base = listOf(
            EpgChannelWrapper(ch("gr011"), listOf(
                prog("b1", "gr011", "2026-09-05T20:00:00+09:00", "2026-09-05T21:00:00+09:00")
            ))
        )
        val extra = listOf(
            EpgChannelWrapper(ch("gr011"), listOf(
                prog("a1", "gr011", "2026-08-29T20:00:00+09:00", "2026-08-29T21:00:00+09:00")
            ))
        )
        val merged = EpgDataMerger.merge(base, extra)
        assertEquals(1, merged.size)
        assertEquals(listOf("a1", "b1"), merged[0].programs.map { it.id })
    }

    @Test
    fun `同じIDの番組は重複させない`() {
        val p = prog("x", "gr011", "2026-09-05T20:00:00+09:00", "2026-09-05T21:00:00+09:00")
        val merged = EpgDataMerger.merge(
            listOf(EpgChannelWrapper(ch("gr011"), listOf(p))),
            listOf(EpgChannelWrapper(ch("gr011"), listOf(p)))
        )
        assertEquals(1, merged[0].programs.size)
    }

    @Test
    fun `追加分にしか無いチャンネルは末尾に加える`() {
        val merged = EpgDataMerger.merge(
            listOf(EpgChannelWrapper(ch("gr011"), emptyList())),
            listOf(EpgChannelWrapper(ch("gr021"), emptyList()))
        )
        assertEquals(listOf("gr011", "gr021"), merged.map { it.channel.id })
    }

    @Test
    fun `既存チャンネルの並び順は維持する`() {
        val merged = EpgDataMerger.merge(
            listOf(EpgChannelWrapper(ch("gr011"), emptyList()), EpgChannelWrapper(ch("gr021"), emptyList())),
            listOf(EpgChannelWrapper(ch("gr021"), emptyList()), EpgChannelWrapper(ch("gr011"), emptyList()))
        )
        assertEquals(listOf("gr011", "gr021"), merged.map { it.channel.id })
    }
}
