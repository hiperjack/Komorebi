package com.beeregg2001.komorebi.ui.epg

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.ui.epg.logic.EpgTimeSlots
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 日時指定ジャンプ (時間割)。
 * 縦に日付 (読み込み済み範囲: 初回は 7 日前〜7 日後、◀◀ で過去へ伸びる)、横に 6 時間区切りの 4 枠。
 * 決定でその枠の開始時刻へジャンプする。
 */
@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun EpgJumpMenu(
    dates: List<LocalDate>,
    initialTime: OffsetDateTime,
    timeFormat: String,
    onSelect: (OffsetDateTime) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = KomorebiTheme.colors
    val now = remember { OffsetDateTime.now() }
    val today = now.toLocalDate()
    val offset = initialTime.offset
    val slots = 0 until EpgTimeSlots.SLOTS_PER_DAY

    val dateColumnWidth = 120.dp
    val slotColumnWidth = 130.dp
    val rowHeight = 32.dp

    val focusRequesters = remember(dates) {
        dates.map { List(EpgTimeSlots.SLOTS_PER_DAY) { FocusRequester() } }
    }
    val listState = rememberLazyListState()

    // 初期フォーカス: 今見ている日時が属する枠 (範囲外なら今日、それも無ければ先頭)
    LaunchedEffect(initialTime, dates) {
        if (dates.isEmpty()) return@LaunchedEffect
        val targetDate = initialTime.toLocalDate()
        val row = dates.indexOf(targetDate).takeIf { it >= 0 }
            ?: dates.indexOf(today).takeIf { it >= 0 }
            ?: 0
        val slot = EpgTimeSlots.slotIndexOf(initialTime)
        listState.scrollToItem((row - 4).coerceAtLeast(0))
        delay(100)
        focusRequesters[row][slot].safeRequestFocus("EpgJumpMenu_Initial")
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.9f))
            .onKeyEvent { event ->
                if (event.key == Key.Back || event.key == Key.Escape) {
                    if (event.type == KeyEventType.KeyDown) return@onKeyEvent true
                    if (event.type == KeyEventType.KeyUp) {
                        onDismiss(); return@onKeyEvent true
                    }
                }
                false
            }
            .focusGroup(),
        contentAlignment = Alignment.Center
    ) {
        // LazyColumn (SubcomposeLayout) を含むため IntrinsicSize は使えない (クラッシュする)。幅は明示する
        val gridWidth = dateColumnWidth + slotColumnWidth * EpgTimeSlots.SLOTS_PER_DAY
        Surface(
            modifier = Modifier
                .width(gridWidth + 48.dp)
                .wrapContentHeight()
                .focusGroup(),
            shape = RoundedCornerShape(8.dp),
            colors = SurfaceDefaults.colors(containerColor = colors.surface),
            border = Border(BorderStroke(1.dp, colors.textPrimary.copy(alpha = 0.2f)))
        ) {
            Column(
                modifier = Modifier.padding(vertical = 16.dp, horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "日時指定ジャンプ",
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontWeight = FontWeight.Black,
                        letterSpacing = 4.sp
                    ),
                    color = colors.textPrimary, modifier = Modifier.padding(bottom = 8.dp)
                )

                // ヘッダー行 (時間帯ラベル)
                Row {
                    Box(modifier = Modifier.width(dateColumnWidth).height(28.dp))
                    slots.forEach { slotIdx ->
                        Box(
                            modifier = Modifier.width(slotColumnWidth).height(28.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = slotHeaderLabel(slotIdx, timeFormat),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.textSecondary
                            )
                        }
                    }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .width(gridWidth)
                        .heightIn(max = 400.dp)
                        .focusGroup()
                ) {
                    itemsIndexed(dates, key = { _, d -> d.toEpochDay() }) { rowIdx, date ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            DateLabelCell(date, isToday = date == today, dateColumnWidth, rowHeight)

                            slots.forEach { slotIdx ->
                                val slotStart = EpgTimeSlots.slotStart(date, slotIdx, offset)
                                val slotEnd = slotStart.plusHours(EpgTimeSlots.SLOT_HOURS.toLong())
                                val isPastSlot = !slotEnd.isAfter(now)
                                val isCurrentSlot = !slotStart.isAfter(now) && slotEnd.isAfter(now)
                                var isFocused by remember { mutableStateOf(false) }

                                val baseColor = getTimeSlotColor(slotIdx * EpgTimeSlots.SLOT_HOURS + 3, colors)

                                Box(
                                    modifier = Modifier
                                        .width(slotColumnWidth)
                                        .height(rowHeight)
                                        .padding(1.dp)
                                        .focusRequester(focusRequesters[rowIdx][slotIdx])
                                        .focusProperties {
                                            if (slotIdx == 0) left = FocusRequester.Cancel
                                            if (slotIdx == slots.last) right = FocusRequester.Cancel
                                            if (rowIdx == 0) up = FocusRequester.Cancel
                                            if (rowIdx == dates.lastIndex) down = FocusRequester.Cancel
                                        }
                                        .onFocusChanged { isFocused = it.isFocused }
                                        .focusable()
                                        .onKeyEvent { event ->
                                            if (event.type == KeyEventType.KeyDown &&
                                                (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter)
                                            ) {
                                                onSelect(slotStart); true
                                            } else false
                                        }
                                        .background(
                                            when {
                                                isFocused -> colors.accent
                                                isPastSlot -> baseColor.copy(alpha = 0.35f)
                                                else -> baseColor
                                            }
                                        )
                                        .border(
                                            width = if (isFocused || isCurrentSlot) 2.dp else 0.5.dp,
                                            color = when {
                                                isFocused -> colors.textPrimary
                                                isCurrentSlot -> colors.accent
                                                else -> colors.background.copy(0.3f)
                                            }
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isCurrentSlot) {
                                        Text(
                                            "現在",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isFocused) Color.White else colors.accent
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun slotHeaderLabel(slotIdx: Int, timeFormat: String): String {
    val startHour = slotIdx * EpgTimeSlots.SLOT_HOURS
    val endHour = startHour + EpgTimeSlots.SLOT_HOURS
    return if (timeFormat == "12H") {
        when (slotIdx) {
            0 -> "AM 0-6"
            1 -> "AM 6-12"
            2 -> "PM 0-6"
            else -> "PM 6-12"
        }
    } else {
        "${startHour}-${endHour}時"
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
private fun DateLabelCell(
    date: LocalDate,
    isToday: Boolean,
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp
) {
    val colors = KomorebiTheme.colors
    val isSunday = date.dayOfWeek.value == 7
    val isSaturday = date.dayOfWeek.value == 6
    Row(
        modifier = Modifier
            .width(width)
            .height(height)
            .padding(end = 8.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isToday) {
            Text(
                "今日",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = colors.accent,
                modifier = Modifier.padding(end = 6.dp)
            )
        }
        Text(
            text = date.format(DateTimeFormatter.ofPattern("M/d", Locale.JAPANESE)),
            fontSize = 14.sp,
            fontWeight = if (isToday) FontWeight.Black else FontWeight.Bold,
            color = colors.textPrimary
        )
        Text(
            text = date.format(DateTimeFormatter.ofPattern("(E)", Locale.JAPANESE)),
            fontSize = 11.sp,
            color = when {
                isSunday -> Color(0xFFFF5252); isSaturday -> Color(0xFF448AFF); else -> colors.textSecondary
            },
            modifier = Modifier.padding(start = 2.dp)
        )
    }
}

fun getTimeSlotColor(hour: Int, colors: com.beeregg2001.komorebi.ui.theme.KomorebiColors): Color {
    return when (hour) {
        in 4..10 -> Color(0xFF422B2B)
        in 11..16 -> Color(0xFF2B422B)
        in 17..22 -> Color(0xFF2B2B42)
        else -> colors.surface
    }
}
