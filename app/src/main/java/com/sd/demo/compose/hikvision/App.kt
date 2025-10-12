package com.sd.demo.compose.hikvision

import android.app.Application
import com.sd.lib.hikvision.HikVision

class App : Application() {
  override fun onCreate() {
    super.onCreate()
    HikVision.init(debug = true)
  }
}