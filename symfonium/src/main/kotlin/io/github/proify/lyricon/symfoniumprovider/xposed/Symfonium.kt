/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.symfoniumprovider.xposed

import android.content.Context
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.net.Uri
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import com.kyant.taglib.TagLib
import io.github.proify.lrckit.EnhanceLrcParser
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

/**
 * Symfonium 播放器的 Lyricon 适配钩子实现类.
 *
 * 参考 PowerAmp 实现: Hook MediaSession.setMetadata 获取轨道元数据,
 * 从音频文件内嵌标签读取歌词, 失败时回退到网络搜索.
 */
open class Symfonium(val tag: String = "SymfoniumProvider") : YukiBaseHooker() {

    private val lyricTagRegex by lazy { Regex("(?i)\\b(LYRICS)\\b") }

    private var provider: LyriconProvider? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var trackProcessingJob: Job? = null

    private var currentMediaId: String? = null

    override fun onHook() {
        YLog.debug(tag = tag, msg = "Starting Symfonium hook integration...")

        onAppLifecycle {
            onCreate {
                initProvider(this)
            }
            onTerminate {
                provider?.unregister()
                scope.cancel()
            }
        }

        hookMediaSession()
    }

    // ──────────────────────────────────────────────
    // MediaSession Hook: 播放状态 + 轨道元数据
    // ──────────────────────────────────────────────

    private fun hookMediaSession() {
        "android.media.session.MediaSession".toClass().resolve().apply {
            // 播放状态
            firstMethod {
                name = "setPlaybackState"
                parameters(PlaybackState::class.java)
            }.hook {
                after {
                    val state = args[0] as? PlaybackState
                    provider?.player?.setPlaybackState(state)
                }
            }

            // 轨道元数据: 从这里获取 title、artist、duration、mediaId
            firstMethod {
                name = "setMetadata"
                parameters("android.media.MediaMetadata")
            }.hook {
                after {
                    val metadata = args[0] as? MediaMetadata ?: return@after
                    handleMetadataChange(metadata)
                }
            }
        }
    }

    private fun handleMetadataChange(metadata: MediaMetadata) {
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)

        if (title.isNullOrBlank()) return

        // 去重
        val trackId = mediaId ?: title
        if (trackId == currentMediaId) return
        currentMediaId = trackId

        YLog.debug(tag = tag, msg = "Metadata: $title - $artist [id=$mediaId]")

        // 异步处理歌词
        trackProcessingJob?.cancel()
        trackProcessingJob = scope.launch {
            handleTrackData(title, artist, duration, mediaId)
        }
    }

    // ──────────────────────────────────────────────
    // 歌词处理
    // ──────────────────────────────────────────────

    private suspend fun handleTrackData(
        title: String,
        artist: String?,
        duration: Long,
        mediaId: String?
    ) {
        // 先发送基础歌曲信息（清除旧歌词），参考 PowerAmp
        updateSong(Song(name = title, artist = artist, duration = duration, id = mediaId))

        // 尝试通过 mediaId 解析 URI 并读取内嵌歌词
        if (!mediaId.isNullOrBlank()) {
            val uri = tryParseUri(mediaId)
            if (uri != null) {
                val rawLyric = withContext(Dispatchers.IO) { fetchLyricFromTag(uri) }
                if (!rawLyric.isNullOrBlank()) {
                    val document = EnhanceLrcParser.parse(rawLyric, duration)
                    val song = Song(
                        id = mediaId, name = title, artist = artist,
                        duration = duration,
                        lyrics = document.lines.filter { !it.text.isNullOrBlank() }
                    )
                    updateSong(song)
                    YLog.info(tag = tag, msg = "Local lyric loaded: $title")
                    return
                }
            }
        }

        // 本地歌词未找到, 尝试网络搜索
        searchLyricsOnline(title, artist, duration, mediaId)
    }

    // ──────────────────────────────────────────────
    // 歌词来源 1: 音频文件内嵌标签
    // ──────────────────────────────────────────────

    private fun fetchLyricFromTag(uri: Uri): String? = try {
        appContext?.contentResolver?.openFileDescriptor(uri, "r")?.use { pfd ->
            TagLib.getMetadata(pfd.dup().detachFd())?.let { metadata ->
                metadata.propertyMap.entries.firstOrNull { (key, _) ->
                    lyricTagRegex.matches(key)
                }?.value?.firstOrNull()
            }
        }
    } catch (e: Exception) {
        YLog.error(tag = tag, msg = "TagLib failed: $uri", e = e)
        null
    }

    // ──────────────────────────────────────────────
    // 歌词来源 2: 网络搜索 (参考 PowerAmp Downloader)
    // ──────────────────────────────────────────────

    private fun searchLyricsOnline(title: String, artist: String?, duration: Long, mediaId: String?) {
        try {
            Downloader.search(
                tag = tag,
                trackName = title,
                artistName = artist ?: ""
            ) { results ->
                scope.launch {
                    val trackId = mediaId ?: title
                    if (trackId != currentMediaId) return@launch

                    val richLyrics = results.firstOrNull()?.lyrics?.rich
                    val song = Song(
                        id = mediaId, name = title, artist = artist,
                        duration = duration, lyrics = richLyrics
                    )
                    updateSong(song)
                    YLog.info(tag = tag, msg = "Online lyric loaded: $title")
                }
            }
        } catch (e: Exception) {
            YLog.error(tag = tag, msg = "Online search failed for: $title", e = e)
        }
    }

    // ──────────────────────────────────────────────
    // 工具方法
    // ──────────────────────────────────────────────

    private fun tryParseUri(mediaId: String?): Uri? {
        if (mediaId.isNullOrBlank()) return null
        return try {
            Uri.parse(mediaId)
        } catch (e: Exception) { null }
    }

    private fun updateSong(song: Song?) {
        provider?.player?.setSong(song)
    }

    // ──────────────────────────────────────────────
    // LyriconProvider 初始化
    // ──────────────────────────────────────────────

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
        YLog.debug(tag = tag, msg = "LyriconProvider initialized for ${context.packageName}")
    }
}
