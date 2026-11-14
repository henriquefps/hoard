package dev.hfps.hoard.app

import android.app.Application

class HoardApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
