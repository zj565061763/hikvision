package com.sd.lib.hikvision

import android.graphics.Canvas
import android.graphics.Rect
import android.view.Surface
import android.view.SurfaceHolder
import com.hikvision.netsdk.HCNetSDK
import com.hikvision.netsdk.NET_DVR_PREVIEWINFO

interface HVPlayer {
  /** 初始化 */
  fun init(
    /** IP */
    ip: String,
    /** 用户名 */
    username: String,
    /** 密码 */
    password: String,
    /** 0-主码流；1-子码流 */
    streamType: Int = 0,
  ): Boolean

  /** 设置预览 */
  fun setSurface(surface: Surface?)

  /** 开始播放 */
  fun startPlay()

  /** 停止播放 */
  fun stopPlay()

  /** 释放 */
  fun release()

  interface Callback {
    fun onError(e: HikVisionException)
  }

  companion object {
    @JvmStatic
    fun create(callback: Callback): HVPlayer {
      return HVPlayerImpl(callback = MainCallback(callback))
    }
  }
}

private class HVPlayerImpl(
  private val callback: HVPlayer.Callback,
) : HVPlayer {
  /** 用户ID */
  private var _userID: Int? = null
  /** 播放配置 */
  private var _playConfig: PlayConfig? = null
  /** 预览 */
  private var _surface: Surface? = null
  /** 是否需要播放 */
  private var _requirePlay = false
  /** 播放句柄 */
  private var _playHandle: Int = -1

  override fun init(
    ip: String,
    username: String,
    password: String,
    streamType: Int,
  ): Boolean {
    HikVision.log { "${this@HVPlayerImpl} init ip:$ip|streamType:$streamType" }
    val loginResult = runCatching {
      HikVision.login(ip = ip, username = username, password = password)
    }

    loginResult.onSuccess { data ->
      initLoginUser(userID = data)
      initPlayConfig(ip = ip, streamType = streamType)
    }
    loginResult.onFailure { e ->
      initLoginUser(userID = null)
      callback.onError((e as? HikVisionException) ?: HikVisionException(cause = e))
    }

    HikVision.addLoginUserCallback(_loginUserCallback)
    return loginResult.isSuccess
  }

  @Synchronized
  override fun setSurface(surface: Surface?) {
    if (_surface != surface) {
      HikVision.log { "${this@HVPlayerImpl} setSurface surface:$surface" }
      _surface = surface
      restartPlayIfNeed()
    }
  }

  @Synchronized
  override fun startPlay() {
    HikVision.log { "${this@HVPlayerImpl} startPlay" }
    _requirePlay = true
    startPlayInternal()
  }

  @Synchronized
  override fun stopPlay() {
    HikVision.log { "${this@HVPlayerImpl} stopPlay" }
    _requirePlay = false
    stopPlayInternal()
  }

  /** 开始播放 */
  private fun startPlayInternal() {
    if (!_requirePlay) {
      /** [startPlay]还未调用 */
      return
    }

    if (_playHandle >= 0) {
      // 当前正在播放
      return
    }

    val userID = _userID ?: return
    val playConfig = _playConfig ?: return
    val surface = _surface ?: return

    val playInfo = NET_DVR_PREVIEWINFO().apply {
      this.lChannel = 1
      this.dwStreamType = playConfig.streamType
      this.bBlocked = 1
      this.hHwnd = CustomSurfaceHolder(surface)
    }

    // 开始播放
    val playHandle = HCNetSDK.getInstance().NET_DVR_RealPlay_V40(userID, playInfo, null)
    // 保存播放句柄
    _playHandle = playHandle

    if (playHandle < 0) {
      // 播放失败
      val code = HCNetSDK.getInstance().NET_DVR_GetLastError()
      HikVision.log { "${this@HVPlayerImpl} startPlayInternal failed code:$code|userID:$userID|streamType:${playConfig.streamType}|playHandle:$playHandle" }
      callback.onError(HikVisionExceptionPlayFailed(code = code))
    } else {
      // 播放成功
      HikVision.log { "${this@HVPlayerImpl} startPlayInternal success userID:$userID|streamType:${playConfig.streamType}|playHandle:$playHandle" }
    }
  }

  /** 停止播放 */
  private fun stopPlayInternal() {
    val playHandle = _playHandle
    if (playHandle < 0) return
    HCNetSDK.getInstance().NET_DVR_StopRealPlay(playHandle).also { ret ->
      HikVision.log { "${this@HVPlayerImpl} stopPlayInternal playHandle:$playHandle|ret:$ret" }
    }
    _playHandle = -1
  }

  private fun restartPlayIfNeed() {
    stopPlayInternal()
    startPlayInternal()
  }

  override fun release() {
    HikVision.log { "${this@HVPlayerImpl} release" }
    HikVision.removeLoginUserCallback(_loginUserCallback)
    synchronized(this@HVPlayerImpl) {
      stopPlay()
      _userID = null
      _playConfig = null
      _surface = null
    }
  }

  private val _loginUserCallback = object : HikVision.LoginUserCallback {
    override fun onUser(ip: String, userID: Int?) {
      synchronized(this@HVPlayerImpl) {
        if (ip == _playConfig?.ip) {
          HikVision.log { "${this@HVPlayerImpl} LoginUserCallback userID:$userID" }
          initLoginUser(userID)
        }
      }
    }
  }

  @Synchronized
  private fun initLoginUser(userID: Int?) {
    if (_userID != userID) {
      HikVision.log { "${this@HVPlayerImpl} initLoginUser userID:$userID" }
      _userID = userID
      restartPlayIfNeed()
    }
  }

  @Synchronized
  private fun initPlayConfig(ip: String, streamType: Int) {
    val config = PlayConfig(ip = ip, streamType = streamType)
    if (_playConfig != config) {
      HikVision.log { "${this@HVPlayerImpl} initPlayConfig ip:$ip|streamType:$streamType" }
      _playConfig = config
      restartPlayIfNeed()
    }
  }

  private data class PlayConfig(
    val ip: String,
    val streamType: Int,
  )

  init {
    HikVision.log { "${this@HVPlayerImpl} created" }
  }
}

