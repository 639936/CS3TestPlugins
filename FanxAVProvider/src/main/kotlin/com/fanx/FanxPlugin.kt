package com.fanx

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class FanxPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FanxProvider())
        registerExtractorAPI(Seekplayer())
    }
}