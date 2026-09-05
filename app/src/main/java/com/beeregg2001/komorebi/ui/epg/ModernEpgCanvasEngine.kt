@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.beeregg2001.komorebi.ui.epg

import android.os.Build
import android.util.Log
import android.view.KeyEvent as NativeKeyEvent
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import coil.compose.rememberAsyncImagePainter
import com.beeregg2001.komorebi.data.model.EpgProgram
import com.beeregg2001.komorebi.data.model.ReserveItem
import com.beeregg2001.komorebi.ui.epg.engine.*
import com.beeregg2001.komorebi.ui.epg.logic.RecordedProgramMatcher
import com.beeregg2001.komorebi.viewmodel.EpgUiState
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import java.time.Duration
import java.time.OffsetDateTime
import kotlinx.coroutines.delay

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun ModernEpgCanvasEngine_Smooth(
    uiState: EpgUiState,
    logoUrls: List<String>,
    topTabFocusRequester: FocusRequester,
    headerFocusRequester: FocusRequester,
    jumpButtonFocusRequester: FocusRequester,
    gridFocusRequester: FocusRequester,
    onProgramSelected: (EpgProgram) -> Unit,
    onEpgJumpMenuStateChanged: (Boolean) -> Unit,
    currentType: String,
    onTypeChanged: (String) -> Unit,
    restoreChannelId: String? = null,
    restoreProgramStartTime: String? = null,
    availableTypes: List<String> = emptyList(),
    reserves: List<ReserveItem> = emptyList(),
    onUpdateTargetTime: (OffsetDateTime) -> Unit,
    onRequestJumpToNow: () -> Unit,
    searchButtonFocusRequester: FocusRequester,
    onSearchClick: () -> Unit,
    timeFormat: String,
    // 日をまたぐジャンプ要求 (▶▶/◀◀ の ±1 日、上下キーでの前日末尾/翌日先頭)。
    // 読み込み済み範囲の判定と過去方向の追加読み込みは呼び出し側 (EpgNavigationContainer) が行う
    onRequestTime: (target: OffsetDateTime) -> Unit = {},
    // メニュー (バーガー) キー: グリッドから時間割ジャンプを開く
    onOpenJumpMenuFromGrid: () -> Unit = {},
    // 録画済み番組の枠線描画用 (ローカルDBの録画のチャンネル・放送時間)
    recordedRanges: List<RecordedProgramMatcher.RecordedRange> = emptyList()
) {
    val density = LocalDensity.current
    val colors = KomorebiTheme.colors

    val config = remember(density, colors) { EpgConfig(density, colors) }
    val epgState = remember { EpgState(config) }

    val textMeasurer = rememberTextMeasurer()
    val drawer = remember(config, textMeasurer) { EpgDrawer(config, textMeasurer) }
    val logoPainters = logoUrls.map { rememberAsyncImagePainter(model = it) }
    val clockPainter = rememberVectorPainter(Icons.Default.Schedule)
    val reserveMap = remember(reserves) { reserves.associateBy { it.program.id } }
    // 表示中の1日分の番組のうち、録画済みのものの ID (録画一覧や表示日が変わったときだけ再計算)
    val recordedIds = remember(uiState, recordedRanges) {
        val data = (uiState as? EpgUiState.Success)?.data ?: return@remember emptySet<String>()
        RecordedProgramMatcher.matchedProgramIds(data.flatMap { it.programs }, recordedRanges)
    }

    val visibleTabs = remember(availableTypes) {
        val all =
            listOf("地デジ" to "GR", "BS" to "BS", "CS" to "CS", "BS4K" to "BS4K", "SKY" to "SKY")
        if (availableTypes.isEmpty()) all else all.filter { it.second in availableTypes }
    }
    val subTabFocusRequesters =
        remember(visibleTabs.size) { List(visibleTabs.size) { FocusRequester() } }

    val epgViewModel: com.beeregg2001.komorebi.viewmodel.EpgViewModel =
        androidx.hilt.navigation.compose.hiltViewModel()

    LaunchedEffect(epgViewModel.epgRestoreTrigger) {
        if (epgViewModel.epgRestoreTrigger > 0L) {
            val targetCh = epgViewModel.lastFocusedChannelId
            val targetTime = epgViewModel.lastFocusedTime
            if (targetCh != null && targetTime != null) {
                Log.i(
                    "KomorebiFocus",
                    "[ModernEpgCanvas] 復元トリガー検知！ $targetCh - $targetTime へジャンプします"
                )
                epgState.restoreFocus(targetCh, targetTime)
                delay(150)
                gridFocusRequester.requestFocus()
                epgViewModel.clearEpgFocus()
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(
        epgState.focusedCol,
        epgState.focusedMin,
        epgState.currentFocusedProgram
    ) {
        val ch = epgState.uiChannels.getOrNull(epgState.focusedCol)
        val prog = epgState.currentFocusedProgram

        if (ch != null && prog != null && prog.title != "（番組情報なし）") {
            val time = com.beeregg2001.komorebi.ui.epg.EpgDataConverter.safeParseTime(
                prog.start_time,
                epgState.baseTime
            )
            epgViewModel.saveEpgFocus(ch.wrapper.channel.id, time)
        }
    }

    var isHeaderVisible by remember { mutableStateOf(true) }
    var pendingHeaderFocusIndex by remember { mutableStateOf<Int?>(null) }
    var lastLoadedType by remember { mutableStateOf<String?>(null) }
    var hasRenderedFirstFrame by remember { mutableStateOf(false) }

    var isJumping by remember { mutableStateOf(false) }
    var isLongPressHandled by remember { mutableStateOf(false) }

    var lastRequestedTargetTime by remember { mutableStateOf<OffsetDateTime?>(null) }
    var isNextUpdateSeamless by remember { mutableStateOf(false) }

    LaunchedEffect(epgState.hasData) {
        if (epgState.hasData && !hasRenderedFirstFrame) epgState.jumpToNow()
    }

    LaunchedEffect(isHeaderVisible, pendingHeaderFocusIndex) {
        if (isHeaderVisible && pendingHeaderFocusIndex != null) {
            val index = pendingHeaderFocusIndex!!
            delay(50)
            if (index == -2) topTabFocusRequester.safeRequestFocus("Epg_TopTab")
            else if (index in subTabFocusRequesters.indices) subTabFocusRequesters[index].safeRequestFocus(
                "Epg_SubTab"
            )
            pendingHeaderFocusIndex = null
        }
    }

    // ==========================================================
    // ★ 修正箇所: ジャンプ後に元のチャンネル列を復元するロジック
    // ==========================================================
    LaunchedEffect(uiState) {
        if (uiState is EpgUiState.Success) {
            val isTypeChanged = lastLoadedType != null && lastLoadedType != currentType
            lastLoadedType = currentType
            if (isTypeChanged) hasRenderedFirstFrame = false

            isJumping = true
            lastRequestedTargetTime = null

            // ★ 修正: データ更新（ジャンプ）直前に見ていたチャンネルのIDを保存しておく
            val prevChannelId = epgViewModel.lastFocusedChannelId

            epgState.updateData(
                newData = uiState.data,
                targetTime = uiState.targetTime,
                resetFocus = isTypeChanged
            )

            // ★ 修正: 放送波(地デジ等)の切り替えでなければ、直前にフォーカスしていたチャンネル列へ復元する
            if (!isTypeChanged && prevChannelId != null) {
                // targetTimeがある場合はその時間へ、なければ直前に見ていた時間へジャンプ
                val targetTime =
                    uiState.targetTime ?: epgViewModel.lastFocusedTime ?: OffsetDateTime.now()
                epgState.restoreFocus(prevChannelId, targetTime)
            }

            isNextUpdateSeamless = false

            delay(100)
            isJumping = false
        }
    }

    BoxWithConstraints {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()

        LaunchedEffect(w, h) { epgState.updateScreenSize(w, h) }

        val scrollSpec = if (isJumping) snap() else spring<Float>(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = 2500f
        )

        val scrollX by animateFloatAsState(epgState.targetScrollX, scrollSpec, label = "sX")
        val scrollY by animateFloatAsState(epgState.targetScrollY, scrollSpec, label = "sY")
        val animX by animateFloatAsState(epgState.targetAnimX, scrollSpec, label = "aX")
        val animY by animateFloatAsState(epgState.targetAnimY, scrollSpec, label = "aY")
        val animH by animateFloatAsState(epgState.targetAnimH, scrollSpec, label = "aH")

        val animValues = EpgAnimValues(scrollX, scrollY, animX, animY, animH)

        Column(modifier = Modifier.fillMaxSize()) {
            AnimatedVisibility(
                visible = isHeaderVisible,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                EpgHeaderSection(
                    topTabFocusRequester = topTabFocusRequester,
                    headerFocusRequester = headerFocusRequester,
                    jumpMenuFocusRequester = jumpButtonFocusRequester,
                    searchButtonFocusRequester = searchButtonFocusRequester,
                    gridFocusRequester = gridFocusRequester,
                    subTabFocusRequesters = subTabFocusRequesters,
                    availableBroadcastingTypes = visibleTabs,
                    onEpgJumpMenuStateChanged = onEpgJumpMenuStateChanged,
                    onSearchClick = onSearchClick,
                    onTypeChanged = onTypeChanged,
                    currentType = currentType
                )
            }

            var isContentFocused by remember { mutableStateOf(false) }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .focusRequester(gridFocusRequester)
                    .onFocusChanged {
                        isContentFocused = it.isFocused
                        if (it.isFocused) isHeaderVisible = false
                    }
                    .onKeyEvent { event ->
                        if (event.key == Key.Back) {
                            if (event.type == KeyEventType.KeyDown) {
                                if (event.nativeKeyEvent.isLongPress) {
                                    isJumping = true
                                    isLongPressHandled = true
                                    onRequestJumpToNow()
                                    return@onKeyEvent true
                                }
                                return@onKeyEvent true
                            }
                            if (event.type == KeyEventType.KeyUp) {
                                if (isLongPressHandled) {
                                    isLongPressHandled = false
                                    isJumping = false
                                    return@onKeyEvent true
                                } else {
                                    isJumping = false
                                    isHeaderVisible = true
                                    pendingHeaderFocusIndex =
                                        visibleTabs.indexOfFirst { it.second == currentType }
                                            .coerceAtLeast(0)
                                    return@onKeyEvent true
                                }
                            }
                        }

                        // 非公式パッチ: ▶▶/◀◀ で翌日/前日へ、メニューキーで時間割ジャンプを開く
                        when (event.nativeKeyEvent.keyCode) {
                            NativeKeyEvent.KEYCODE_MEDIA_FAST_FORWARD, NativeKeyEvent.KEYCODE_MEDIA_NEXT,
                            NativeKeyEvent.KEYCODE_MEDIA_REWIND, NativeKeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                                if (event.type == KeyEventType.KeyDown &&
                                    event.nativeKeyEvent.repeatCount == 0 && epgState.hasData
                                ) {
                                    val isForward =
                                        event.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_MEDIA_FAST_FORWARD ||
                                                event.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_MEDIA_NEXT
                                    val currentTime =
                                        epgState.baseTime.plusMinutes(epgState.focusedMin.toLong())
                                    onRequestTime(currentTime.plusDays(if (isForward) 1L else -1L))
                                }
                                return@onKeyEvent true
                            }

                            NativeKeyEvent.KEYCODE_MENU -> {
                                if (event.type == KeyEventType.KeyUp) onOpenJumpMenuFromGrid()
                                return@onKeyEvent true
                            }
                        }

                        if (event.type == KeyEventType.KeyDown) {
                            when (event.key) {
                                Key.DirectionRight -> {
                                    epgState.updatePositions(
                                        epgState.focusedCol + 1,
                                        epgState.focusedMin
                                    ); true
                                }

                                Key.DirectionLeft -> {
                                    epgState.updatePositions(
                                        epgState.focusedCol - 1,
                                        epgState.focusedMin
                                    ); true
                                }

                                Key.DirectionDown -> {
                                    val next = epgState.currentFocusedProgram?.let {
                                        Duration.between(
                                            epgState.baseTime,
                                            EpgDataConverter.safeParseTime(
                                                it.end_time,
                                                epgState.baseTime
                                            )
                                        ).toMinutes().toInt()
                                    } ?: (epgState.focusedMin + 30)

                                    if (next >= epgState.maxScrollMinutes) {
                                        val currentTarget =
                                            (uiState as? EpgUiState.Success)?.targetTime
                                                ?: OffsetDateTime.now()
                                        val nextDayStart =
                                            epgState.baseTime.plusDays(1).plusHours(1)
                                        if (lastRequestedTargetTime != nextDayStart) {
                                            lastRequestedTargetTime = nextDayStart
                                            onRequestTime(nextDayStart)
                                        }
                                        true
                                    } else {
                                        epgState.updatePositions(epgState.focusedCol, next); true
                                    }
                                }

                                Key.DirectionUp -> {
                                    val prev = epgState.currentFocusedProgram?.let {
                                        Duration.between(
                                            epgState.baseTime,
                                            EpgDataConverter.safeParseTime(
                                                it.start_time,
                                                epgState.baseTime
                                            )
                                        ).toMinutes().toInt() - 1
                                    } ?: (epgState.focusedMin - 30)

                                    if (prev < 0) {
                                        val todayStart =
                                            OffsetDateTime.now().withHour(4).withMinute(0)
                                                .withSecond(0).withNano(0).let {
                                                    if (OffsetDateTime.now().hour < 4) it.minusDays(
                                                        1
                                                    ) else it
                                                }
                                        if (!epgState.baseTime.isBefore(todayStart)) {
                                            isHeaderVisible = true
                                            pendingHeaderFocusIndex =
                                                visibleTabs.indexOfFirst { it.second == currentType }
                                                    .coerceAtLeast(0)
                                        } else {
                                            val prevDayEnd = epgState.baseTime.minusHours(1)
                                            if (lastRequestedTargetTime != prevDayEnd) {
                                                lastRequestedTargetTime = prevDayEnd
                                                onRequestTime(prevDayEnd)
                                            }
                                        }
                                        true
                                    } else {
                                        epgState.updatePositions(epgState.focusedCol, prev); true
                                    }
                                }

                                else -> false
                            }
                        } else if (event.type == KeyEventType.KeyUp) {
                            when (event.key) {
                                Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                                    epgState.currentFocusedProgram?.let {
                                        if (it.title != "（番組情報なし）") onProgramSelected(it)
                                    }; true
                                }

                                else -> false
                            }
                        } else false
                    }
                    .focusable()
            ) {
                if (epgState.hasData) {
                    Spacer(
                        modifier = Modifier
                            .fillMaxSize()
                            .drawWithCache {
                                onDrawBehind {
                                    drawer.draw(
                                        drawScope = this,
                                        state = epgState,
                                        animValues = animValues,
                                        logoPainters = logoPainters,
                                        isGridFocused = isContentFocused || epgState.hasData,
                                        reserveMap = reserveMap,
                                        clockPainter = clockPainter,
                                        timeFormat = timeFormat,
                                        recordedIds = recordedIds
                                    )
                                    hasRenderedFirstFrame = true
                                }
                            })
                }

                if (uiState is EpgUiState.Loading || epgState.isCalculating || (!hasRenderedFirstFrame && !isJumping)) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(colors.background.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator(color = colors.textPrimary) }
                }
            }
        }
    }
}

