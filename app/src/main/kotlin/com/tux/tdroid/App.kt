package com.tux.tdroid

import android.app.Application

class App : Application() {
    val runtime by lazy { Runtime(this) }

    override fun onCreate() {
        super.onCreate()
        // Before anything reads the layout, the widgets or the style, the wallpaper service included.
        LauncherData.moveIntoConfig(this)
    }
}
