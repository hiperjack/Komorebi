package com.beeregg2001.komorebi.viewmodel

import android.content.Context
import android.os.Build
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import com.beeregg2001.komorebi.common.UrlBuilder
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.model.EpgChannel
import com.beeregg2001.komorebi.data.model.EpgChannelWrapper
import com.beeregg2001.komorebi.data.model.EpgProgram
import com.beeregg2001.komorebi.data.repository.EpgRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext // ★追加
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray // ★追加
import java.time.OffsetDateTime
import javax.inject.Inject

/**
 * 検索結果リストのUIに渡すための統合データクラス。
 * 番組情報単体だけでなく、どのチャンネルで放送されるかと、そのチャンネルのロゴURLをセットにして保持します。
 */
data class UiSearchResultItem(
    val program: EpgProgram,
    val channel: EpgChannel,
    val logoUrl: String
)

private const val PREF_NAME_EPG_SEARCH = "epg_search_history_pref"
private const val KEY_EPG_HISTORY = "history_list"

/**
 * 番組表（EPGタブ）のUI状態とビジネスロジックを管理するViewModel。
 * KonomiTV APIからの数日分・数十チャンネルに及ぶ巨大な番組データ（fullEpgData）をメモリ上に保持し、
 * UIの要求（表示したい日付や時間帯）に応じて1日分だけをスライスしてUI層（CanvasEngine）に渡す役割を担います。
 */
