/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.symfoniumprovider.xposed

import android.content.Context
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
import java.io.File

/**
 * Symfonium 播放器的 Lyricon 适配钩子实现类.
 *
 * Symfonium 使用 androidx.media3 (ExoPlayer) 作为播放引擎,
 * 本类通过 Hook Media3 的 MediaItem 切换 + MediaSession 元数据变化来同步歌词.
 */
open class Symfonium(val tag: String = "SymfoniumProvider") : YukiBaseHooker() {

    /** 匹配 ID3 标签中歌词字段的正则表达式 */
    private val lyricTagRegex by lazy { Regex("(?i)\\b(LYRICS)\\b") }

    /** Lyricon 核心提供者实例 */
    private var provider: LyriconProvider? = null

    /** 异步任务处理作用域 */
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** 当前正在运行的轨道处理 Job */
    private var trackProcessingJob: Job? = null

    /** 缓存当前歌曲信息, 避免重复推送 */
    private var currentSongId: String? = null

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
        hookExoPlayer()
    }

    // ──────────────────────────────────────────────
    // 核心: Hook Media3 ExoPlayer 的 MediaItem 变化
    // ──────────────────────────────────────────────

    /**
     * Hook Media3 ExoPlayer 添加媒体项的方法, 捕获即将播放的音频 URI.
     */
    private fun hookExoPlayer() {
        try {
            val exoPlayerClass = "androidx.media3.exoplayer.ExoPlayer".toClass().resolve()
            exoPlayerClass.apply {
                method { name = "setMediaItem" }.hook {
                    after {
                        val mediaItem = args.firstOrNull() ?: return@after
                        processExoPlayerMediaItem(mediaItem)
                    }
                }
                method { name = "addMediaItem" }.hook {
                    after {
                        val mediaItem = args.firstOrNull() ?: return@after
                        processExoPlayerMediaItem(mediaItem)
                    }
                }
                method { name = "setMediaItems" }.hook {
                    after {
                        val mediaItem = (args.firstOrNull() as? List<*>)?.firstOrNull() ?: return@after
                        processExoPlayerMediaItem(mediaItem)
                    }
                }
            }
            YLog.debug(tag = tag, msg = "Successfully hooked $exoPlayerClass")
        } catch (e: Exception) {
            YLog.warn(tag = tag, msg = "Could not hook ExoPlayer", e = e)
        }
    }

    /**
     * 从 Media3 MediaItem 中提取元数据并触发异步歌词解析.
     */
    private fun processExoPlayerMediaItem(mediaItem: Any) {
        try {
            // 反射获取 MediaItem.localConfiguration.uri
            val localConfig = mediaItem.javaClass.getDeclaredField("localConfiguration").apply {
                isAccessible = true
            }.get(mediaItem) ?: return

            val uri = localConfig.javaClass.getDeclaredField("uri").apply {
                isAccessible = true
            }.get(localConfig) as? Uri ?: return

            // 反射获取 mediaMetadata
            val mediaMetadata = mediaItem.javaClass.getDeclaredField("mediaMetadata").apply {
                isAccessible = true
            }.get(mediaItem)

            val title = mediaMetadata.javaClass.getDeclaredField("title").apply {
                isAccessible = true
            }.get(mediaMetadata)?.toString()
            val artist = mediaMetadata.javaClass.getDeclaredField("artist").apply {
                isAccessible = true
            }.get(mediaMetadata)?.toString()
            val durationMs = mediaMetadata.javaClass.getDeclaredField("durationMs").apply {
                isAccessible = true
            }.get(mediaMetadata) as? Long ?: 0L

            val mediaId = mediaItem.javaClass.getDeclaredField("mediaId").apply {
                isAccessible = true
            }.get(mediaItem) as? String

            YLog.debug(tag = tag, msg = "MediaItem: $title - $artist [${uri.scheme}]")

            executeAsyncTrackUpdate(uri, title, artist, durationMs, mediaId)

        } catch (e: Throwable) {
            YLog.error(tag = tag, msg = "Failed to extract MediaItem metadata", e = e)
        }
    }

    // ──────────────────────────────────────────────
    // 异步歌词处理
    // ──────────────────────────────────────────────

    private fun executeAsyncTrackUpdate(
        uri: Uri,
        title: String?,
        artist: String?,
        durationMs: Long,
        mediaId: String?
    ) {
        trackProcessingJob?.cancel()
        trackProcessingJob = scope.launch {
            handleTrackData(uri, title, artist, durationMs, mediaId)
        }
    }

    private suspend fun handleTrackData(
        uri: Uri,
        title: String?,
        artist: String?,
        durationMs: Long,
        mediaId: String?
    ) {
        val songId = mediaId ?: uri.toString()

        // 尝试从内嵌标签读取歌词
        var rawLyric = withContext(Dispatchers.IO) { fetchLyricFromTag(uri) }

        // 如果没有内嵌歌词, 尝试查找同目录下的 .lrc 文件
        if (rawLyric.isNullOrBlank()) {
            rawLyric = withContext(Dispatchers.IO) { fetchLyricFromLrcFile(uri) }
        }

        val song = Song(
            name = title,
            artist = artist,
            duration = durationMs,
            id = songId
        )

        if (!rawLyric.isNullOrBlank()) {
            val document = EnhanceLrcParser.parse(rawLyric, durationMs)
            song.lyrics = document.lines
        }

        if (currentSongId == songId) {
            YLog.debug(tag = tag, msg = "Skip same song: $title")
            return
        }
        currentSongId = songId

        provider?.player?.setSong(song)
        YLog.debug(tag = tag, msg = "Track updated: $title - $artist [$songId]")
    }

    // ──────────────────────────────────────────────
    // 歌词来源 1: 音频文件内嵌标签 (ID3 LYRICS frame)
    // ──────────────────────────────────────────────

    private fun fetchLyricFromTag(uri: Uri): String? {
        return try {
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
    }

    // ──────────────────────────────────────────────
    // 歌词来源 2: 同目录 .lrc 文件
    // ──────────────────────────────────────────────

    /**
     * 在音频文件同目录下查找同名的 .lrc 歌词文件.
     *
     * 支持的 URI 类型:
     * - file:///path/to/music.mp3 → /path/to/music.lrc
     * - content://media/external/audio/media/123 → 通过 MediaStore 反查文件路径
     */
    private fun fetchLyricFromLrcFile(uri: Uri): String? {
        return try {
            val audioPath = resolveFilePath(uri) ?: return null
            val lrcFile = File(audioPath.replace(Regex("\\.[^.]+$"), ".lrc"))
            if (lrcFile.isFile && lrcFile.canRead()) {
                YLog.debug(tag = tag, msg = "Found LRC file: ${lrcFile.name}")
                return lrcFile.readText()
            }
            null
        } catch (e: Exception) {
            YLog.error(tag = tag, msg = "LRC file read failed: $uri", e = e)
            null
        }
    }

    /**
     * 将 Content URI 或 File URI 解析为绝对文件路径.
     */
    private fun resolveFilePath(uri: Uri): String? {
        return try {
            when (uri.scheme) {
                "file" -> uri.path
                "content" -> {
                    val cursor = appContext?.contentResolver?.query(
                        uri,
                        arrayOf(android.provider.MediaStore.Audio.AudioColumns.DATA),
                        null, null, null
                    )
                    cursor?.use {
                        if (it.moveToFirst()) {
                            val idx = it.getColumnIndexOrThrow(android.provider.MediaStore.Audio.AudioColumns.DATA)
                            return it.getString(idx)
                        }
                    }
                    null
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
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

    // ──────────────────────────────────────────────
    // MediaSession Hook: 同步播放状态 (播放/暂停)
    // ──────────────────────────────────────────────

    private fun hookMediaSession() {
        "android.media.session.MediaSession".toClass().resolve().apply {
            firstMethod {
                name = "setPlaybackState"
                parameters(PlaybackState::class.java)
            }.hook {
                after {
                    val state = args[0] as? PlaybackState
                    provider?.player?.setPlaybackState(state)
                }
            }
        }
    }
}
