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

    private var currentMediaUri: Uri? = null
    private var currentSong: Song? = null

    // Track the last TITLE to avoid sending duplicate lines back-to-back
    // (same lyric line can repeat in a song, but consecutive duplicates are noise)
    private var lastSentText: String? = null

    override fun onHook() {
        YLog.debug(tag = TAG, msg = "进程: $packageName/$processName")

        // Ensure Bluetooth A2DP is reported as "on" so that Symfonium's
        // Bluetooth-llyrics mode can activate on ROMs that check this flag.
        AndroidUtils.openBluetoothA2dpOn(appClassLoader)

        onAppLifecycle {
            onCreate {
                initProvider()
            }
        }

        hookMediaSession()
        hookEmbeddedLyrics()
    }

    // ── Bluetooth-lyrics capture (MediaSession TITLE overwrites) ──────────
    // Symfonium's "Bluetooth lyrics" switch does NOT expose a separate lyrics
    // API. Instead it overwrites MediaSession's TITLE field with the current
    // lyric line on every progression, which gets sent to the car / Bluetooth
    // screen via AVRCP.  By hooking setMetadata() we intercept every one of
    // those TITLE updates and forward them as real-time lyric text.
    private fun hookMediaSession() {
        "android.media.session.MediaSession".toClass()
            .resolve()
            .apply {
                firstMethod {
                    name = "setPlaybackState"
                    parameters(PlaybackState::class.java)
                }.hook {
                    after {
                        val state = args[0] as? PlaybackState
                        lyriconProvider?.player?.setPlaybackState(state)
                    }
                }

                firstMethod {
                    name = "setMetadata"
                    parameters("android.media.MediaMetadata")
                }.hook {
                    after {
                        val metadata = args[0] as? MediaMetadata ?: return@after

                        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                        YLog.debug(tag = TAG, msg = "metadata: $title - $artist")

                        // Forward every TITLE change as a real-time lyric line.
                        // While Bluetooth lyrics is ON, title IS the current
                        // lyric line; when OFF, title equals the song name (which
                        // won't change during playback, so it's sent only once).
                        if (!title.isNullOrBlank()) {
                            if (title != lastSentText) {
                                lastSentText = title
                                lyriconProvider?.player?.sendText(title)
                            }
                        } else {
                            lyriconProvider?.player?.sendText(null)
                        }
                    }
                }
            }
    }

    // ── Embedded-lyrics (local files with LRC tags) ───────────────────────
    // When Symfonium plays a local file we try to read an embedded LRC tag.
    // This provides a complete time-synced lyric document that is richer than
    // the line-by-line TITLE stream.
    private fun hookEmbeddedLyrics() {
        Uri::class.java.name.toClass()
            .resolve()
            .apply {
                firstMethod {
                    name = "parse"
                    parameters(String::class.java)
                }.hook {
                    after {
                        val uri = args[0] as String
                        if (!uri.startsWith("content://media/external/audio/")) {
                            return@after
                        }

                        val result = this.result as Uri
                        if (currentMediaUri == result) {
                            return@after
                        }
                        YLog.debug(tag = TAG, msg = "load uri: $uri")
                        currentMediaUri = result

                        val lyric = fetchLyricFromTag(result)
                        setLyric(uri, lyric)
                    }
                }
            }
    }

    private fun setLyric(id: String, lyric: String?) {
        val document = EnhanceLrcParser.parse(lyric)
        // Note: we don't have a MediaMetadata reference here — the song
        // metadata belongs to the TITLE-capture path.  We still deliver
        // whatever we can extract from the embedded tag.
        setSong(
            Song(
                id = id,
                name = null,
                artist = null,
                lyrics = document.lines
            )
        )
    }

    private fun setSong(song: Song) {
        if (currentSong == song) {
            YLog.debug(tag = TAG, msg = "skip same song: ${song.name}")
            return
        }
        currentSong = song
        lyriconProvider?.player?.setSong(song)
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

    private fun initProvider() {
        val context = appContext ?: return
        lyriconProvider = LyriconFactory.createProvider(
            context = context,
            providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
            playerPackageName = context.packageName,
            logo = ProviderLogo.fromSvg(Constants.ICON)
        ).apply { register() }
    }
}