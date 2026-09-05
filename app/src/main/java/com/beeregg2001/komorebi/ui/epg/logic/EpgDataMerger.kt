package com.beeregg2001.komorebi.ui.epg.logic

import com.beeregg2001.komorebi.data.model.EpgChannelWrapper
import com.beeregg2001.komorebi.data.model.EpgProgram

/**
 * 追加取得した番組表データ (過去分など) を既存データに結合する。
 * チャンネルの並び順は既存側を維持し、番組は ID で重複排除して開始時刻順に並べる。
 */
object EpgDataMerger {
    fun merge(base: List<EpgChannelWrapper>, extra: List<EpgChannelWrapper>): List<EpgChannelWrapper> {
        val extraByChannel = extra.associateBy { it.channel.id }
        val merged = base.map { wrapper ->
            val add = extraByChannel[wrapper.channel.id] ?: return@map wrapper
            wrapper.copy(programs = mergePrograms(wrapper.programs, add.programs))
        }
        val baseIds = base.map { it.channel.id }.toSet()
        val newChannels = extra.filter { it.channel.id !in baseIds }
            .map { it.copy(programs = mergePrograms(emptyList(), it.programs)) }
        return merged + newChannels
    }

    private fun mergePrograms(a: List<EpgProgram>, b: List<EpgProgram>): List<EpgProgram> =
        (a + b).distinctBy { it.id }.sortedBy { it.start_time }
}
