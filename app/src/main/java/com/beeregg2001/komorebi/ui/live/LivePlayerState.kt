package com.beeregg2001.komorebi.ui.live

import android.content.Context
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import com.beeregg2001.komorebi.common.AppStrings
import com.beeregg2001.komorebi.data.model.AudioMode
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.model.StreamQuality
import com.beeregg2001.komorebi.data.model.StreamSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

data class SignalMetadata(
    val videoRes: String = "-",
    val videoCodec: String = "-",
    val videoBitrate: String = "-",
    val verticalFreq: String = "-",
    val audioCodec: String = "-",
    val audioChannels: String = "-",
    val audioSampleRate: String = "-",
    val bufferDuration: String = "-",
    val droppedFrames: String = "0"
)

@Stable
class LivePlayerState(
    val context: Context,
    initialQuality: String
) {
    var currentAudioMode by mutableStateOf(AudioMode.MAIN)
    var currentQuality by mutableStateOf(StreamQuality.fromValue(initialQuality))
    var currentStreamSource by mutableStateOf(StreamSource.KONOMITV)

    var playerError by mutableStateOf<String?>(null)
    var retryKey by mutableIntStateOf(0)
    var isPlayerPlaying by mutableStateOf(false)

    var isSignalInfoVisible by mutableStateOf(false)
    var signalInfo by mutableStateOf(SignalMetadata())

    var sseStatus by mutableStateOf("Standby")
    var sseDetail by mutableStateOf(AppStrings.SSE_CONNECTING)

    var dualSseStatus by mutableStateOf("Standby")
    var dualSseDetail by mutableStateOf(AppStrings.SSE_CONNECTING)

    var isDualDisplayMode by mutableStateOf(false)
    var activeDualPlayerIndex by mutableIntStateOf(0)
    var dualRightChannel by mutableStateOf<Channel?>(null)

    var leftScreenWeight by mutableFloatStateOf(1f)
    var rightScreenWeight by mutableFloatStateOf(1f)

    var isCenterLongPressHandled by mutableStateOf(false)
    var lastInteractionTime by mutableLongStateOf(System.currentTimeMillis())

    // ★ 追加: 戻るキー長押し判定用
    var backKeyDownTime by mutableLongStateOf(0L)
    var isBackKeyLongPressed by mutableStateOf(false)

    var showMirakurunDualWarningDialog by mutableStateOf(false)
    var previousStreamSource by mutableStateOf<StreamSource?>(null)

    fun toggleDualScreenSize() {
        if (activeDualPlayerIndex == 0) {
            if (leftScreenWeight == 1f && rightScreenWeight == 1f) {
                leftScreenWeight = 0.6f
                rightScreenWeight = 1.4f
            } else if (leftScreenWeight < 1f) {
                leftScreenWeight = 1.4f
                rightScreenWeight = 0.6f
            } else {
                leftScreenWeight = 1f
                rightScreenWeight = 1f
            }
        } else {
            if (rightScreenWeight == 1f && leftScreenWeight == 1f) {
                rightScreenWeight = 0.6f
                leftScreenWeight = 1.4f
            } else if (rightScreenWeight < 1f) {
                rightScreenWeight = 1.4f
                leftScreenWeight = 0.6f
            } else {
                rightScreenWeight = 1f
                leftScreenWeight = 1f
            }
        }
    }

    fun analyzePlayerError(error: PlaybackException): String {
        val cause = error.cause
        return when {
            cause is HttpDataSource.InvalidResponseCodeException -> {
                when (cause.responseCode) {
                    404 -> AppStrings.ERR_CHANNEL_NOT_FOUND
                    503 -> AppStrings.ERR_TUNER_FULL
                    else -> String.format(AppStrings.ERR_SERVER_HTTP, cause.responseCode)
                }
            }

            cause is HttpDataSource.HttpDataSourceException -> {
                when (cause.cause) {
                    is java.net.ConnectException -> AppStrings.ERR_CONNECTION_REFUSED
                    is java.net.SocketTimeoutException -> AppStrings.ERR_TIMEOUT
                    else -> AppStrings.ERR_NETWORK
                }
            }

            cause is java.io.IOException -> String.format(AppStrings.ERR_DATA_READ, cause.message)
            else -> "${AppStrings.ERR_UNKNOWN}\n(${error.errorCodeName})"
        }
    }

    fun retry() {
        playerError = null
        retryKey++
    }

    fun handleKeyEvent(
        keyEvent: KeyEvent,
        isSubMenuOpen: Boolean,
        isMiniListOpen: Boolean,
        showOverlay: Boolean,
        isManualOverlay: Boolean,
        isPinnedOverlay: Boolean,
        currentChannelItem: Channel,
        groupedChannels: Map<String, List<Channel>>,
        scrollState: ScrollState,
        scope: CoroutineScope,
        onChannelSelect: (Channel) -> Unit,
        onShowOverlayChange: (Boolean) -> Unit,
        onManualOverlayChange: (Boolean) -> Unit,
        onPinnedOverlayChange: (Boolean) -> Unit,
        onSubMenuToggle: (Boolean) -> Unit,
        onMiniListToggle: (Boolean) -> Unit,
        onShowToast: (String) -> Unit,
        // ★ 追加: コールバック
        onPiPRequested: () -> Unit,
        onBackPressed: () -> Unit
    ): Boolean {
        if (this.playerError != null || isSubMenuOpen || isMiniListOpen) return false

        val keyCode = keyEvent.nativeKeyEvent.keyCode
        val isActionDown = keyEvent.type == KeyEventType.KeyDown
        val isActionUp = keyEvent.type == KeyEventType.KeyUp
        val repeatCount = keyEvent.nativeKeyEvent.repeatCount

        if (isActionDown) {
            this.lastInteractionTime = System.currentTimeMillis()
        }

        // 非公式パッチ: CH+/CH- (チャンネルボタン) でチャンネル送り
        // ミニ番組表と同じ並び (グループ順の平坦リスト) で前後のチャンネルへ切り替える。端はループ
        if (keyCode == android.view.KeyEvent.KEYCODE_CHANNEL_UP || keyCode == android.view.KeyEvent.KEYCODE_CHANNEL_DOWN) {
            if (isActionDown && repeatCount == 0) {
                val flatChannels = groupedChannels.values.flatten()
                if (flatChannels.size >= 2) {
                    val currentIndex = flatChannels.indexOfFirst { it.id == currentChannelItem.id }
                    val delta = if (keyCode == android.view.KeyEvent.KEYCODE_CHANNEL_UP) 1 else -1
                    val nextIndex = (currentIndex + delta + flatChannels.size) % flatChannels.size
                    onChannelSelect(flatChannels[nextIndex])
                }
            }
            return true
        }

        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
            if (isActionDown) {
                if (keyEvent.nativeKeyEvent.repeatCount > 0 && !isCenterLongPressHandled) {
                    isCenterLongPressHandled = true
                    return true
                }
                return true
            } else if (isActionUp) {
                if (isCenterLongPressHandled) {
                    isCenterLongPressHandled = false
                    return true
                } else {
                    if (this.isDualDisplayMode) {
                        this.toggleDualScreenSize()
                        return true
                    }
                    if (showOverlay) {
                        onShowOverlayChange(false)
                        onManualOverlayChange(false)
                        onPinnedOverlayChange(true)
                    } else if (isPinnedOverlay) {
                        onPinnedOverlayChange(false)
                    } else {
                        onShowOverlayChange(true)
                        onManualOverlayChange(true)
                        onPinnedOverlayChange(false)
                    }
                    return true
                }
            }
        }

        // ★ 戻るキー長押しの実装
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK || keyCode == android.view.KeyEvent.KEYCODE_ESCAPE) {
            if (this.isDualDisplayMode) {
                // 2画面時はPiPを許可せず、2画面の解除を行う
                if (isActionDown) {
                    // ★ 修正: サブメニュー等の他のレイヤーが開いていてDownイベントが消費された場合は記録しない
                    if (repeatCount == 0) {
                        backKeyDownTime = System.currentTimeMillis()
                    }
                    return true
                } else if (isActionUp) {
                    // ★ 修正: この画面で正常にDownイベントをキャッチしていた場合のみ、解除処理を行う
                    if (backKeyDownTime > 0) {
                        this.isDualDisplayMode = false
                        this.leftScreenWeight = 1f
                        this.rightScreenWeight = 1f
                        this.previousStreamSource?.let {
                            this.currentStreamSource = it
                            this.previousStreamSource = null
                        }
                    }
                    // リセットして次の入力に備える
                    backKeyDownTime = 0L
                    return true
                }
                return true
            }

            if (isActionDown) {
                if (repeatCount == 0) {
                    backKeyDownTime = System.currentTimeMillis()
                    isBackKeyLongPressed = false
                } else {
                    val elapsed = System.currentTimeMillis() - backKeyDownTime
                    if (!isBackKeyLongPressed && elapsed > 500) {
                        isBackKeyLongPressed = true
                        onPiPRequested()
                    }
                }
                return true
            } else if (isActionUp) {
                val elapsed = System.currentTimeMillis() - backKeyDownTime
                if (!isBackKeyLongPressed && elapsed < 500) {
                    onBackPressed()
                }
                backKeyDownTime = 0L
                isBackKeyLongPressed = false
                return true
            }
        }

        if (!isActionDown) return false

        if (!isMiniListOpen) {
            if (this.isDualDisplayMode) {
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT || keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                    this.activeDualPlayerIndex =
                        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) 0 else 1
                    return true
                }
            } else {
                val currentGroupList =
                    groupedChannels.values.find { list -> list.any { it.id == currentChannelItem.id } }
                if (currentGroupList != null) {
                    val currentIndex =
                        currentGroupList.indexOfFirst { it.id == currentChannelItem.id }
                    if (currentIndex != -1) {
                        when (keyCode) {
                            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                                onManualOverlayChange(false)
                                onPinnedOverlayChange(false)
                                onShowOverlayChange(false)
                                onChannelSelect(currentGroupList[if (currentIndex > 0) currentIndex - 1 else currentGroupList.size - 1])
                                return true
                            }

                            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                onManualOverlayChange(false)
                                onPinnedOverlayChange(false)
                                onShowOverlayChange(false)
                                onChannelSelect(currentGroupList[if (currentIndex < currentGroupList.size - 1) currentIndex + 1 else 0])
                                return true
                            }
                        }
                    }
                }
            }
        }

        when (keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                if (showOverlay && isManualOverlay) {
                    scope.launch { scrollState.animateScrollTo(scrollState.value - 200) }
                    return true
                }
                if (!showOverlay && !isPinnedOverlay && !isMiniListOpen) {
                    onSubMenuToggle(true)
                    return true
                }
            }

            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (showOverlay && isManualOverlay) {
                    scope.launch { scrollState.animateScrollTo(scrollState.value + 200) }
                    return true
                }
                if (!showOverlay && !isPinnedOverlay && !isMiniListOpen) {
                    onMiniListToggle(true)
                    return true
                }
            }
        }
        return false
    }
}

@Composable
fun rememberLivePlayerState(
    context: Context,
    initialQuality: String
): LivePlayerState {
    return remember(initialQuality) {
        LivePlayerState(context, initialQuality)
    }
}