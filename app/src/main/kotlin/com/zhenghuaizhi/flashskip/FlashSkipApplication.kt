package com.zhenghuaizhi.flashskip

import android.app.Application

class FlashSkipApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
