package com.sd.demo.compose.hikvision

import android.graphics.SurfaceTexture
import android.os.Bundle
import android.view.Surface
import android.view.TextureView
import android.view.TextureView.SurfaceTextureListener
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.sd.demo.compose.hikvision.theme.AppTheme
import com.sd.lib.hikvision.HVPlayer
import com.sd.lib.hikvision.HikVisionException

class SampleActivity : ComponentActivity() {
  private val _player = HVPlayer.create(object : HVPlayer.Callback {
    override fun onError(e: HikVisionException) {
      logMsg { "onError:$e" }
    }
  })

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    _player.init(
      ip = "192.168.100.110",
      username = "admin",
      password = "admin",
      streamType = 1,
    )
    setContent {
      AppTheme {
        Content(player = _player)
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
}

@Composable
private fun Content(player: HVPlayer) {
  Box(modifier = Modifier.fillMaxSize()) {
    AndroidTextureView(
      modifier = Modifier.fillMaxSize(),
      onSurface = { player.setSurface(it) },
    )
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