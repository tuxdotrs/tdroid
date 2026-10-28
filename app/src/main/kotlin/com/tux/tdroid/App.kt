package com.tux.tdroid

import android.app.Application

class App : Application() {
    val runtime by lazy { Runtime(this) }
}