@RequiresApi(Build.VERSION_CODES.O)
@HiltViewModel
class EpgViewModel @Inject constructor(
    private val repository: EpgRepository,
    private val settingsRepository: SettingsRepository,
    @ApplicationContext private val context: Context // ★追加: SharedPreferencesにアクセスするため
) : ViewModel() {

    // ==========================================
    // 番組表のUI状態管理 (State)
    // ==========================================

    // EPGのメイン画面（グリッド）に渡すデータ状態。ComposeのMutableStateを利用して高速に再描画をトリガーします。
    var uiState by mutableStateOf<EpgUiState>(EpgUiState.Loading)
        private set

    // アプリ起動直後のバックグラウンドデータ先読み中フラグ
    private val _isPreloading = MutableStateFlow(true)
    val isPreloading: StateFlow<Boolean> = _isPreloading

    // 最初のデータロードが完了したかどうかのフラグ（スプラッシュ画面の解除判定などに使用）
    private val _isInitialLoadComplete = MutableStateFlow(false)
    val isInitialLoadComplete: StateFlow<Boolean> = _isInitialLoadComplete.asStateFlow()

    // 現在表示している放送波のタブ（"GR"=地デジ, "BS", "CS" など）
    private val _selectedBroadcastingType = MutableStateFlow("GR")
    val selectedBroadcastingType: StateFlow<String> = _selectedBroadcastingType.asStateFlow()

    // サーバーの接続情報（ロゴ画像のURL生成などに使用）
    private var mirakurunIp = ""
    private var mirakurunPort = ""
    private var konomiIp = ""
    private var konomiPort = ""

    private var hasInitialFetched = false
    private var epgJob: Job? = null

    // APIから取得した数日分の「全番組データ」。これを丸ごとUIに渡すと重すぎるため、裏側で保持しておきます。
    private var fullEpgData: List<EpgChannelWrapper> = emptyList()

    // 上記チャンネル群のロゴURLリスト（UI描画時の計算コストを省くためのキャッシュ）
    private var fullLogoUrls: List<String> = emptyList()

    // ユーザーが番組表上でフォーカスしている、またはジャンプ指定した「目標の日時」
    private var currentTargetTime: OffsetDateTime = OffsetDateTime.now()

    // ==========================================
    // 番組表の読み込み範囲 (過去方向は ◀◀ で 7 日ずつ追加読み込みできる)
    // ==========================================
    companion object {
        const val INITIAL_PAST_DAYS = 7L
        const val FUTURE_DAYS = 7L
        const val EXTEND_PAST_STEP_DAYS = 7L
    }

    enum class ExtendPastResult { Extended, NoMoreData, Failed }

    // 放送波ごとの読み込み済み開始日時 (追加読み込みで過去へ伸びる)
    private val loadedStartByType = mutableMapOf<String, OffsetDateTime>()

    private fun initialRangeStart(now: OffsetDateTime): OffsetDateTime =
        now.minusDays(INITIAL_PAST_DAYS).withHour(0).withMinute(0).withSecond(0).withNano(0)

    private fun rangeEnd(now: OffsetDateTime): OffsetDateTime = now.plusDays(FUTURE_DAYS)

    private fun loadedStart(type: String): OffsetDateTime =
        loadedStartByType.getOrPut(type) { initialRangeStart(OffsetDateTime.now()) }

    // UI (日ジャンプの範囲判定・時間割メニューの日付一覧) に渡す、現在の放送波の読み込み済み範囲
    var epgRangeStart by mutableStateOf(initialRangeStart(OffsetDateTime.now()))
        private set
    var epgRangeEnd by mutableStateOf(rangeEnd(OffsetDateTime.now()))
        private set

    // 過去方向の追加読み込み中フラグ (番組表は消さず、小さなインジケータだけ出す)
    var isLoadingMorePast by mutableStateOf(false)
        private set

    private fun publishRange(type: String) {
        epgRangeStart = loadedStart(type)
        epgRangeEnd = rangeEnd(OffsetDateTime.now())
    }

    /**
     * 現在の放送波について、読み込み済み範囲の手前 7 日分を KonomiTV から追加取得してメモリ上のデータに結合する。
     * KonomiTV 側のアーカイブ最古日に達している場合は NoMoreData を返す。
     */
    fun extendPast(onResult: (ExtendPastResult) -> Unit) {
        if (isLoadingMorePast) return
        val type = _selectedBroadcastingType.value
        val currentStart = loadedStart(type)

        val earliest = repository.getEarliestAvailable(type)
        if (earliest != null && !currentStart.isAfter(earliest)) {
            onResult(ExtendPastResult.NoMoreData)
            return
        }

        isLoadingMorePast = true
        viewModelScope.launch {
            val newStart = currentStart.minusDays(EXTEND_PAST_STEP_DAYS)
            val result = repository.fetchAndMergeRange(newStart, currentStart, type)
            result.onSuccess { merged ->
                // 応答で判明した最古日より前へは伸ばさない
                val floor = repository.getEarliestAvailable(type)
                loadedStartByType[type] =
                    if (floor != null && floor.isAfter(newStart)) floor.withHour(0).withMinute(0).withSecond(0).withNano(0) else newStart
                fullEpgData = merged
                publishRange(type)
                // 表示中の日は変わらないので再スライスはしない (ジャンプは呼び出し側が updateTargetTime で行う)
                isLoadingMorePast = false
                onResult(ExtendPastResult.Extended)
            }.onFailure {
                isLoadingMorePast = false
                onResult(ExtendPastResult.Failed)
            }
        }
    }

    // ==========================================
    // 未来番組検索用のState
    // ==========================================
    private val _searchHistory = MutableStateFlow<List<String>>(emptyList())
    val searchHistory: StateFlow<List<String>> = _searchHistory.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // 実際に検索ボタンが押され、現在検索結果に反映されている確定済みのクエリ
    private val _activeSearchQuery = MutableStateFlow("")
    val activeSearchQuery: StateFlow<String> = _activeSearchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<UiSearchResultItem>>(emptyList())
    val searchResults: StateFlow<List<UiSearchResultItem>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    // 🌟 追加: EPGフォーカス記憶と復元トリガー
    var lastFocusedChannelId: String? = null
    var lastFocusedTime: OffsetDateTime? = null
    var epgRestoreTrigger by androidx.compose.runtime.mutableStateOf(0L)
        private set

    fun saveEpgFocus(channelId: String, time: OffsetDateTime) {
        lastFocusedChannelId = channelId
        lastFocusedTime = time
    }

    fun triggerRestore() {
        epgRestoreTrigger = System.currentTimeMillis()
    }

    fun clearEpgFocus() {
        lastFocusedChannelId = null
        lastFocusedTime = null
    }

    init {
        loadSearchHistory() // ★追加: アプリ起動時に履歴を読み込む
        loadInitialData()
    }

    // ==========================================
    // 検索履歴のローカル保存機能 (SharedPreferences)
    // ==========================================
    private fun loadSearchHistory() {
        try {
            val prefs = context.getSharedPreferences(PREF_NAME_EPG_SEARCH, Context.MODE_PRIVATE)
            val jsonString = prefs.getString(KEY_EPG_HISTORY, "[]")
            val jsonArray = JSONArray(jsonString)
            val list = ArrayList<String>()
            for (i in 0 until jsonArray.length()) list.add(jsonArray.getString(i))
            _searchHistory.value = list
        } catch (e: Exception) {
            _searchHistory.value = emptyList()
        }
    }

    private fun addSearchHistory(query: String) {
        val currentList = _searchHistory.value.toMutableList()
        currentList.remove(query) // 重複排除
        currentList.add(0, query) // 先頭に追加
        if (currentList.size > 5) currentList.removeAt(currentList.lastIndex) // 最大5件まで保持
        _searchHistory.value = currentList
        saveSearchHistory(currentList)
    }

    fun removeSearchHistory(query: String) {
        val currentList = _searchHistory.value.toMutableList()
        if (currentList.remove(query)) {
            _searchHistory.value = currentList
            saveSearchHistory(currentList)
        }
    }

    private fun saveSearchHistory(list: List<String>) {
        viewModelScope.launch {
            try {
                val prefs = context.getSharedPreferences(PREF_NAME_EPG_SEARCH, Context.MODE_PRIVATE)
                val jsonArray = JSONArray(list)
                prefs.edit().putString(KEY_EPG_HISTORY, jsonArray.toString()).apply()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }
    // ==========================================

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    /**
     * KonomiTVのAPIを叩いて、未来の番組（番組表データ）からキーワード検索を実行します。
     * 結果は番組単体ではなく、チャンネル情報とロゴURLを結合したUiSearchResultItemのリストとしてUIに提供します。
     */
    // ==========================================
    // ★ 修正: 超スッキリした検索呼び出しメソッド
    // ==========================================
    @OptIn(UnstableApi::class)
    fun executeSearch(
        keyword: String,
        genre: String? = null,
        dateStr: String? = null,
        isLiveOnly: Boolean = false,
        channelName: String? = null
    ) {
        viewModelScope.launch {
            _isSearching.value = true

            // UIに表示するためのテキストを生成
            val displayQuery = listOfNotNull(
                keyword.takeIf { it.isNotBlank() },
                channelName?.takeIf { it.isNotBlank() },
                genre?.takeIf { it.isNotBlank() }
            ).joinToString(" ")

            _searchQuery.value = displayQuery
            _activeSearchQuery.value = displayQuery
            if (displayQuery.isNotBlank()) addSearchHistory(displayQuery)

            try {
                // 最強になったRepositoryの検索エンジンを呼ぶだけ！
                val rawResults = repository.searchFuturePrograms(
                    keyword,
                    genre,
                    dateStr,
                    isLiveOnly,
                    channelName
                )

                _searchResults.value = rawResults.map { item ->
                    UiSearchResultItem(
                        program = item.program,
                        channel = item.channel,
                        logoUrl = getLogoUrl(item.channel)
                    )
                }
            } catch (e: Exception) {
                Log.e("EpgViewModel", "Search Error", e)
                _searchResults.value = emptyList()
            } finally {
                _isSearching.value = false
            }
        }
    }

    suspend fun searchSilently(
        keyword: String,
        genre: String? = null,
        dateStr: String? = null,
        isLiveOnly: Boolean = false,
        channelName: String? = null
    ): List<UiSearchResultItem> {
        return try {
            val rawResults =
                repository.searchFuturePrograms(keyword, genre, dateStr, isLiveOnly, channelName)
            rawResults.map { item ->
                UiSearchResultItem(
                    program = item.program,
                    channel = item.channel,
                    logoUrl = getLogoUrl(item.channel)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun clearSearch() {
        _activeSearchQuery.value = ""
        _searchQuery.value = ""
        _searchResults.value = emptyList()
    }

    /**
     * EPGデータ（地デジ、BSなど全波）をバックグラウンドで先読みしてキャッシュに格納します。
     */
    fun preloadEpgDataForSearch(availableTypes: List<String>) {
        val now = OffsetDateTime.now()
        val start = initialRangeStart(now)
        val end = rangeEnd(now) // 7日前〜7日後を取得

        viewModelScope.launch(Dispatchers.IO) {
            availableTypes.map { type ->
                async {
                    if (!repository.hasCacheForType(type)) {
                        repository.fetchAndCacheEpgDataSilently(start, end, type)
                    }
                }
            }.awaitAll()
        }
    }

    private fun loadInitialData() {
        viewModelScope.launch {
            combine(
                settingsRepository.mirakurunIp,
                settingsRepository.mirakurunPort,
                settingsRepository.konomiIp,
                settingsRepository.konomiPort,
                _selectedBroadcastingType
            ) { mIp, mPort, kIp, kPort, type ->
                mirakurunIp = mIp
                mirakurunPort = mPort
                konomiIp = kIp
                konomiPort = kPort

                val isMirakurunReady = mirakurunIp.isNotEmpty() && mirakurunPort.isNotEmpty()
                val isKonomiReady = konomiIp.isNotEmpty() && konomiPort.isNotEmpty()

                if ((isMirakurunReady || isKonomiReady) && !hasInitialFetched) {
                    hasInitialFetched = true
                    viewModelScope.launch { refreshEpgData(type) }

                    // ★追加: 検索・AI予約のために、他の放送波（BS・CS・SKY等）も裏側でメモリにキャッシュしておく！
                    preloadEpgDataForSearch(listOf("GR", "BS", "CS", "SKY", "BS4K"))

                } else if ((isMirakurunReady || isKonomiReady) && hasInitialFetched) {
                    refreshEpgData(type)
                }
            }.collectLatest { }
        }
    }

    fun preloadAllEpgData() {
        refreshEpgData()
    }

    fun refreshEpgData(channelType: String? = null) {
        epgJob?.cancel()
        epgJob = viewModelScope.launch {
            if (uiState !is EpgUiState.Success) {
                uiState = EpgUiState.Loading
            }

            val now = OffsetDateTime.now()
            val typeToFetch = channelType ?: _selectedBroadcastingType.value
            // 初回は 7 日前 0 時から。追加読み込み済みならその開始日時から (過去分をキャッシュから引き継ぐ)
            val start = initialRangeStart(now)
            val end = rangeEnd(now)

            repository.getEpgDataStream(start, end, typeToFetch).collect { result ->
                result.onSuccess { data ->
                    fullEpgData = data
                    // 追加読み込みで過去へ伸ばした開始日時があればそれを維持し、無ければ今回の取得開始日時にする
                    val cur = loadedStartByType[typeToFetch]
                    if (cur == null || cur.isAfter(start)) loadedStartByType[typeToFetch] = start
                    publishRange(typeToFetch)
                    fullLogoUrls =
                        withContext(Dispatchers.Default) { data.map { getLogoUrl(it.channel) } }

                    sliceAndEmitEpgData()

                    _isInitialLoadComplete.value = true
                    _isPreloading.value = false
                }.onFailure { e ->
                    if (uiState !is EpgUiState.Success) {
                        uiState = EpgUiState.Error(e.message ?: "Unknown Error")
                        _isInitialLoadComplete.value = true
                    }
                }
            }
        }
    }

    fun updateTargetTime(time: OffsetDateTime) {
        currentTargetTime = time
        sliceAndEmitEpgData()
    }

    private fun getTvDayStart(time: OffsetDateTime): OffsetDateTime {
        val base = time.withHour(4).withMinute(0).withSecond(0).withNano(0)
        return if (time.hour < 4) base.minusDays(1) else base
    }

    private fun sliceAndEmitEpgData() {
        if (fullEpgData.isEmpty()) return
        viewModelScope.launch(Dispatchers.Default) {

            val tvDayStart = getTvDayStart(currentTargetTime)
            val tvDayEnd = tvDayStart.plusHours(24)

            val slicedData = fullEpgData.map { wrapper ->
                val filteredPrograms = wrapper.programs.filter { prog ->
                    try {
                        val pStart = OffsetDateTime.parse(prog.start_time)
                        val pEnd = OffsetDateTime.parse(prog.end_time)
                        pEnd.isAfter(tvDayStart) && pStart.isBefore(tvDayEnd)
                    } catch (e: Exception) {
                        false
                    }
                }
                wrapper.copy(programs = filteredPrograms)
            }

            uiState = EpgUiState.Success(
                data = slicedData,
                logoUrls = fullLogoUrls,
                mirakurunIp = mirakurunIp,
                mirakurunPort = mirakurunPort,
                targetTime = currentTargetTime
            )
        }
    }

    @OptIn(UnstableApi::class)
    fun getLogoUrl(channel: EpgChannel): String {
        return if (mirakurunIp.isNotEmpty() && mirakurunPort.isNotEmpty()) {
            UrlBuilder.getMirakurunLogoUrl(
                mirakurunIp, mirakurunPort, channel.network_id.toLong(), channel.service_id.toLong()
            )
        } else {
            UrlBuilder.getKonomiTvLogoUrl(konomiIp, konomiPort, channel.display_channel_id)
        }
    }

    fun updateBroadcastingType(type: String) {
        if (_selectedBroadcastingType.value != type) {
            _selectedBroadcastingType.value = type
        }
    }
}

sealed class EpgUiState {
    object Loading : EpgUiState()

    data class Success(
        val data: List<EpgChannelWrapper>,
        val logoUrls: List<String>,
        val mirakurunIp: String,
        val mirakurunPort: String,
        val targetTime: OffsetDateTime
    ) : EpgUiState()

    data class Error(val message: String) : EpgUiState()
}