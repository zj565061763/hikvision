package com.sd.lib.hikvision

import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import com.hikvision.netsdk.HCNetSDK
import com.hikvision.netsdk.NET_DVR_PREVIEWINFO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

interface HikPlayer {
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
  )

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
     * 创建[HikPlayer]，内部使用弱引用保存[callback]，因此外部需要强引用保存[callback]
     */
    @JvmStatic
    fun create(callback: Callback): HikPlayer {
      return HikPlayerImpl(MainPlayerCallback(callback))
    }
  }
}

private class HikPlayerImpl(
  private val callback: HikPlayer.Callback,
) : HikPlayer {
  /** 初始化标志 */
  private var _initFlag = AtomicBoolean(false)

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

  private val _coroutineScope = MainScope()
  private val _retryHandler = HikRetryHandler(_coroutineScope)
  private val _initConfigFlow = MutableStateFlow<InitConfig?>(null)

  override fun init(
    ip: String,
    username: String,
    password: String,
    streamType: Int,
  ) {
    if (ip.isEmpty()) return
    if (username.isEmpty()) return
    if (password.isEmpty()) return

    if (_initFlag.compareAndSet(false, true)) {
      log { "init" }
      HikVision.addCallback(_hikVisionCallback)
      _coroutineScope.launch {
        _initConfigFlow.filterNotNull().collectLatest { config ->
          try {
            handleInitConfig(config)
          } catch (e: CancellationException) {
            log { "handleInitConfig ip:${config.ip}|streamType:${config.streamType} cancelled" }
            throw e
          }
        }
      }
    }

    // 取消重试任务
    _retryHandler.cancelRetryJob()

    // 提交初始化配置
    submitInitConfig(
      InitConfig(
        ip = ip,
        username = username,
        password = password,
        streamType = streamType,
      )
    )
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
    _retryHandler.cancelRetryJob()
    stopPlayInternal()
  }

  /** 开始播放 */
  @Synchronized
  private fun startPlayInternal(
    /** 是否重试任务触发 */
    isRetry: Boolean = false,
  ) {
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

    if (!isRetry) {
      // 取消重试任务
      _retryHandler.cancelRetryJob()
    }

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
      _retryHandler.startRetryJob(error) { startPlayInternal(isRetry = true) }
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
    synchronized(this@HikPlayerImpl) {
      _coroutineScope.coroutineContext[Job]?.cancelChildren()
      stopPlay()
      _userID = null
      _playConfig = null
      _surface = null
      _initConfigFlow.value = null
      _initFlag.set(false)
    }
  }

  /** 提交初始化配置 */
  private fun submitInitConfig(config: InitConfig) {
    if (_initFlag.get()) {
      log { "submitInitConfig ip:${config.ip}|streamType:${config.streamType}" }
      _initConfigFlow.value = config
    }
  }

  /** 处理初始化配置 */
  private suspend fun handleInitConfig(config: InitConfig) = coroutineScope {
    log { "handleInitConfig ip:${config.ip}|streamType:${config.streamType}" }
    try {
      withContext(Dispatchers.IO) {
        HikVision.login(
          ip = config.ip,
          username = config.username,
          password = config.password,
        ).let { Result.success(it) }
      }
    } catch (error: HikVisionException) {
      // 重置Flow，允许用相同的配置重试
      _initConfigFlow.value = null
      callback.onError(error)
      Result.failure(error)
    }.onSuccess { userID ->
      log { "handleInitConfig ip:${config.ip}|streamType:${config.streamType} onSuccess userID:$userID" }
      launch { onInitSuccess(config, userID) }
    }.onFailure { error ->
      log { "handleInitConfig ip:${config.ip}|streamType:${config.streamType} onFailure error:$error" }
      launch { onInitFailure(config, error as HikVisionException) }
    }
  }

  private fun onInitSuccess(config: InitConfig, userID: Int) {
    initLoginUser(userID)
    initPlayConfig(ip = config.ip, streamType = config.streamType)
  }

  private fun onInitFailure(config: InitConfig, error: HikVisionException) {
    initLoginUser(userID = null)
    when (error) {
      is HikVisionExceptionLoginAccount -> {
        // 用户名或者密码错误，不重试
      }
      is HikVisionExceptionLoginLocked -> {
        // 账号被锁定，不重试
      }
      else -> {
        _retryHandler.startRetryJob(error) { submitInitConfig(config) }
      }
    }
  }

  private val _hikVisionCallback = object : HikVision.Callback {
    override fun onUser(ip: String, userID: Int?) {
      synchronized(this@HikPlayerImpl) {
        if (ip == _playConfig?.ip) {
          log { "HikVision.Callback.onUser userID:$userID" }
          initLoginUser(userID)
        }
      }
    }

    override fun onException(type: Int, userID: Int) {
      synchronized(this@HikPlayerImpl) {
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

  /** 初始化配置 */
  private data class InitConfig(
    val ip: String,
    val username: String,
    val password: String,
    val streamType: Int,
  )

  /** 播放配置 */
  private data class PlayConfig(
    val ip: String,
    val streamType: Int,
  )

  init {
    log { "created" }
  }

  private inline fun log(block: () -> String) {
    HikVision.log {
      val instance = "${this@HikPlayerImpl.javaClass.simpleName}@${Integer.toHexString(this@HikPlayerImpl.hashCode())}"
      "$instance ${block()}"
    }
  }
}

private class MainPlayerCallback(
  callback: HikPlayer.Callback,
) : HikPlayer.Callback() {
  private val _mainHandler = Handler(Looper.getMainLooper())
  private val _callback = WeakReference(callback)
  private val callback get() = _callback.get()

  override fun onError(e: HikVisionException) {
    _mainHandler.post { callback?.onError(e) }
  }

  override fun onStartPlay() {
    _mainHandler.post { callback?.onStartPlay() }
  }

  override fun onStopPlay() {
    _mainHandler.post { callback?.onStopPlay() }
  }

  override fun onReconnect() {
    _mainHandler.post { callback?.onReconnect() }
  }

  override fun onReconnectSuccess() {
    _mainHandler.post { callback?.onReconnectSuccess() }
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