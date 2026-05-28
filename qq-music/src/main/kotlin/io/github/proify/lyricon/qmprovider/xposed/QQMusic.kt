/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.qmprovider.xposed

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.util.Log
import androidx.core.content.ContextCompat
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.lyric.model.lyricMetadataOf
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo
import io.github.proify.qrckit.LyricResponse

object QQMusic : YukiBaseHooker() {

    private const val TAG = "Lyricon_QQMusic"
    private const val PKG_MAIN = "com.tencent.qqmusic"
    private const val PKG_PLAYER_SERVICE = "com.tencent.qqmusic:QQPlayerService"

    private const val ACTION_LYRIC_SETTINGS_CHANGED =
        "io.github.proify.lyricon.ACTION_SETTINGS_CHANGED"
    private const val ACTION_PLAYER_READY =
        "io.github.proify.lyricon.ACTION_PLAYER_READY"
    private const val ACTION_SETTINGS_SNAPSHOT =
        "io.github.proify.lyricon.ACTION_SETTINGS_SNAPSHOT"
    private const val PREF_NAME_QQMUSIC = "qqmusicplayer"
    private const val KEY_DISPLAY_TRANS = "showtranslyric"
    private const val KEY_DISPLAY_ROMA = "showromalyric"

    private val mainProcessHook by lazy { MainProcessHook() }
    private val playerProcessHook by lazy { PlayerProcessHook() }

    override fun onHook() {
        val loader = appClassLoader ?: return
        when (processName) {
            PKG_MAIN -> mainProcessHook.hook(loader)
            PKG_PLAYER_SERVICE -> playerProcessHook.hook(loader)
        }
    }

    /**
     * 处理主进程逻辑：监听 QQ 音乐内部设置变更并广播
     *
     * 同时监听来自 PlayerService 的就绪握手广播，将当前设置值主动推送过去，
     * 解决 PlayerService 重启后无法通过跨进程 SharedPreferences 读取初始值的问题。
     */
    private class MainProcessHook {
        fun hook(loader: ClassLoader) {
            YLog.debug("Hooking Main Process: SharedPreferences interceptor")

            $$"android.app.SharedPreferencesImpl$EditorImpl".toClass(loader)
                .resolve()
                .firstMethod {
                    name = "putBoolean"
                    parameters(String::class.java, Boolean::class.java)
                }.hook {
                    after {
                        val key = args[0] as String
                        val value = args[1] as Boolean

                        if (key == KEY_DISPLAY_TRANS || key == KEY_DISPLAY_ROMA) {
                            val intent = Intent(ACTION_LYRIC_SETTINGS_CHANGED).apply {
                                putExtra("setting_key", key)
                                putExtra("setting_value", value)
                                setPackage(appContext?.packageName)
                            }
                            appContext?.sendBroadcast(intent)
                            Log.d(TAG, "Settings changed in main process: $key -> $value")
                        }
                    }
                }

            onAppLifecycle {
                onCreate {
                    registerPlayerReadyReceiver(this)
                    // 主进程启动时也主动推送一次快照
                    // 处理主进程被系统杀死重启、而 PlayerService 仍在运行的边缘情况
                    sendSettingsSnapshot(this)
                }
            }
        }

        /**
         * 监听 PlayerService 就绪握手。
         * PlayerService 启动完成后会发送 ACTION_PLAYER_READY，主进程收到后
         * 立即把当前 SharedPreferences 里的真实值以 ACTION_SETTINGS_SNAPSHOT
         * 广播回 PlayerService，彻底替代跨进程直读 prefs 的方案。
         */
        private fun sendSettingsSnapshot(application: Application) {
            val prefs = application.getSharedPreferences(PREF_NAME_QQMUSIC, Context.MODE_PRIVATE)
            val trans = prefs.getBoolean(KEY_DISPLAY_TRANS, false)
            val roma  = prefs.getBoolean(KEY_DISPLAY_ROMA, false)

            val snapshot = Intent(ACTION_SETTINGS_SNAPSHOT).apply {
                putExtra(KEY_DISPLAY_TRANS, trans)
                putExtra(KEY_DISPLAY_ROMA, roma)
                setPackage(application.packageName)
            }
            application.sendBroadcast(snapshot)
            Log.d(TAG, "Host process started, pushed settings snapshot: trans=$trans roma=$roma")
        }

