package com.lumena.android.settings

import android.content.Context
import com.lumena.android.agent.core.TinyJevModel
import com.lumena.android.agent.core.TinyJevWeights

object TinyJevAssetLoader {
    const val ASSET_NAME = "tinyjev-v1.properties"

    fun load(context: Context): TinyJevModel {
        val weights = runCatching {
            val text = context.assets
                .open(ASSET_NAME)
                .bufferedReader()
                .use { it.readText() }
            TinyJevWeights.parseProperties(text)
        }.getOrElse {
            TinyJevWeights.embeddedFallback()
        }

        return TinyJevModel(weights)
    }
}
