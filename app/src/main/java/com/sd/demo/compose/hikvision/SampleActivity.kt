package com.sd.demo.compose.hikvision

import android.graphics.SurfaceTexture
import android.os.Bundle
import android.view.Surface
import android.view.TextureView
import android.view.TextureView.SurfaceTextureListener
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.sd.demo.compose.hikvision.theme.AppTheme
import com.sd.lib.hikvision.HikException
import com.sd.lib.hikvision.HikExceptionLogin
import com.sd.lib.hikvision.HikExceptionLoginAccount
import com.sd.lib.hikvision.HikExceptionLoginLocked
import com.sd.lib.hikvision.HikExceptionNotInit
import com.sd.lib.hikvision.HikExceptionPlayFailed
import com.sd.lib.hikvision.HikPlayer

private const val DEFAULT_IP = "192.168.100.110"
private const val DEFAULT_USERNAME = "admin110"
private const val DEFAULT_PASSWORD = "pwd@123456"
private const val DEFAULT_URL = "rtsp://${DEFAULT_USERNAME}:${DEFAULT_PASSWORD}@${DEFAULT_IP}:554/Streaming/Channels/101"

class SampleActivity : ComponentActivity() {
  private val _player by lazy { HikPlayer.create(_callback) }
  private var _tips by mutableStateOf("")

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // 0-主码流；1-子码流，默认0
    _player.setStreamType(1)
    // 初始化
    _player.init(ip = DEFAULT_IP, username = DEFAULT_USERNAME, password = DEFAULT_PASSWORD)
    setContent {
      AppTheme {
        Content(player = _player, tips = _tips)
      }
    }
  }

  override fun onResume() {
    super.onResume()
    _player.startPlay()
  }

  override fun onPause() {
    super.onPause()
    _player.stopPlay()
  }

  override fun onDestroy() {
    super.onDestroy()
    _player.release()
  }

  /** 回调对象 */
  private val _callback = object : HikPlayer.Callback() {
    override fun onError(e: HikException) {
      logMsg { "onError:$e" }
      _tips = when (e) {
        is HikExceptionNotInit -> "未初始化"
        is HikExceptionLogin -> "登录失败(${e.code})"
        is HikExceptionLoginAccount -> "登录失败，用户名或者密码错误(${e.code})"
        is HikExceptionLoginLocked -> "登录失败，账号被锁定(${e.code})"
        is HikExceptionPlayFailed -> "播放失败(${e.code})"
        else -> "异常:$e"
      }
    }

    override fun onStartPlay() {
      logMsg { "onStartPlay" }
      _tips = ""
    }

    override fun onStopPlay() {
      logMsg { "onStopPlay" }
      _tips = ""
    }

    override fun onReconnect() {
      logMsg { "onReconnect" }
      _tips = "重连中..."
    }

    override fun onReconnectSuccess() {
      logMsg { "onReconnectSuccess" }
      _tips = ""
    }
  }
}

@Composable
private fun Content(
  modifier: Modifier = Modifier,
  player: HikPlayer,
  tips: String,
) {
  Box(modifier = modifier.fillMaxSize()) {
    AndroidTextureView(
      modifier = Modifier.fillMaxSize(),
      onSurface = { player.setSurface(it) },
    )

    if (tips.isNotEmpty()) {
      Box(
        modifier = Modifier
          .align(Alignment.Center)
          .background(Color.Black.copy(0.3f))
          .padding(horizontal = 16.dp, vertical = 4.dp)
      ) {
        Text(
          text = tips,
          color = Color.White,
        )
      }
    }

    Row(
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .navigationBarsPadding()
    ) {
      Button(onClick = {
        player.initWithUrl(DEFAULT_URL)
      }) {
        Text(text = "default")
      }
      Spacer(Modifier.width(16.dp))
      Button(onClick = {
        player.init(ip = "192.168.100.120", username = "admin120", password = "admin120")
      }) {
        Text(text = "120")
      }
    }
  }
}

@Composable
private fun AndroidTextureView(
  modifier: Modifier = Modifier,
  onSurface: (Surface?) -> Unit,
) {
  val onSurfaceUpdated by rememberUpdatedState(onSurface)
  AndroidView(
    modifier = modifier,
    factory = { context ->
      TextureView(context).apply {
        surfaceTextureListener = object : SurfaceTextureListener {
          override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            logMsg { "onSurfaceTextureAvailable surface:$surface|width:$width|height:$height" }
            onSurfaceUpdated(Surface(surface))
          }

          override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            logMsg { "onSurfaceTextureSizeChanged surface:$surface|width:$width|height:$height" }
          }

          override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            logMsg { "onSurfaceTextureDestroyed surface:$surface" }
            onSurfaceUpdated(null)
            return true
          }

          override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
          }
        }
      }
    },
  )
}