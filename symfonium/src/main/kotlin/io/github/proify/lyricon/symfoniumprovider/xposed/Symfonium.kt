/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.symfoniumprovider.xposed

import android.content.Context
import android.net.Uri
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import com.kyant.taglib.TagLib
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import io.github.proify.cloudlyric.CloudLyrics
import io.github.proify.cloudlyric.SearchOptions
import io.github.proify.cloudlyric.provider.lrclib.LrcLibProvider
import io.github.proify.cloudlyric.provider.qq.QQMusicProvider
import io.github.proify.extensions.isChinese
import io.github.proify.lrckit.EnhanceLrcParser
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

open class Symfonium(val tag: String = "SymfoniumProvider") : YukiBaseHooker() {

    private val lyricTagRegex by lazy { Regex("(?i)\\b(LYRICS)\\b") }

    private var provider: LyriconProvider? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var trackJob: Job? = null
    private var currentMediaId: String? = null

    private val cloudLyrics by lazy {
        CloudLyrics(
            if (Locale.getDefault().isChinese()) listOf(QQMusicProvider())
            else listOf(LrcLibProvider())
        )
    }

    override fun onHook() {
        onAppLifecycle {
            onCreate { initProvider(this) }
            onTerminate {
                provider?.unregister()
                scope.cancel()
            }
        }
        // 只 hook 系统 MediaSession，Symfonium 确认使用这个
        try {
            val cls = XposedHelpers.findClass("android.media.session.MediaSession", null)
            XposedHelpers.findAndHookMethod(cls, "setMetadata",
                XposedHelpers.findClass("android.media.MediaMetadata", null),
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        onMetadataChanged(param.args[0])
                    }
                })
        } catch (e: Exception) {
            YLog.error(tag = tag, msg = "Failed to hook MediaSession", e = e)
        }
    }

    private fun onMetadataChanged(metadata: Any) {
        val title = getMetaString(metadata, "METADATA_KEY_TITLE") ?: return
        val artist = getMetaString(metadata, "METADATA_KEY_ARTIST")
        val duration = getMetaLong(metadata, "METADATA_KEY_DURATION")
        val mediaId = getMetaString(metadata, "METADATA_KEY_MEDIA_ID") ?: title

        val trackId = mediaId
        if (trackId == currentMediaId) return
        currentMediaId = trackId

        trackJob?.cancel()
        trackJob = scope.launch { handleTrackData(title, artist, duration, mediaId) }
    }

    private suspend fun handleTrackData(
        title: String, artist: String?, duration: Long, mediaId: String
    ) {
        // 先清除旧歌词
        updateSong(Song(name = title, artist = artist, duration = duration, id = mediaId))

        // 1. 尝试本地标签（仅当 mediaId 是合法 URI）
        val uri = tryParseUri(mediaId)
        if (uri != null) {
            val raw = withContext(Dispatchers.IO) { fetchLyricFromTag(uri) }
            if (!raw.isNullOrBlank()) {
                val doc = EnhanceLrcParser.parse(raw, duration)
                val lines = doc.lines.filter { !it.text.isNullOrBlank() }
                updateSong(Song(id = mediaId, name = title, artist = artist,
                    duration = duration, lyrics = lines))
                return
            }
        }

        // 2. 网络搜索（Symfonium 的主要歌词来源）
        searchOnline(title, artist, duration, mediaId)
    }

    private suspend fun searchOnline(
        title: String, artist: String?, duration: Long, mediaId: String
    ) {
        try {
            val results = cloudLyrics.search(SearchOptions().apply {
                trackName = title
                artistName = artist ?: ""
            })
            val lyric = results.firstOrNull()?.lyrics?.rich ?: return
            if (mediaId != currentMediaId) return
            updateSong(Song(id = mediaId, name = title, artist = artist,
                duration = duration, lyrics = lyric))
        } catch (e: Exception) {
            YLog.error(tag = tag, msg = "Online search failed: $title", e = e)
        }
    }

    private fun fetchLyricFromTag(uri: Uri): String? = try {
        appContext?.contentResolver?.openFileDescriptor(uri, "r")?.use { pfd ->
            TagLib.getMetadata(pfd.dup().detachFd())?.let { meta ->
                meta.propertyMap.entries.firstOrNull { (k, _) ->
                    lyricTagRegex.matches(k)
                }?.value?.firstOrNull()
            }
        }
    } catch (e: Exception) { null }

    // ── 反射工具 ──

    private fun tryParseUri(id: String): Uri? {
        if (!id.startsWith("content://") && !id.startsWith("file://")) return null
        return try { Uri.parse(id) } catch (_: Exception) { null }
    }

    private fun getMetaString(obj: Any, keyName: String): String? = try {
        val key = Class.forName("android.media.MediaMetadata").getDeclaredField(keyName).get(null) as String
        obj.javaClass.getMethod("getString", String::class.java).invoke(obj, key) as? String
    } catch (e: Exception) { null }

    private fun getMetaLong(obj: Any, keyName: String): Long = try {
        val key = Class.forName("android.media.MediaMetadata").getDeclaredField(keyName).get(null) as String
        obj.javaClass.getMethod("getLong", String::class.java).invoke(obj, key) as? Long ?: 0L
    } catch (e: Exception) { 0L }

    private fun updateSong(song: Song?) {
        provider?.player?.setSong(song)
    }

    private fun initProvider(context: Context) {
        provider = LyriconFactory.createProvider(
            context = context,
            providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
            playerPackageName = context.packageName,
            logo = ProviderLogo.fromSvg(Constants.ICON)
        ).apply {
            player.setDisplayTranslation(true)
            register()
        }
    }
}
