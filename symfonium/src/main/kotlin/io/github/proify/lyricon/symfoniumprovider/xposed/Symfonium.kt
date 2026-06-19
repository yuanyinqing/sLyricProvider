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
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
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

open class Symfonium(val tag: String = "SymfoniumProvider") : YukiBaseHooker() {

    private val lyricTagRegex by lazy { Regex("(?i)\\b(LYRICS)\\b") }

    private var provider: LyriconProvider? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var trackProcessingJob: Job? = null

    private var currentMediaId: String? = null

    private val lyricCache = Collections.synchronizedMap(object :
        LinkedHashMap<String, List<RichLyricLine>?>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<RichLyricLine>?>?): Boolean {
            return size > 16
        }
    })

    override fun onHook() {
        try {
            YLog.warn(tag = tag, msg = "=== Symfonium onHook entering, process=$processName ===")

            onAppLifecycle {
                onCreate {
                    YLog.warn(tag = tag, msg = "=== Symfonium app onCreate ===")
                    initProvider(this)
                }
                onTerminate {
                    YLog.warn(tag = tag, msg = "=== Symfonium app onTerminate ===")
                    provider?.unregister()
                    scope.cancel()
                }
            }

            // 诊断：hook ExoPlayer.play() 验证 hook 机制
            tryDiagnosticHook()

            hookMediaSessionRaw()
        } catch (e: Throwable) {
            YLog.error(tag = tag, msg = "onHook crashed", e = e)
        }
    }

    private fun tryDiagnosticHook() {
        val targets = listOf(
            "androidx.media3.exoplayer.ExoPlayer" to "play",
            "androidx.media3.exoplayer.ExoPlayer" to "setMediaItem",
            "androidx.media3.exoplayer.ExoPlayer" to "prepare",
            "androidx.media3.common.Player" to "play",
        )
        for ((cls, method) in targets) {
            try {
                val c = XposedHelpers.findClass(cls, appContext?.classLoader)
                XposedBridge.hookAllMethods(c, method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        YLog.warn(tag = tag, msg = ">>> $cls.$method called")
                    }
                })
                YLog.warn(tag = tag, msg = "Diag hooked: $cls.$method")
            } catch (e: Exception) {
                YLog.warn(tag = tag, msg = "Diag FAIL: $cls.$method - ${e.message}")
            }
        }
    }

    // ── 直接用 XposedBridge hook，绕过 KavaRef 兼容层 ──

    private fun hookMediaSessionRaw() {
        // 1. 系统 MediaSession（旧版）
        tryHookSetMetadata(
            "android.media.session.MediaSession", null,
            "setMetadata", "android.media.MediaMetadata"
        )
        // 2. MediaSessionCompat
        tryHookSetMetadata(
            "android.support.v4.media.session.MediaSessionCompat", appContext?.classLoader,
            "setMetadata", "android.support.v4.media.MediaMetadataCompat"
        )
        // 3. Media3 MediaSession（Symfonium 实际使用）
        tryHookSetMetadata(
            "androidx.media3.session.MediaSession", appContext?.classLoader,
            "setMediaMetadata", "androidx.media3.common.MediaMetadata"
        )
    }

    private fun tryHookSetMetadata(
        className: String, classLoader: ClassLoader?,
        methodName: String, paramClassName: String
    ) {
        try {
            val cls = XposedHelpers.findClass(className, classLoader)
            val paramCls = XposedHelpers.findClass(paramClassName, classLoader)
            XposedHelpers.findAndHookMethod(cls, methodName, paramCls, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val metadata = param.args.firstOrNull() ?: return
                    onMetadataChanged(metadata)
                }
            })
            YLog.info(tag = tag, msg = "Hooked $className.$methodName($paramClassName)")
        } catch (e: Exception) {
            YLog.warn(tag = tag, msg = "Cannot hook $className: ${e.message}")
        }
    }

    // ── 元数据处理（兼容 MediaMetadata / MediaMetadataCompat / Media3.MediaMetadata）──

    private fun onMetadataChanged(metadata: Any) {
        val title = extractTitle(metadata)
        val artist = extractArtist(metadata)
        val duration = extractDuration(metadata)
        val mediaId = extractMediaId(metadata)

        if (title.isNullOrBlank()) return

        val trackId = mediaId ?: title
        if (trackId == currentMediaId) return
        currentMediaId = trackId

        YLog.debug(tag = tag, msg = "Metadata: $title - $artist [id=$mediaId]")

        trackProcessingJob?.cancel()
        trackProcessingJob = scope.launch {
            handleTrackData(title, artist, duration, mediaId)
        }
    }

    private fun extractTitle(metadata: Any): String? {
        return tryField(metadata, "title")?.toString()
            ?: tryKey(metadata, "android.media.MediaMetadata", "METADATA_KEY_TITLE")
    }

    private fun extractArtist(metadata: Any): String? {
        return tryField(metadata, "artist")?.toString()
            ?: tryKey(metadata, "android.media.MediaMetadata", "METADATA_KEY_ARTIST")
    }

    private fun extractDuration(metadata: Any): Long {
        val d = tryField(metadata, "durationMs")
        if (d is Long) return d
        return tryKeyLong(metadata, "android.media.MediaMetadata", "METADATA_KEY_DURATION")
    }

    private fun extractMediaId(metadata: Any): String? {
        val id = tryField(metadata, "mediaId")?.toString()
        if (!id.isNullOrBlank()) return id
        val uri = tryField(metadata, "mediaUri")?.toString()
        if (!uri.isNullOrBlank()) return uri
        return tryKey(metadata, "android.media.MediaMetadata", "METADATA_KEY_MEDIA_ID")
    }

    // 尝试直接字段（Media3 风格）
    private fun tryField(obj: Any, fieldName: String): Any? {
        return try {
            obj.javaClass.getDeclaredField(fieldName).apply { isAccessible = true }.get(obj)
        } catch (e: Exception) { null }
    }

    // 尝试 key-value getter（系统 MediaMetadata 风格）
    private fun tryKey(obj: Any, metadataClassName: String, keyName: String): String? {
        return try {
            val key = Class.forName(metadataClassName).getDeclaredField(keyName).get(null) as String
            obj.javaClass.getMethod("getString", String::class.java).invoke(obj, key) as? String
        } catch (e: Exception) { null }
    }

    private fun tryKeyLong(obj: Any, metadataClassName: String, keyName: String): Long {
        return try {
            val key = Class.forName(metadataClassName).getDeclaredField(keyName).get(null) as String
            obj.javaClass.getMethod("getLong", String::class.java).invoke(obj, key) as? Long ?: 0L
        } catch (e: Exception) { 0L }
    }

    // ── 歌词处理 ──

    private suspend fun handleTrackData(
        title: String, artist: String?, duration: Long, mediaId: String?
    ) {
        updateSong(Song(name = title, artist = artist, duration = duration, id = mediaId))

        lyricCache[mediaId]?.let { cached ->
            if (cached != null) {
                updateSong(Song(id = mediaId, name = title, artist = artist,
                    duration = duration, lyrics = cached))
            }
            return
        }

        if (!mediaId.isNullOrBlank()) {
            val uri = tryParseUri(mediaId)
            if (uri != null) {
                val rawLyric = withContext(Dispatchers.IO) { fetchLyricFromTag(uri) }
                val lyrics = if (!rawLyric.isNullOrBlank()) {
                    val document = EnhanceLrcParser.parse(rawLyric, duration)
                    document.lines.filter { !it.text.isNullOrBlank() }
                } else null

                lyricCache[mediaId] = lyrics
                updateSong(Song(id = mediaId, name = title, artist = artist,
                    duration = duration, lyrics = lyrics))
            }
        }
    }

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

    private fun tryParseUri(mediaId: String?): Uri? {
        if (mediaId.isNullOrBlank()) return null
        return try { Uri.parse(mediaId) } catch (_: Exception) { null }
    }

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
