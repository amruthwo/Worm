package com.worm.app

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.crossfade
import com.worm.app.data.Library

class WormApp : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        Library.get(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .crossfade(true)
            .build()
}