private class MainCallback(
  private val callback: HVPlayer.Callback,
) : HVPlayer.Callback {
  override fun onError(e: HikVisionException) {
    HikVision.mainHandler.post { callback.onError(e) }
  }
}

private class CustomSurfaceHolder(
  private val surface: Surface,
) : SurfaceHolder {
  override fun addCallback(callback: SurfaceHolder.Callback?) {
    HikVision.log { "CustomSurfaceHolder addCallback callback:$callback" }
  }

  override fun removeCallback(callback: SurfaceHolder.Callback?) {
    HikVision.log { "CustomSurfaceHolder removeCallback callback:$callback" }
  }

  override fun isCreating(): Boolean {
    HikVision.log { "CustomSurfaceHolder isCreating" }
    return false
  }

  @Deprecated("Deprecated in Java")
  override fun setType(type: Int) {
    HikVision.log { "CustomSurfaceHolder setType type:$type" }
  }

  override fun setFixedSize(width: Int, height: Int) {
    HikVision.log { "CustomSurfaceHolder setFixedSize width:$width|height:$height" }
  }

  override fun setSizeFromLayout() {
    HikVision.log { "CustomSurfaceHolder setSizeFromLayout" }
  }

  override fun setFormat(format: Int) {
    HikVision.log { "CustomSurfaceHolder setFormat format:$format" }
  }

  override fun setKeepScreenOn(screenOn: Boolean) {
    HikVision.log { "CustomSurfaceHolder setKeepScreenOn screenOn:$screenOn" }
  }

  override fun lockCanvas(): Canvas {
    HikVision.log { "CustomSurfaceHolder lockCanvas" }
    return Canvas()
  }

  override fun lockCanvas(dirty: Rect?): Canvas {
    HikVision.log { "CustomSurfaceHolder lockCanvas dirty:$dirty" }
    return Canvas()
  }

  override fun unlockCanvasAndPost(canvas: Canvas?) {
    HikVision.log { "CustomSurfaceHolder unlockCanvasAndPost canvas:$canvas" }
  }

  override fun getSurfaceFrame(): Rect {
    HikVision.log { "CustomSurfaceHolder getSurfaceFrame" }
    return Rect()
  }

  override fun getSurface(): Surface {
    HikVision.log { "CustomSurfaceHolder getSurface" }
    return surface
  }
}