@Composable
fun EpgHeaderSection(
    topTabFocusRequester: FocusRequester,
    headerFocusRequester: FocusRequester,
    jumpMenuFocusRequester: FocusRequester,
    searchButtonFocusRequester: FocusRequester,
    gridFocusRequester: FocusRequester,
    subTabFocusRequesters: List<FocusRequester>,
    availableBroadcastingTypes: List<Pair<String, String>>,
    onEpgJumpMenuStateChanged: (Boolean) -> Unit,
    onSearchClick: () -> Unit,
    onTypeChanged: (String) -> Unit,
    currentType: String
) {
    val colors = KomorebiTheme.colors
    val currentTypeIndex = remember(
        currentType,
        availableBroadcastingTypes
    ) { availableBroadcastingTypes.indexOfFirst { it.second == currentType }.coerceAtLeast(0) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(colors.surface.copy(alpha = 0.95f))
    ) {
        Row(
            modifier = Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.Center
        ) {
            availableBroadcastingTypes.forEachIndexed { index, (label, apiValue) ->
                var isTabFocused by remember { mutableStateOf(false) }
                val requester = subTabFocusRequesters[index]
                val isTarget = index == currentTypeIndex

                Box(
                    modifier = Modifier
                        .width(110.dp)
                        .fillMaxHeight()
                        .onFocusChanged { isTabFocused = it.isFocused }
                        .then(if (isTarget) Modifier.focusRequester(headerFocusRequester) else Modifier)
                        .focusRequester(requester)
                        .focusProperties {
                            left =
                                if (index == 0) jumpMenuFocusRequester else subTabFocusRequesters[index - 1]
                            right =
                                if (index == availableBroadcastingTypes.size - 1) searchButtonFocusRequester else subTabFocusRequesters[index + 1]
                            down = gridFocusRequester
                            up = topTabFocusRequester
                        }
                        .onKeyEvent { event ->
                            if (event.key == Key.Back || event.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_BACK) {
                                if (event.type == KeyEventType.KeyDown) return@onKeyEvent true
                                if (event.type == KeyEventType.KeyUp) {
                                    topTabFocusRequester.safeRequestFocus("EpgHeader_Back")
                                    return@onKeyEvent true
                                }
                            }
                            if (event.type == KeyEventType.KeyDown) {
                                when (event.key) {
                                    Key.DirectionUp -> {
                                        topTabFocusRequester.safeRequestFocus("EpgHeader_Up"); return@onKeyEvent true
                                    }

                                    Key.DirectionCenter, Key.Enter -> {
                                        onTypeChanged(apiValue); return@onKeyEvent true
                                    }
                                }
                            }
                            false
                        }
                        .focusable()
                        .background(
                            if (isTabFocused) colors.textPrimary else Color.Transparent,
                            RectangleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        color = if (isTabFocused) (if (colors.isDark) Color.Black else Color.White) else colors.textPrimary,
                        fontSize = 15.sp
                    )
                    if (currentType == apiValue && !isTabFocused) Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth(0.6f)
                            .height(3.dp)
                            .background(colors.accent)
                    )
                }
            }
        }

        var isSearchBtnFocused by remember { mutableStateOf(false) }
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(60.dp)
                .fillMaxHeight()
                .onFocusChanged { isSearchBtnFocused = it.isFocused }
                .focusRequester(searchButtonFocusRequester)
                .focusProperties {
                    left =
                        if (subTabFocusRequesters.isNotEmpty()) subTabFocusRequesters.last() else jumpMenuFocusRequester
                    down = gridFocusRequester
                    up = topTabFocusRequester
                }
                .onKeyEvent { event ->
                    if (event.key == Key.Back || event.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_BACK) {
                        if (event.type == KeyEventType.KeyDown) return@onKeyEvent true
                        if (event.type == KeyEventType.KeyUp) {
                            topTabFocusRequester.safeRequestFocus("EpgSearch_Back"); return@onKeyEvent true
                        }
                    }
                    if (event.type == KeyEventType.KeyDown) {
                        when (event.key) {
                            Key.DirectionUp -> {
                                topTabFocusRequester.safeRequestFocus("EpgSearch_Up"); return@onKeyEvent true
                            }

                            Key.DirectionCenter, Key.Enter -> {
                                onSearchClick(); return@onKeyEvent true
                            }
                        }
                    }
                    false
                }
                .focusable()
                .background(if (isSearchBtnFocused) colors.textPrimary else Color.Transparent),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "番組検索",
                tint = if (isSearchBtnFocused) (if (colors.isDark) Color.Black else Color.White) else colors.textPrimary
            )
        }

        var isJumpBtnFocused by remember { mutableStateOf(false) }
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .width(110.dp)
                .fillMaxHeight()
                .onFocusChanged { isJumpBtnFocused = it.isFocused }
                .focusRequester(jumpMenuFocusRequester)
                .focusProperties {
                    right =
                        if (subTabFocusRequesters.isNotEmpty()) subTabFocusRequesters[0] else searchButtonFocusRequester
                    down = gridFocusRequester; up = topTabFocusRequester
                }
                .onKeyEvent { event ->
                    if (event.key == Key.Back || event.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_BACK) {
                        if (event.type == KeyEventType.KeyDown) return@onKeyEvent true
                        if (event.type == KeyEventType.KeyUp) {
                            topTabFocusRequester.safeRequestFocus("EpgJump_Back"); return@onKeyEvent true
                        }
                    }
                    if (event.type == KeyEventType.KeyDown) {
                        when (event.key) {
                            Key.DirectionUp -> {
                                topTabFocusRequester.safeRequestFocus("EpgJump_Up"); return@onKeyEvent true
                            }

                            Key.DirectionCenter, Key.Enter -> {
                                onEpgJumpMenuStateChanged(true); return@onKeyEvent true
                            }
                        }
                    }
                    false
                }
                .focusable()
                .background(if (isJumpBtnFocused) colors.textPrimary else Color.Transparent),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "日時指定",
                color = if (isJumpBtnFocused) (if (colors.isDark) Color.Black else Color.White) else colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp
            )
        }
    }
}