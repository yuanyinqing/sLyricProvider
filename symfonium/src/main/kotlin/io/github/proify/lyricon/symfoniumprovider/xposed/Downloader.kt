/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

@file:Suppress("unused")

package io.github.proify.lyricon.symfoniumprovider.xposed

import com.highcapable.yukihookapi.hook.log.YLog
import io.github.proify.cloudlyric.CloudLyrics
import io.github.proify.cloudlyric.ProviderLyrics
import io.github.proify.cloudlyric.provider.lrclib.LrcLibProvider
import io.github.proify.cloudlyric.provider.qq.QQMusicProvider
import io.github.proify.extensions.isChinese
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale

object Downloader {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val cloudLyrics = CloudLyrics(
        if (Locale.getDefault().isChinese()) {
            listOf(QQMusicProvider())
        } else {
            listOf(LrcLibProvider())
        }
    )

    fun search(
        tag: String,
        trackName: String,
        artistName: String,
        onResult: (List<ProviderLyrics>) -> Unit
    ) {
        scope.launch {
            try {
                val results = cloudLyrics.search {
                    this.trackName = trackName
                    this.artistName = artistName
                }
                onResult(results)
            } catch (e: Exception) {
                YLog.error(tag = tag, msg = "Online search failed: $trackName", e = e)
                onResult(emptyList())
            }
        }
    }
}
