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
import java.util.Collections
import java.util.LinkedHashMap

/**
 * Symfonium 播放器的 Lyricon 适配钩子实现类.
 *
 * Hook MediaSession.setMetadata 获取轨道元数据,
 * 从音频文件内嵌标签读取歌词并推送至 Lyricon 服务.
 */
open class Symfonium(val tag: String = "SymfoniumProvider") : YukiBaseHooker() {

    private val lyricTagRegex by lazy { Regex("(?i)\\b(LYRICS)\\b") }

    private var provider: LyriconProvider? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var trackJob: Job? = null

    private var currentMediaId: String? = null

    /** 线程安全 LRU 缓存: mediaId → parsed lyrics */
    private val lyricCache = Collections.synchronizedMap(object :
        LinkedHashMap<String, List<RichLyricLine>?>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<RichLyricLine>?>?): Boolean {
            return size > 16
        }
    })

    override fun onHook() {
        onAppLifecycle {
            onCreate { initProvider(this) }
            onTerminate {
                provider?.unregister()
                scope.cancel()
            }
        }
        hookMediaSession()
    }

    // ── MediaSession hooks ────────────────────────

    private fun hookMediaSession() {
        "android.media.session.MediaSession".toClass().resolve().apply {
            firstMethod {
                name = "setPlaybackState"
                parameters(PlaybackState::class.java)
            }.hook {
                after {
                    provider?.player?.setPlaybackState(args[0] as? PlaybackState)
                }
            }

            firstMethod {
                name = "setMetadata"
                parameters("android.media.MediaMetadata")
            }.hook {
                after {
                    val metadata = args[0] as? MediaMetadata ?: return@after
                    onMetadataChanged(metadata)
                }
            }
        }
    }

    private fun onMetadataChanged(metadata: MediaMetadata) {
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID) ?: title

        if (mediaId == currentMediaId) return
        currentMediaId = mediaId

        trackJob?.cancel()
        trackJob = scope.launch {
            // 先推送基础歌曲信息（清除旧歌词）
            provider?.player?.setSong(
                Song(name = title, artist = artist, duration = duration, id = mediaId)
            )

            // 缓存命中直接使用
            lyricCache[mediaId]?.let { cached ->
                if (cached != null) {
                    provider?.player?.setSong(
                        Song(id = mediaId, name = title, artist = artist,
                            duration = duration, lyrics = cached)
                    )
                }
                return@launch
            }

            val uri = tryParseUri(mediaId)
            val raw = if (uri != null) readTagFromUri(uri) else null

            val lyrics = if (!raw.isNullOrBlank()) {
                withContext(Dispatchers.Default) {
                    EnhanceLrcParser.parse(raw, duration)
                        .lines
                        .filter { !it.text.isNullOrBlank() }
                }
            } else null

            lyricCache[mediaId] = lyrics
            provider?.player?.setSong(
                Song(id = mediaId, name = title, artist = artist,
                    duration = duration, lyrics = lyrics)
            )
        }
    }

    // ── 歌词读取 ──────────────────────────────────

    private fun tryParseUri(mediaId: String): Uri? {
        return try { Uri.parse(mediaId) } catch (_: Exception) { null }
    }

    private fun readTagFromUri(uri: Uri): String? {
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

    // ── LyriconProvider 初始化 ─────────────────────

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
