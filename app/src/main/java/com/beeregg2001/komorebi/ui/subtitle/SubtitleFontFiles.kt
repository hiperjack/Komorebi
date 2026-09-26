package com.beeregg2001.komorebi.ui.subtitle

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 非公式パッチ: 字幕フォント設定 (SettingsRepository.SUBTITLE_FONT) の値を、
 * libaribcaption に渡すフォントファイルの絶対パスへ解決する。
 *
 * アプリ同梱フォント (assets) は JNI から直接開けないため、初回に filesDir へ展開してそのパスを返す。
 * "default" (端末既定フォント) の場合は null。
 */
object SubtitleFontFiles {
    private const val TAG = "SubtitleFontFiles"

    // 設定値 → assets/fonts 内のファイル名
    private val bundledFonts = mapOf(
        "arib" to "rounded-mplus-1m-arib.ttf"
    )

    fun resolve(context: Context, setting: String): String? {
        val assetName = bundledFonts[setting] ?: return null
        return try {
            val dir = File(context.filesDir, "subtitle_fonts").apply { mkdirs() }
            val target = File(dir, assetName)
            val assetSize = context.assets.openFd("fonts/$assetName").use { it.length }
            if (!target.exists() || target.length() != assetSize) {
                context.assets.open("fonts/$assetName").use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
            target.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "字幕フォントの展開に失敗しました: $assetName", e)
            null
        }
    }
}
