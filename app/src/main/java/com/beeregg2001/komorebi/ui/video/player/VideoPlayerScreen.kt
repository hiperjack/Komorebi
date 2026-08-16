@file:OptIn(UnstableApi::class, ExperimentalAnimationApi::class, ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.video.player

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import android.view.TextureView
import android.view.View
import android.view.KeyEvent as NativeKeyEvent
import android.view.ViewGroup
import android.webkit.WebView
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.*
import androidx.media3.common.audio.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.extractor.metadata.id3.PrivFrame
import androidx.media3.ui.AspectRatioFrameLayout
import com.beeregg2001.komorebi.common.UrlBuilder
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.viewmodel.RecordViewModel
import com.beeregg2001.komorebi.viewmodel.SettingsViewModel
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.data.model.ArchivedComment
import com.beeregg2001.komorebi.data.model.AudioMode
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

private const val TAG = "VideoPlayerScreen"
private const val DEBUG_TAG = "ChapterDebug"

@UnstableApi
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun VideoPlayerScreen(
    program: RecordedProgram,
    initialPositionMs: Long = 0,
    initialQuality: String = "1080p-60fps",
    konomiIp: String,
    konomiPort: String,
    showControls: Boolean,
    onShowControlsChange: (Boolean) -> Unit,
    isSubMenuOpen: Boolean,
    onSubMenuToggle: (Boolean) -> Unit,
    isSceneSearchOpen: Boolean,
    onSceneSearchToggle: (Boolean) -> Unit,
    onBackPressed: () -> Unit,
    onShowToast: (String) -> Unit,
    // ★ 追加: PiP状態の受け取りと、要求コールバック
    isPiPMode: Boolean = false,
    onPiPRequested: () -> Unit = {},
    recordViewModel: RecordViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var currentProgram by remember { mutableStateOf(program) }
    val fetchedDetail by recordViewModel.programDetail.collectAsState()

    var isBuffering by remember { mutableStateOf(false) }

    LaunchedEffect(program.id) {
        recordViewModel.fetchProgramDetail(program.id)
    }

    LaunchedEffect(fetchedDetail) {
        if (fetchedDetail != null && fetchedDetail?.id == program.id) {
            currentProgram = fetchedDetail!!
            Log.i(
                DEBUG_TAG,
                "Fetched Detail received. Raw cmSections count: ${currentProgram.recordedVideo.cmSections?.size ?: 0}"
            )
            val merged = mergeCmSections(currentProgram.recordedVideo.cmSections)
            Log.i(DEBUG_TAG, "Merged cmSections count: ${merged.size}")
            merged.forEachIndexed { index, cmSection ->
                Log.i(
                    DEBUG_TAG,
                    "  Merged Section $index: start=${cmSection.startTime}, end=${cmSection.endTime}"
                )
            }
        }
    }

    val vs = rememberVideoPlayerState(initialQuality)

    val commentSpeedStr by settingsViewModel.commentSpeed.collectAsState()
    val commentFontSizeStr by settingsViewModel.commentFontSize.collectAsState()
    val commentOpacityStr by settingsViewModel.commentOpacity.collectAsState()
    val commentMaxLinesStr by settingsViewModel.commentMaxLines.collectAsState()
    val commentDefaultDisplayStr by settingsViewModel.commentDefaultDisplay.collectAsState()
    val subtitleCommentLayer by settingsViewModel.subtitleCommentLayer.collectAsState()
    val videoSubtitleDefaultStr by settingsViewModel.videoSubtitleDefault.collectAsState()

    val commentSpeed = commentSpeedStr.toFloatOrNull() ?: 1.0f
    val commentFontSizeScale = commentFontSizeStr.toFloatOrNull() ?: 1.0f
    val commentOpacity = commentOpacityStr.toFloatOrNull() ?: 1.0f
    val commentMaxLines = commentMaxLinesStr.toIntOrNull() ?: 0

    LaunchedEffect(commentDefaultDisplayStr) {
        vs.isCommentEnabled = commentDefaultDisplayStr == "ON"
    }
    LaunchedEffect(videoSubtitleDefaultStr) {
        vs.isSubtitleEnabled = videoSubtitleDefaultStr == "ON"
    }

    var isHeavyUiReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(800); isHeavyUiReady = true }

    val allComments = remember { mutableStateListOf<ArchivedComment>() }
    val isEmulator =
        remember { Build.FINGERPRINT.startsWith("generic") || Build.MODEL.contains("google_sdk") }
    val currentSessionId = remember(vs.currentQuality) { UUID.randomUUID().toString() }
    val webViewRef = remember { mutableStateOf<WebView?>(null) }

    val mainFocusRequester = remember { FocusRequester() }
    val subMenuFocusRequester = remember { FocusRequester() }

    var videoWidth by remember { mutableIntStateOf(0) }
    var videoHeight by remember { mutableIntStateOf(0) }
    var pixelWidthHeightRatio by remember { mutableFloatStateOf(1f) }

    var isChapterListOpen by remember { mutableStateOf(false) }
    var rightKeyDownTime by remember { mutableLongStateOf(0L) }
    var isRightKeyLongPressed by remember { mutableStateOf(false) }
    var leftKeyDownTime by remember { mutableLongStateOf(0L) }
    var isLeftKeyLongPressed by remember { mutableStateOf(false) }
    var downKeyDownTime by remember { mutableLongStateOf(0L) }
    var isDownKeyLongPressed by remember { mutableStateOf(false) }
    // 非公式パッチ: ←/→ 長押しの連続シーク用スロットル (前回シークした時刻)
    var lastRepeatSeekTime by remember { mutableLongStateOf(0L) }

    LaunchedEffect(program.recordedVideo.id) {
        allComments.clear()
        allComments.addAll(recordViewModel.getArchivedComments(program.recordedVideo.id))
    }

    val exoPlayer = remember(vs.currentQuality) {
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                ctx: Context,
                enableFloat: Boolean,
                enableParams: Boolean
            ): DefaultAudioSink? {
                return DefaultAudioSink.Builder(ctx)
                    .setEnableAudioTrackPlaybackParams(false)
                    .build()
            }
        }.apply {
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
            setEnableDecoderFallback(true)
        }

        val httpDataSourceFactory = DefaultHttpDataSource.Factory().apply {
            setUserAgent("DTVClient/1.0")
            setAllowCrossProtocolRedirects(true)
            setConnectTimeoutMs(90000)
            setReadTimeoutMs(90000)
        }

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                30000,
                30000,
                1500,
                3000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val hlsMediaSourceFactory = HlsMediaSource.Factory(httpDataSourceFactory)

        ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(hlsMediaSourceFactory)
            .setLoadControl(loadControl)
            .build().apply {
                setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)

                val mediaItem = MediaItem.Builder().setUri(
                    UrlBuilder.getVideoPlaylistUrl(
                        konomiIp,
                        konomiPort,
                        program.recordedVideo.id,
                        currentSessionId,
                        vs.currentQuality.value
                    )
                ).setMimeType(MimeTypes.APPLICATION_M3U8).build()

                setMediaItem(mediaItem)
                setAudioAttributes(
                    AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .setUsage(C.USAGE_MEDIA).build(), true
                )
                addListener(object : Player.Listener {
                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        videoWidth = videoSize.width
                        videoHeight = videoSize.height
                        pixelWidthHeightRatio = videoSize.pixelWidthHeightRatio
                    }

                    override fun onIsPlayingChanged(playing: Boolean) {
                        vs.isPlayerPlaying = playing
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        isBuffering = (playbackState == Player.STATE_BUFFERING)
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(TAG, "ExoPlayer Source Error: ${error.message}", error)
                        scope.launch {
                            isBuffering = true
                            delay(3000L)
                            prepare()
                            playWhenReady = true
                        }
                    }

                    override fun onMetadata(metadata: Metadata) {
                        if (!vs.isSubtitleEnabled) return
                        for (i in 0 until metadata.length()) {
                            val entry = metadata.get(i)
                            if (entry is PrivFrame && (entry.owner.contains(
                                    "aribb24",
                                    true
                                ) || entry.owner.contains("B24", true))
                            ) {
                                val base64Data =
                                    Base64.encodeToString(entry.privateData, Base64.NO_WRAP)
                                webViewRef.value?.post {
                                    webViewRef.value?.evaluateJavascript(
                                        "if(window.receiveSubtitleData){ window.receiveSubtitleData($currentPosition, '$base64Data'); }",
                                        null
                                    )
                                }
                            }
                        }
                    }
                })
                if (initialPositionMs > 0) seekTo(initialPositionMs)
                prepare(); playWhenReady = true
            }
    }

    LaunchedEffect(isSceneSearchOpen, isChapterListOpen) {
        if (isSceneSearchOpen || isChapterListOpen) {
            vs.wasPlayingBeforeSceneSearch = exoPlayer.isPlaying
            if (vs.wasPlayingBeforeSceneSearch) exoPlayer.pause()
        } else if (vs.wasPlayingBeforeSceneSearch) {
            exoPlayer.play()
        }
    }

    LaunchedEffect(vs.indicatorState) {
        if (vs.indicatorState != null) {
            delay(2000)
            vs.indicatorState = null
        }
    }

    DisposableEffect(vs.currentQuality, currentSessionId) {
        recordViewModel.startStreamMaintenance(
            program,
            vs.currentQuality.value,
            currentSessionId
        ) { exoPlayer.currentPosition / 1000.0 }
        onDispose { recordViewModel.stopStreamMaintenance() }
    }

    LaunchedEffect(
        showControls,
        isSubMenuOpen,
        isSceneSearchOpen,
        isChapterListOpen,
        vs.lastInteractionTime
    ) {
        if (showControls && !isSubMenuOpen && !isSceneSearchOpen && !isChapterListOpen) {
            delay(5000); onShowControlsChange(false)
        }
    }

    LaunchedEffect(isSubMenuOpen, isSceneSearchOpen, isChapterListOpen, showControls) {
        // ★ PiPモード中はフォーカスを奪わないようにする
        if (isPiPMode) return@LaunchedEffect

        delay(150)
        if (isSubMenuOpen) {
            subMenuFocusRequester.safeRequestFocus(TAG)
        } else if (!isSceneSearchOpen && !isChapterListOpen && !showControls) {
            mainFocusRequester.safeRequestFocus(TAG)
        }
    }

    // 非公式パッチ: CM 自動スキップ
    // 再生位置が CM 区間の先頭を「自然再生で跨いだ」ときだけ、区間末尾へ自動ジャンプする。
    // 跨ぎ判定 (前回位置と現在位置の差が小さい) にすることで、手動シークで CM 区間の
    // 途中に入った場合は発動せず、見たい位置を勝手に飛ばさない
    LaunchedEffect(currentProgram, exoPlayer) {
        var previousPosition = exoPlayer.currentPosition
        while (isActive) {
            delay(250)
            val position = exoPlayer.currentPosition
            if (exoPlayer.isPlaying) {
                val mergedCms = mergeCmSections(currentProgram.recordedVideo.cmSections)
                val crossed = mergedCms.firstOrNull { cm ->
                    val startMs = (cm.startTime * 1000).toLong()
                    // 前回位置が CM 開始より前 → 現在位置が CM 開始以降、かつ自然再生の進み幅 (2秒未満) の場合のみ
                    previousPosition < startMs && position >= startMs && (position - previousPosition) < 2000
                }
                if (crossed != null) {
                    val endMs = (crossed.endTime * 1000).toLong()
                    if (endMs > position) {
                        exoPlayer.seekTo(endMs)
                        vs.updateIndicator(Icons.Default.SkipNext, "CMスキップ")
                        previousPosition = endMs
                        continue
                    }
                }
            }
            previousPosition = position
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onKeyEvent { keyEvent ->
                // ★ PiPモード中はキーイベントを全てスルー（裏側のホーム画面に処理させる）
                if (isPiPMode) return@onKeyEvent false

                if (isSubMenuOpen || isSceneSearchOpen || isChapterListOpen) return@onKeyEvent false

                val keyCode = keyEvent.nativeKeyEvent.keyCode
                val repeatCount = keyEvent.nativeKeyEvent.repeatCount
                val isActionDown = keyEvent.type == KeyEventType.KeyDown
                val isActionUp = keyEvent.type == KeyEventType.KeyUp

                if (isActionDown) {
                    vs.lastInteractionTime = System.currentTimeMillis()
                }

                when (keyCode) {
                    // ★ 戻るキー長押しの実装
                    NativeKeyEvent.KEYCODE_BACK, NativeKeyEvent.KEYCODE_ESCAPE -> {
                        if (isActionDown) {
                            if (repeatCount == 0) {
                                vs.backKeyDownTime = System.currentTimeMillis()
                                vs.isBackKeyLongPressed = false
                            } else {
                                val elapsed = System.currentTimeMillis() - vs.backKeyDownTime
                                if (!vs.isBackKeyLongPressed && elapsed > 500) {
                                    vs.isBackKeyLongPressed = true
                                    onPiPRequested()
                                }
                            }
                            return@onKeyEvent true
                        } else if (isActionUp) {
                            val elapsed = System.currentTimeMillis() - vs.backKeyDownTime
                            if (!vs.isBackKeyLongPressed && elapsed < 500) {
                                onBackPressed()
                            }
                            vs.backKeyDownTime = 0L
                            vs.isBackKeyLongPressed = false
                            return@onKeyEvent true
                        }
                        false
                    }

                    NativeKeyEvent.KEYCODE_DPAD_CENTER, NativeKeyEvent.KEYCODE_ENTER -> {
                        if (isActionDown) {
                            onShowControlsChange(true)
                            vs.togglePlayPause(exoPlayer.isPlaying)
                            if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                        }
                        true
                    }

                    // 非公式パッチ: → は短押し・長押しとも +30秒シーク (長押し中は一定間隔でくり返し送る)
                    // チャプター移動は ▶▶ (KEYCODE_MEDIA_FAST_FORWARD) に割り当て直した
                    NativeKeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (isActionDown) {
                            if (repeatCount == 0) {
                                rightKeyDownTime = System.currentTimeMillis()
                                isRightKeyLongPressed = false
                            } else {
                                val elapsed = System.currentTimeMillis() - rightKeyDownTime
                                if (elapsed > 500) {
                                    isRightKeyLongPressed = true
                                    // 長押し中は 400ms ごとに +30秒 (Fire TV 標準の早送りに近い操作感)
                                    val now = System.currentTimeMillis()
                                    if (now - lastRepeatSeekTime > 400) {
                                        lastRepeatSeekTime = now
                                        onShowControlsChange(true)
                                        exoPlayer.seekTo(
                                            (exoPlayer.currentPosition + 30000).coerceAtMost(
                                                exoPlayer.duration.coerceAtLeast(1L)
                                            )
                                        )
                                        vs.updateIndicator(Icons.Default.FastForward, "+30s")
                                    }
                                }
                            }
                        } else if (isActionUp) {
                            val elapsed = System.currentTimeMillis() - rightKeyDownTime
                            if (!isRightKeyLongPressed && elapsed < 500) {
                                onShowControlsChange(true)
                                exoPlayer.seekTo(exoPlayer.currentPosition + 30000)
                                vs.updateIndicator(Icons.Default.FastForward, "+30s")
                            }
                            rightKeyDownTime = 0L
                            isRightKeyLongPressed = false
                        }
                        true
                    }

                    // 非公式パッチ: ← は短押し・長押しとも -10秒シーク (長押し中は一定間隔でくり返し戻す)
                    // チャプター移動は ◀◀ (KEYCODE_MEDIA_REWIND) に割り当て直した
                    NativeKeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (isActionDown) {
                            if (repeatCount == 0) {
                                leftKeyDownTime = System.currentTimeMillis()
                                isLeftKeyLongPressed = false
                            } else {
                                val elapsed = System.currentTimeMillis() - leftKeyDownTime
                                if (elapsed > 500) {
                                    isLeftKeyLongPressed = true
                                    // 長押し中は 400ms ごとに -10秒
                                    val now = System.currentTimeMillis()
                                    if (now - lastRepeatSeekTime > 400) {
                                        lastRepeatSeekTime = now
                                        onShowControlsChange(true)
                                        exoPlayer.seekTo(
                                            (exoPlayer.currentPosition - 10000).coerceAtLeast(0L)
                                        )
                                        vs.updateIndicator(Icons.Default.FastRewind, "-10s")
                                    }
                                }
                            }
                        } else if (isActionUp) {
                            val elapsed = System.currentTimeMillis() - leftKeyDownTime
                            if (!isLeftKeyLongPressed && elapsed < 500) {
                                onShowControlsChange(true)
                                exoPlayer.seekTo(exoPlayer.currentPosition - 10000)
                                vs.updateIndicator(Icons.Default.FastRewind, "-10s")
                            }
                            leftKeyDownTime = 0L
                            isLeftKeyLongPressed = false
                        }
                        true
                    }

                    // 非公式パッチ: ◀◀ / ▶▶ (メディアキー) でチャプター移動
                    // チャプター境界は KonomiTV の cm_sections から作られる (境界が無い録画は固定秒数にフォールバック)
                    NativeKeyEvent.KEYCODE_MEDIA_FAST_FORWARD, NativeKeyEvent.KEYCODE_MEDIA_NEXT -> {
                        if (isActionDown && repeatCount == 0) {
                            onShowControlsChange(true)
                            val duration = exoPlayer.duration.coerceAtLeast(1L)
                            val mergedCms =
                                mergeCmSections(currentProgram.recordedVideo.cmSections)
                            val boundaries = getChapterBoundaries(duration, mergedCms)
                            if (boundaries.size <= 2) {
                                exoPlayer.seekTo(
                                    (exoPlayer.currentPosition + 180_000).coerceAtMost(duration)
                                )
                                vs.updateIndicator(Icons.Default.FastForward, "+3m")
                            } else {
                                val currentPos = exoPlayer.currentPosition
                                val nextBoundary =
                                    boundaries.firstOrNull { it > currentPos + 1000 }
                                exoPlayer.seekTo(nextBoundary ?: duration)
                                vs.updateIndicator(Icons.Default.SkipNext, "次チャプター")
                            }
                        }
                        true
                    }

                    NativeKeyEvent.KEYCODE_MEDIA_REWIND, NativeKeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                        if (isActionDown && repeatCount == 0) {
                            onShowControlsChange(true)
                            val duration = exoPlayer.duration.coerceAtLeast(1L)
                            val mergedCms =
                                mergeCmSections(currentProgram.recordedVideo.cmSections)
                            val boundaries = getChapterBoundaries(duration, mergedCms)
                            if (boundaries.size <= 2) {
                                exoPlayer.seekTo(
                                    (exoPlayer.currentPosition - 60_000).coerceAtLeast(0L)
                                )
                                vs.updateIndicator(Icons.Default.FastRewind, "-1m")
                            } else {
                                val currentPos = exoPlayer.currentPosition
                                val prevBoundary =
                                    boundaries.lastOrNull { it < currentPos - 1000 }
                                exoPlayer.seekTo(prevBoundary ?: 0L)
                                vs.updateIndicator(Icons.Default.SkipPrevious, "前チャプター")
                            }
                        }
                        true
                    }

                    NativeKeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (isActionDown) {
                            if (repeatCount == 0) {
                                downKeyDownTime = System.currentTimeMillis()
                                isDownKeyLongPressed = false
                            } else {
                                val elapsed = System.currentTimeMillis() - downKeyDownTime
                                if (!isDownKeyLongPressed && elapsed > 500) {
                                    isDownKeyLongPressed = true
                                    val duration = exoPlayer.duration.coerceAtLeast(1L)
                                    val mergedCms =
                                        mergeCmSections(currentProgram.recordedVideo.cmSections)
                                    val hasChapters =
                                        getChapterBoundaries(duration, mergedCms).size > 2
                                    if (hasChapters) {
                                        isChapterListOpen = true
                                        onShowControlsChange(true)
                                    }
                                }
                            }
                        } else if (isActionUp) {
                            val elapsed = System.currentTimeMillis() - downKeyDownTime
                            if (!isDownKeyLongPressed && elapsed < 500) {
                                onShowControlsChange(true)
                                onSceneSearchToggle(true)
                            }
                            downKeyDownTime = 0L
                            isDownKeyLongPressed = false
                        }
                        true
                    }

                    NativeKeyEvent.KEYCODE_DPAD_UP -> {
                        if (isActionDown) {
                            onShowControlsChange(true)
                            onSubMenuToggle(true)
                        }
                        true
                    }

                    else -> false
                }
            }) {
        AndroidView(
            factory = { ctx ->
                AspectRatioFrameLayout(ctx).apply {
                    keepScreenOn = true

                    val textureView = TextureView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                    addView(textureView)
                }
            },
            update = { view ->
                val textureView = view.getChildAt(0) as TextureView
                exoPlayer.setVideoTextureView(textureView)

                if (videoWidth > 0 && videoHeight > 0) {
                    val ratio =
                        (videoWidth.toFloat() * pixelWidthHeightRatio) / videoHeight.toFloat()
                    view.setAspectRatio(ratio)

                    val isAnamorphic =
                        (videoWidth == 1440 && videoHeight == 1080 && pixelWidthHeightRatio == 1.0f)
                    val is16by9 = ratio >= 1.7f
                    val targetMode = if (isAnamorphic || is16by9) {
                        AspectRatioFrameLayout.RESIZE_MODE_FILL
                    } else {
                        AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                    if (view.resizeMode != targetMode) {
                        view.resizeMode = targetMode
                    }
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(mainFocusRequester)
                // ★ PiPモード中はフォーカスを受け取らないようにする
                // ★ VideoPlayerScreenにはisMiniListOpenが存在しないため削除
                .focusable(!isPiPMode && !isSubMenuOpen)
        )

        // ★ PiPモード時は字幕や実況などのリソースを食うUIはすべて非表示にし、パフォーマンスを確保する
        if (!isPiPMode) {
            val commentLayer = @Composable {
                if (isHeavyUiReady && vs.isCommentEnabled) {
                    ArchivedCommentOverlay(
                        Modifier.fillMaxSize(), allComments, { exoPlayer.currentPosition },
                        vs.isPlayerPlaying, vs.isCommentEnabled, commentSpeed,
                        commentFontSizeScale, commentOpacity, commentMaxLines, isEmulator
                    )
                }
            }

            val subtitleLayer = @Composable {
                if (isHeavyUiReady) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(-1, -1)
                                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                settings.apply {
                                    javaScriptEnabled = true; domStorageEnabled = true
                                }
                                loadUrl("file:///android_asset/subtitle_renderer.html")
                                webViewRef.value = this
                            }
                        },
                        update = {
                            it.visibility =
                                if (vs.isSubtitleEnabled) View.VISIBLE else View.INVISIBLE
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            if (subtitleCommentLayer == "CommentOnTop") {
                subtitleLayer(); commentLayer()
            } else {
                commentLayer(); subtitleLayer()
            }

            if (isBuffering) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = androidx.compose.ui.graphics.Color.White
                )
            }

            PlayerControls(
                exoPlayer,
                currentProgram.title,
                showControls && !isSubMenuOpen && !isSceneSearchOpen && !isChapterListOpen
            )

            AnimatedVisibility(
                isSceneSearchOpen,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                SceneSearchOverlay(
                    currentProgram,
                    exoPlayer.currentPosition,
                    konomiIp,
                    konomiPort,
                    {
                        exoPlayer.seekTo(it); onSceneSearchToggle(false); scope.launch {
                        delay(200); mainFocusRequester.safeRequestFocus(TAG)
                    }
                    },
                    {
                        onSceneSearchToggle(false); scope.launch {
                        delay(200); mainFocusRequester.safeRequestFocus(TAG)
                    }
                    })
            }

            AnimatedVisibility(
                isChapterListOpen,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                ChapterListOverlay(
                    currentProgram,
                    exoPlayer.currentPosition,
                    konomiIp,
                    konomiPort,
                    {
                        exoPlayer.seekTo(it)
                        isChapterListOpen = false
                        scope.launch { delay(200); mainFocusRequester.safeRequestFocus(TAG) }
                    },
                    {
                        isChapterListOpen = false
                        scope.launch { delay(200); mainFocusRequester.safeRequestFocus(TAG) }
                    }
                )
            }

            AnimatedVisibility(
                isSubMenuOpen,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut()) {
                VideoTopSubMenuUI(
                    vs.currentAudioMode, vs.currentSpeed, vs.isSubtitleEnabled,
                    vs.currentQuality, vs.isCommentEnabled, subMenuFocusRequester,
                    {
                        vs.currentAudioMode =
                            if (vs.currentAudioMode == AudioMode.MAIN) AudioMode.SUB else AudioMode.MAIN;
                        val tracks =
                            exoPlayer.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }; if (tracks.size >= 2) exoPlayer.trackSelectionParameters =
                        exoPlayer.trackSelectionParameters.buildUpon()
                            .clearOverridesOfType(C.TRACK_TYPE_AUDIO).addOverride(
                                TrackSelectionOverride(
                                    tracks[if (vs.currentAudioMode == AudioMode.SUB) 1 else 0].mediaTrackGroup,
                                    0
                                )
                            )
                            .build(); onShowToast("音声: ${if (vs.currentAudioMode == AudioMode.MAIN) "主音声" else "副音声"}")
                    },
                    {
                        val speeds = listOf(1.0f, 1.5f, 2.0f, 0.8f); vs.currentSpeed =
                        speeds[(speeds.indexOf(vs.currentSpeed) + 1) % speeds.size]; exoPlayer.setPlaybackSpeed(
                        vs.currentSpeed
                    ); onShowToast("速度: ${vs.currentSpeed}x")
                    },
                    {
                        vs.isSubtitleEnabled =
                            !vs.isSubtitleEnabled; onShowToast("字幕: ${if (vs.isSubtitleEnabled) "表示" else "非表示"}")
                    },
                    { vs.currentQuality = it; onShowToast("画質: ${it.label}") },
                    {
                        vs.isCommentEnabled =
                            !vs.isCommentEnabled; onShowToast("実況: ${if (vs.isCommentEnabled) "表示" else "非表示"}")
                    }
                )
            }
            PlaybackIndicator(vs.indicatorState)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                exoPlayer.pause(); recordViewModel.updateWatchHistory(
                    program,
                    exoPlayer.currentPosition / 1000.0
                )
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            recordViewModel.updateWatchHistory(program, exoPlayer.currentPosition / 1000.0)
            lifecycleOwner.lifecycle.removeObserver(observer); exoPlayer.release()
        }
    }
}