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
import com.sd.lib.hikvision.HikPlayer
import com.sd.lib.hikvision.HikVisionException
import com.sd.lib.hikvision.HikVisionExceptionLogin
import com.sd.lib.hikvision.HikVisionExceptionLoginAccount
import com.sd.lib.hikvision.HikVisionExceptionLoginLocked
import com.sd.lib.hikvision.HikVisionExceptionNotInit
import com.sd.lib.hikvision.HikVisionExceptionPlayFailed

class SampleActivity : ComponentActivity() {
  private val _player by lazy { HikPlayer.create(_callback) }
  private var _tips by mutableStateOf("")

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    _player.init(
      ip = "192.168.100.110",
      username = "admin1",
      password = "admin1",
      streamType = 1,
    )
    setContent {
      AppTheme {
        Content(
          player = _player,
          tips = _tips,
        )
      }
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    _player.release()
  }

  /** 回调对象 */
  private val _callback = object : HikPlayer.Callback() {
    override fun onError(e: HikVisionException) {
      logMsg { "onError:$e" }
      _tips = when (e) {
        is HikVisionExceptionNotInit -> "未初始化"
        is HikVisionExceptionLogin -> "登录失败(${e.code})"
        is HikVisionExceptionLoginAccount -> "登录失败，用户名或者密码错误"
        is HikVisionExceptionLoginLocked -> "登录失败，账号被锁定"
        is HikVisionExceptionPlayFailed -> "播放失败(${e.code})"
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
  player: HikPlayer,
  tips: String,
) {
  Box {
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
        player.init(ip = "192.168.100.110", username = "admin110", password = "admin110")
      }) {
        Text(text = "Play110")
      }
      Spacer(Modifier.width(16.dp))
      Button(onClick = {
        player.init(ip = "192.168.100.120", username = "admin120", password = "admin120")
      }) {
        Text(text = "Play120")
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