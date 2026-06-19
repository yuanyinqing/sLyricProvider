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
        YLog.info(tag = tag, msg = "Symfonium provider starting, process=$processName")

        onAppLifecycle {
            onCreate { initProvider(this) }
            onTerminate {
                provider?.unregister()
                scope.cancel()
            }
        }

        hookMediaSessionRaw()
    }

    // ── 直接用 XposedBridge hook，绕过 KavaRef 兼容层 ──

    private fun hookMediaSessionRaw() {
        // 系统类用 boot classloader (null)，support 库用 app classloader
        val targets = listOf(
            "android.media.session.MediaSession" to null,
            "android.support.v4.media.session.MediaSessionCompat" to appContext?.classLoader
        )

        for ((clsName, loader) in targets) {
            try {
                val cls = XposedHelpers.findClass(clsName, loader)
                XposedBridge.hookAllMethods(cls, "setMetadata", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val metadata = param.args.firstOrNull() ?: return
                        onMetadataChanged(metadata)
                    }
                })
                YLog.info(tag = tag, msg = "Raw hooked $clsName.setMetadata")
            } catch (e: Exception) {
                YLog.warn(tag = tag, msg = "Cannot hook $clsName: ${e.message}")
            }
        }
    }

    // ── 元数据处理（反射兼容多种 Metadata 类型）──

    private fun onMetadataChanged(metadata: Any) {
        val title = getMetaString(metadata, "METADATA_KEY_TITLE")
        val artist = getMetaString(metadata, "METADATA_KEY_ARTIST")
        val duration = getMetaLong(metadata, "METADATA_KEY_DURATION")
        val mediaId = getMetaString(metadata, "METADATA_KEY_MEDIA_ID")

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

    private fun getMetaString(metadata: Any, keyName: String): String? {
        return try {
            val cls = if (keyName.startsWith("METADATA_KEY")) {
                Class.forName("android.media.MediaMetadata")
            } else {
                metadata.javaClass
            }
            val key = cls.getDeclaredField(keyName).get(null) as String
            metadata.javaClass.getMethod("getString", String::class.java).invoke(metadata, key) as? String
        } catch (e: Exception) { null }
    }

    private fun getMetaLong(metadata: Any, keyName: String): Long {
        return try {
            val cls = Class.forName("android.media.MediaMetadata")
            val key = cls.getDeclaredField(keyName).get(null) as String
            metadata.javaClass.getMethod("getLong", String::class.java).invoke(metadata, key) as? Long ?: 0L
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
