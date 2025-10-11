package com.sd.lib.hikvision

import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import android.view.Surface
import android.view.SurfaceHolder
import com.hikvision.netsdk.HCNetSDK
import com.hikvision.netsdk.NET_DVR_PREVIEWINFO
import java.lang.ref.WeakReference

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

  /** 回调接口，所有方法都在主线程回调 */
  open class Callback {
    /** 错误 */
    open fun onError(e: HikVisionException) = Unit

    /** 开始播放 */
    open fun onStartPlay() = Unit
    /** 停止播放 */
    open fun onStopPlay() = Unit

    /** 重连 */
    open fun onReconnect() = Unit
    /** 重连成功 */
    open fun onReconnectSuccess() = Unit
  }

  companion object {
    /**
     * 创建[HVPlayer]，内部使用弱引用保存[callback]，因此外部需要强引用保存[callback]
     */
    @JvmStatic
    fun create(callback: Callback): HVPlayer {
      val handler = HikVision.mainHandler
      return HVPlayerImpl(
        callback = MainCallback(callback, handler),
        handler = handler,
      )
    }
  }
}

private class HVPlayerImpl(
  private val callback: HVPlayer.Callback,
  private val handler: Handler,
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
    if (ip.isEmpty()) return false
    if (username.isEmpty()) return false
    if (password.isEmpty()) return false

    log { "init ip:$ip|streamType:$streamType" }
    cancelRetryTask()
    val loginResult = runCatching {
      HikVision.login(ip = ip, username = username, password = password)
    }

    // 登录成功
    loginResult.onSuccess { data ->
      initLoginUser(userID = data)
      initPlayConfig(ip = ip, streamType = streamType)
    }

    // 登录失败
    loginResult.onFailure { e ->
      initLoginUser(userID = null)
      val error = (e as? HikVisionException) ?: HikVisionException(cause = e)
      callback.onError(error)
      when (error) {
        is HikVisionExceptionLoginAccount -> {
          // 用户名或者密码错误，不重试
        }
        is HikVisionExceptionLoginLocked -> {
          // 账号被锁定，不重试
        }
        else -> {
          startRetryTask(error) { init(ip = ip, username = username, password = password, streamType = streamType) }
        }
      }
    }

    HikVision.addCallback(_hikVisionCallback)
    return loginResult.isSuccess
  }

  @Synchronized
  override fun setSurface(surface: Surface?) {
    if (_surface != surface) {
      log { "setSurface surface:$surface" }
      _surface = surface
      restartPlayIfNeed()
    }
  }

  @Synchronized
  override fun startPlay() {
    log { "startPlay" }
    _requirePlay = true
    startPlayInternal()
  }

  @Synchronized
  override fun stopPlay() {
    log { "stopPlay" }
    _requirePlay = false
    cancelRetryTask()
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

    // 取消重试任务
    cancelRetryTask()

    // 播放信息
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
      val code = getSDKLastErrorCode()
      log { "startPlayInternal failed code:$code|userID:$userID|streamType:${playConfig.streamType}|playHandle:$playHandle" }
      val error = code.asHikVisionExceptionNotInit() ?: HikVisionExceptionPlayFailed(code = code)
      callback.onError(error)
      startRetryTask(error) { startPlayInternal() }
    } else {
      // 播放成功
      log { "startPlayInternal success userID:$userID|streamType:${playConfig.streamType}|playHandle:$playHandle" }
      callback.onStartPlay()
    }
  }

  /** 停止播放 */
  private fun stopPlayInternal() {
    val playHandle = _playHandle
    if (playHandle < 0) return
    callback.onStopPlay()
    HCNetSDK.getInstance().NET_DVR_StopRealPlay(playHandle).also { ret ->
      log { "stopPlayInternal playHandle:$playHandle|ret:$ret" }
    }
    _playHandle = -1
  }

  private fun restartPlayIfNeed() {
    stopPlayInternal()
    startPlayInternal()
  }

  override fun release() {
    log { "release" }
    HikVision.removeCallback(_hikVisionCallback)
    synchronized(this@HVPlayerImpl) {
      stopPlay()
      _userID = null
      _playConfig = null
      _surface = null
    }
  }

  private val _hikVisionCallback = object : HikVision.Callback {
    override fun onUser(ip: String, userID: Int?) {
      synchronized(this@HVPlayerImpl) {
        if (ip == _playConfig?.ip) {
          log { "HikVision.Callback.onUser userID:$userID" }
          initLoginUser(userID)
        }
      }
    }

    override fun onException(type: Int, userID: Int) {
      synchronized(this@HVPlayerImpl) {
        if (userID == _userID) {
          log { "HikVision.Callback.onException type:$type|userID:$userID" }
          when (type) {
            HCNetSDK.EXCEPTION_RECONNECT -> callback.onReconnect()
            HCNetSDK.PREVIEW_RECONNECTSUCCESS -> callback.onReconnectSuccess()
          }
        }
      }
    }
  }

  @Synchronized
  private fun initLoginUser(userID: Int?) {
    if (_userID != userID) {
      log { "initLoginUser userID:$userID" }
      _userID = userID
      restartPlayIfNeed()
    }
  }

  @Synchronized
  private fun initPlayConfig(ip: String, streamType: Int) {
    val config = PlayConfig(ip = ip, streamType = streamType)
    if (_playConfig != config) {
      log { "initPlayConfig ip:$ip|streamType:$streamType" }
      _playConfig = config
      restartPlayIfNeed()
    }
  }

  private data class PlayConfig(
    val ip: String,
    val streamType: Int,
  )

  /** 重试任务 */
  private var _retryTask: RetryTask? = null

  /** 开始重试任务 */
  @Synchronized
  private fun startRetryTask(
    exception: HikVisionException,
    task: Runnable,
  ) {
    if (exception is HikVisionExceptionNotInit) {
      // 如果没有初始化，则尝试初始化
      HikVision.init()
    }
    cancelRetryTask()
    RetryTask(task).also { retryTask ->
      _retryTask = retryTask
      handler.postDelayed(retryTask, 5_000L)
      log { "startRetryTask ${exception.javaClass.simpleName} task:$retryTask" }
    }
  }

  /** 取消重试任务 */
  @Synchronized
  private fun cancelRetryTask() {
    _retryTask?.also { retryTask ->
      log { "cancelRetryTask task:$retryTask" }
      _retryTask = null
      handler.removeCallbacks(retryTask)
    }
  }

  private inner class RetryTask(
    private val task: Runnable,
  ) : Runnable {
    override fun run() {
      log { "RetryTask run task:${this@RetryTask}" }
      synchronized(this@HVPlayerImpl) {
        if (_retryTask === this@RetryTask) {
          _retryTask = null
        }
      }
      task.run()
    }
  }

  init {
    log { "created" }
  }

  private inline fun log(block: () -> String) {
    HikVision.log {
      val instance = "${this@HVPlayerImpl.javaClass.simpleName}@${Integer.toHexString(this@HVPlayerImpl.hashCode())}"
      "$instance ${block()}"
    }
  }
}

private class MainCallback(
  callback: HVPlayer.Callback,
  private val handler: Handler,
) : HVPlayer.Callback() {
  private val _callback = WeakReference(callback)
  private val callback get() = _callback.get()

  override fun onError(e: HikVisionException) {
    handler.post { callback?.onError(e) }
  }

  override fun onStartPlay() {
    handler.post { callback?.onStartPlay() }
  }

  override fun onStopPlay() {
    handler.post { callback?.onStopPlay() }
  }

  override fun onReconnect() {
    handler.post { callback?.onReconnect() }
  }

  override fun onReconnectSuccess() {
    handler.post { callback?.onReconnectSuccess() }
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