        private fun registerPlayerReadyReceiver(application: Application) {
            val filter = IntentFilter(ACTION_PLAYER_READY)
            ContextCompat.registerReceiver(application, object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val ctx = context ?: return
                    // 主进程在自己进程里读取——这里是可靠的
                    val prefs = ctx.getSharedPreferences(PREF_NAME_QQMUSIC, Context.MODE_PRIVATE)
                    val trans = prefs.getBoolean(KEY_DISPLAY_TRANS, false)
                    val roma  = prefs.getBoolean(KEY_DISPLAY_ROMA, false)

                    val snapshot = Intent(ACTION_SETTINGS_SNAPSHOT).apply {
                        putExtra(KEY_DISPLAY_TRANS, trans)
                        putExtra(KEY_DISPLAY_ROMA, roma)
                        setPackage(ctx.packageName)
                    }
                    ctx.sendBroadcast(snapshot)
                    Log.d(TAG, "Player ready, pushed settings snapshot: trans=$trans roma=$roma")
                }
            }, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }
    }

    private class PlayerProcessHook : DownloadCallback {
        private var lyriconProvider: LyriconProvider? = null
        private var currentMediaId: String? = null

        fun hook(loader: ClassLoader) {
            YLog.debug("Hooking Player Process: MediaSession & Lyricon Provider")

            onAppLifecycle {
                onCreate {
                    DiskSongCache.initialize(this)
                    setupLyriconProvider(this)
                    registerSettingsReceiver(this)
                    // 所有接收器注册完毕后，通知主进程推送当前设置快照
                    sendReadyBroadcast(this)
                }
            }

            "android.media.session.MediaSession".toClass(loader)
                .resolve().apply {
                    firstMethod {
                        name = "setPlaybackState"
                        parameters(PlaybackState::class.java)
                    }.hook {
                        after {
                            val state = (args[0] as? PlaybackState)
                            lyriconProvider?.player?.setPlaybackState(state)
                        }
                    }

                    // 监听歌曲切歌
                    firstMethod {
                        name = "setMetadata"
                        parameters(MediaMetadata::class.java)
                    }.hook {
                        after {
                            val metadata = args[0] as? MediaMetadata ?: return@after
                            val mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
                                ?: return@after

                            if (mediaId.isBlank() || mediaId == currentMediaId) return@after

                            currentMediaId = mediaId
                            MediaMetadataCache.save(metadata)
                            refreshActiveSong()
                        }
                    }
                }
        }

        private fun registerSettingsReceiver(application: Application) {
            val filter = IntentFilter().apply {
                addAction(ACTION_LYRIC_SETTINGS_CHANGED)
                addAction(ACTION_SETTINGS_SNAPSHOT)
            }

            ContextCompat.registerReceiver(application, object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    when (intent?.action) {
                        ACTION_LYRIC_SETTINGS_CHANGED -> {
                            val key = intent.getStringExtra("setting_key") ?: return
                            val value = intent.getBooleanExtra("setting_value", false)
                            applySettingChange(key, value)
                        }
                        ACTION_SETTINGS_SNAPSHOT -> {
                            // 主进程在收到握手后推来的完整初始快照
                            val trans = intent.getBooleanExtra(KEY_DISPLAY_TRANS, false)
                            val roma  = intent.getBooleanExtra(KEY_DISPLAY_ROMA, false)
                            Log.d(TAG, "Received settings snapshot: trans=$trans roma=$roma")
                            lyriconProvider?.player?.setDisplayTranslation(trans)
                            lyriconProvider?.player?.setDisplayRoma(roma)
                        }
                    }
                }
            }, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }

        private fun applySettingChange(key: String, value: Boolean) {
            when (key) {
                KEY_DISPLAY_TRANS -> lyriconProvider?.player?.setDisplayTranslation(value)
                KEY_DISPLAY_ROMA  -> lyriconProvider?.player?.setDisplayRoma(value)
            }
        }

        private fun sendReadyBroadcast(application: Application) {
            val intent = Intent(ACTION_PLAYER_READY).apply {
                setPackage(application.packageName)
            }
            application.sendBroadcast(intent)
            Log.d(TAG, "Player ready broadcast sent")
        }

        private fun setupLyriconProvider(application: Application) {
            val provider = LyriconFactory.createProvider(
                context = application,
                providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
                playerPackageName = PKG_MAIN,
                logo = ProviderLogo.fromSvg(Constants.ICON)
            )

            // 不在此处读取 SharedPreferences。
            // PlayerService 进程无法可靠地跨进程读取主进程写入的 "qqmusicplayer" prefs
            // （Android 7+ 禁止 MODE_WORLD_READABLE，跨进程读只会得到空文件的默认值）。
            // 真实初始值由握手机制获取：setupLyriconProvider 完成后 sendReadyBroadcast()
            // 通知主进程，主进程在自己进程内读取 prefs 后通过 ACTION_SETTINGS_SNAPSHOT 推回来。

            provider.register()
            this.lyriconProvider = provider
        }

        // --- 歌曲数据处理 ---

        private fun refreshActiveSong() {
            val mediaId = currentMediaId ?: return

            if (DiskSongCache.isCached(mediaId)) {
                updateLyriconSong(DiskSongCache.get(mediaId))
            } else {
                updateSongWithPlaceholder(mediaId)
            }

            DownloadManager.download(mediaId, this)
        }

        private fun updateSongWithPlaceholder(mediaId: String) {
            val metadata = MediaMetadataCache.get(mediaId)

            updateLyriconSong(
                Song(
                    id = mediaId,
                    name = metadata?.title,
                    artist = metadata?.artist,
                    metadata = lyricMetadataOf("placeholder" to "true")
                )
            )
        }

        private fun updateLyriconSong(song: Song?) {
            lyriconProvider?.player?.setSong(song)
        }

        override fun onDownloadFinished(response: LyricResponse) {
            val song = response.toLyriconSong()
            DiskSongCache.put(song)

            if (response.id == currentMediaId) {
                updateLyriconSong(song)
            }
        }

        override fun onDownloadFailed(id: String, e: Exception) {
            YLog.error("$TAG: Lyric download failed for $id", e)
        }

        private fun LyricResponse.toLyriconSong(): Song {
            val cachedMetadata = MediaMetadataCache.get(id)
            val lyrics = parsedLyric.richLyricLines.removeInvalidTranslation()
            return Song(
                id = id,
                name = cachedMetadata?.title,
                artist = cachedMetadata?.artist,
                duration = cachedMetadata?.duration ?: 0,
                lyrics = lyrics
            )
        }

        fun List<RichLyricLine>.removeInvalidTranslation() = apply {
            forEach { if (it.translation?.trim() == "//") it.translation = null }
        }
    }
}