package com.sd.demo.compose.hikvision

import android.app.Application
import com.sd.lib.hikvision.HikVision

class App : Application() {
  override fun onCreate() {
    super.onCreate()
    HikVision.init(debug = true, callback = _hikVisionCallback)
  }

  private val _hikVisionCallback = object : HikVision.Callback {
    override fun onUser(ip: String, userID: Int?) {
      logMsg { "HikVision.Callback.onUser ip:$ip|userID:$userID" }
    }

    override fun onException(type: Int, userID: Int) {
      logMsg { "HikVision.Callback.onException type:$type|userID:$userID" }
    }
  }
}