package com.sd.lib.hikvision

import android.graphics.Canvas
import android.graphics.Rect
import android.view.Surface
import android.view.SurfaceHolder
import com.hikvision.netsdk.HCNetSDK
import com.hikvision.netsdk.NET_DVR_PREVIEWINFO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal class HikPlayerImpl(
  private val callback: HikPlayer.Callback,
) : HikPlayer {
  /** 初始化标志 */
  private var _initFlag = AtomicBoolean(false)

  /** 初始化配置 */
  private val _initConfigFlow = MutableSharedFlow<InitConfig?>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
  /** 播放配置 */
  private val _playConfigFlow = MutableStateFlow(PlayConfig())

  /** 是否需要播放 */
  @Volatile
  private var _requirePlay = false
  /** 播放句柄 */
  private var _playHandle = -1

  private val _coroutineScope = MainScope()
  private val _retryHandler = HikRetryHandler(_coroutineScope)
  private val _initConfigCount = AtomicLong()

  override fun init(
    ip: String,
    username: String,
    password: String,
    streamType: Int,
  ) {
    if (ip.isEmpty()) return
    if (username.isEmpty()) return
    if (password.isEmpty()) return

    // 初始化播放器
    initPlayer()

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

  override fun setSurface(surface: Surface?) {
    _playConfigFlow.update { it.copy(surface = surface) }
  }

  override fun startPlay() {
    log { "startPlay" }
    _requirePlay = true
    startPlayInternal(_playConfigFlow.value)
  }

  override fun stopPlay() {
    log { "stopPlay" }
    _requirePlay = false
    _retryHandler.cancelRetryJob()
    stopPlayInternal()
  }

  /** 初始化播放器 */
  private fun initPlayer() {
    if (_initFlag.compareAndSet(false, true)) {
      log { "initPlayer" }
      // 监听登录信息
      _coroutineScope.launch {
        HikVision.loginInfoFlow.collect { info ->
          _playConfigFlow.update { config ->
            if (config.ip == info.ip) {
              config.copy(userID = info.userID)
            } else {
              config
            }
          }
        }
      }

      // 监听SDK事件
      _coroutineScope.launch {
        HikVision.sdkEventFlow.collect { event ->
          val config = _playConfigFlow.value
          if (config.userID == event.userID) {
            when (event.type) {
              HCNetSDK.EXCEPTION_RECONNECT -> callback.onReconnect()
              HCNetSDK.PREVIEW_RECONNECTSUCCESS -> callback.onReconnectSuccess()
            }
          }
        }
      }

      // 监听初始化配置
      _coroutineScope.launch {
        _initConfigFlow
          .filterNotNull()
          .collectLatest { handleInitConfig(it) }
      }

      // 监听播放配置
      _coroutineScope.launch {
        _playConfigFlow.collect { config ->
          log { "play config ip:${config.ip}|userID:${config.userID}|streamType:${config.streamType}|surface:${config.surface}" }
          stopPlayInternal()
          startPlayInternal(config)
        }
      }
    }
  }

  /** 开始播放 */
  @Synchronized
  private fun startPlayInternal(
    /** 播放配置 */
    config: PlayConfig,
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

    // 检查播放配置
    if (config.ip == null) return
    val userID = config.userID ?: return
    val surface = config.surface ?: return

    if (!isRetry) {
      // 取消重试任务
      _retryHandler.cancelRetryJob()
    }

    // 播放信息
    val playInfo = NET_DVR_PREVIEWINFO().apply {
      this.lChannel = 1
      this.dwStreamType = config.streamType
      this.bBlocked = 1
      this.hHwnd = CustomSurfaceHolder(surface)
    }

    // 开始播放
    val playHandle = HCNetSDK.getInstance().NET_DVR_RealPlay_V40(userID, playInfo, null)
    // 保存播放句柄
    _playHandle = playHandle

    if (playHandle >= 0) {
      // 播放成功
      log { "startPlayInternal success userID:$userID|streamType:${config.streamType}" }
      callback.onStartPlay()
    } else {
      // 播放失败
      val code = getSDKLastErrorCode()
      log { "startPlayInternal failed code:$code|userID:$userID|streamType:${config.streamType}" }
      val error = code.asHikVisionExceptionNotInit() ?: HikVisionExceptionPlayFailed(code = code)
      callback.onError(error)
      log { "startRetryJob startPlayInternal" }
      _retryHandler.startRetryJob(error) { startPlayInternal(config, isRetry = true) }
    }
  }

  /** 停止播放 */
  @Synchronized
  private fun stopPlayInternal() {
    val playHandle = _playHandle
    if (playHandle < 0) return
    callback.onStopPlay()
    HCNetSDK.getInstance().NET_DVR_StopRealPlay(playHandle)
      .also { ret -> log { "stopPlayInternal $ret playHandle:$playHandle" } }
    _playHandle = -1
  }

  override fun release() {
    log { "release" }
    _coroutineScope.coroutineContext[Job]?.cancelChildren()
    _initConfigFlow.tryEmit(null)
    _playConfigFlow.update { PlayConfig() }
    stopPlay()
    _initFlag.set(false)
  }

  /** 提交初始化配置 */
  private fun submitInitConfig(config: InitConfig) {
    if (_initFlag.get()) {
      log { "submit InitConfig ip:${config.ip}|streamType:${config.streamType}" }
      _initConfigFlow.tryEmit(config)
    }
  }

  /** 处理初始化配置 */
  private suspend fun handleInitConfig(config: InitConfig) = coroutineScope {
    val count = _initConfigCount.incrementAndGet()
    log { "handleInitConfig ($count) ... ip:${config.ip}|streamType:${config.streamType}" }
    try {
      withContext(Dispatchers.IO) {
        HikVision.login(
          ip = config.ip,
          username = config.username,
          password = config.password,
        ).let { userID ->
          log { "handleInitConfig ($count) onSuccess isActive:($isActive) ip:${config.ip}|streamType:${config.streamType}|userID:$userID" }
          Result.success(userID)
        }
      }
    } catch (error: HikVisionException) {
      log { "handleInitConfig ($count) onFailure isActive:($isActive) ip:${config.ip}|streamType:${config.streamType}|error:$error" }
      callback.onError(error)
      Result.failure(error)
    }.also {
      ensureActive()
    }.onSuccess { userID ->
      _playConfigFlow.update {
        it.copy(
          ip = config.ip,
          userID = userID,
          streamType = config.streamType,
        )
      }
    }.onFailure { e ->
      _playConfigFlow.update { it.copy(userID = null) }
      when (val error = e as HikVisionException) {
        is HikVisionExceptionLoginAccount -> {
          // 用户名或者密码错误，不重试
        }
        is HikVisionExceptionLoginLocked -> {
          // 账号被锁定，不重试
        }
        else -> {
          log { "startRetryJob submitInitConfig" }
          _retryHandler.startRetryJob(error) { submitInitConfig(config) }
        }
      }
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
    val ip: String? = null,
    val userID: Int? = null,
    val streamType: Int = 0,
    val surface: Surface? = null,
  )

  init {
    log { "created" }
  }

  private inline fun log(block: () -> String) {
    HikVision.log {
      val msg = block()
      if (msg.isNotEmpty()) {
        "${this@HikPlayerImpl.simpleID()} $msg"
      } else {
        ""
      }
    }
  }
}

private fun Any.simpleID(): String {
  return "${this.javaClass.simpleName}@${Integer.toHexString(this.hashCode())}"
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