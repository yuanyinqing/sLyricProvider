/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.symfoniumprovider.xposed

import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.net.Uri
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import com.kyant.taglib.TagLib
import io.github.proify.extensions.android.AndroidUtils
import io.github.proify.lrckit.EnhanceLrcParser
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo

class Symfonium : YukiBaseHooker() {
    companion object {
        const val TAG: String = "Symfonium"
    }

    private var lyriconProvider: LyriconProvider? = null
    private val lyricTagRegex by lazy { Regex("(?i)\\b(LYRICS)\\b") }

    // Track the current track identity to deduplicate metadata events
    private var currentMediaId: String? = null

    // Track the last TITLE sent via sendText() to avoid consecutive dupes
    private var lastSentText: String? = null

    override fun onHook() {
        YLog.debug(tag = TAG, msg = "进程: $packageName/$processName")

        // Ensure Bluetooth A2DP is reported as "on" so that Symfonium's
        // Bluetooth-llyrics mode can activate on ROMs that check this flag.
        AndroidUtils.openBluetoothA2dpOn(appClassLoader)

        onAppLifecycle {
            onCreate { initProvider() }
        }

        hookMediaSession()
    }

    // ── MediaSession Hook ─────────────────────────────────────────────────
    // Two purposes:
    //  1. When Symfonium's "Bluetooth lyrics" is ON, every setMetadata()
    //     call carries the *current lyric line* in the TITLE field → capture
    //     those via sendText() for real-time line-by-line display.
    //  2. When a new track starts, the mediaId (if it's a content:// URI)
    //     lets us read an embedded LRC tag from the audio file → deliver
    //     the full time-synced lyric document via setSong().
    private fun hookMediaSession() {
        "android.media.session.MediaSession".toClass()
            .resolve()
            .apply {
                // ── Playback state ──────────────────────────────────────
                firstMethod {
                    name = "setPlaybackState"
                    parameters(PlaybackState::class.java)
                }.hook {
                    after {
                        val state = args[0] as? PlaybackState
                        lyriconProvider?.player?.setPlaybackState(state)
                    }
                }

                // ── Metadata (title / artist / mediaId / duration) ──────
                firstMethod {
                    name = "setMetadata"
                    parameters("android.media.MediaMetadata")
                }.hook {
                    after {
                        val metadata = args[0] as? MediaMetadata ?: return@after
                        handleMetadata(metadata)
                    }
                }
            }
    }

    private fun handleMetadata(metadata: MediaMetadata) {
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)

        if (title.isNullOrBlank()) return

        // ── Bluetooth-lyrics capture ────────────────────────────────────
        // While Symfonium's "Bluetooth lyrics" is ON, every TITLE change
        // IS a lyric line.  We forward it as real-time text.
        // When the feature is OFF, TITLE stays equal to the song name
        // (no change → sendText called at most once per track).
        if (title != lastSentText) {
            lastSentText = title
            lyriconProvider?.player?.sendText(title)
        }

        // ── Track-level dedup (setSong path only) ───────────────────────
        // Use mediaId as the dedup key.  When mediaId is absent (streaming)
        // we only deliver lyrics via sendText() above — there is no
        // content URI to read embedded tags from anyway.
        if (mediaId != null && mediaId == currentMediaId) return
        currentMediaId = mediaId

        YLog.debug(tag = TAG, msg = "metadata: $title - $artist [id=$mediaId]")

        // ── Attempt embedded LRC via mediaId ────────────────────────────
        val uri = tryParseUri(mediaId)
        val rawLyric = if (uri != null) fetchLyricFromTag(uri) else null
        val document = EnhanceLrcParser.parse(rawLyric, duration)

        val song = Song(
            id = mediaId ?: title,
            name = title,
            artist = artist,
            duration = duration,
            lyrics = document.lines
        )
        lyriconProvider?.player?.setSong(song)
    }

    // ── Embedded-lyric helpers ────────────────────────────────────────────

    private fun tryParseUri(candidate: String?): Uri? {
        if (candidate.isNullOrBlank()) return null
        return try {
            Uri.parse(candidate)
        } catch (_: Exception) { null }
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
        YLog.error(tag = TAG, msg = "Failed to fetch lyric tag from $uri", e = e)
        null
    }

    // ── Provider lifecycle ────────────────────────────────────────────────

    private fun initProvider() {
        val context = appContext ?: return
        lyriconProvider = LyriconFactory.createProvider(
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
