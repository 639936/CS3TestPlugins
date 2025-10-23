package com.vlxx

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.lagradost.cloudstream3.extractors.JWPlayer


@CloudstreamPlugin
class VlxxPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(VlxxProvider())
        registerExtractorAPI(JWPlayer())
    }
}