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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

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
  private val _initConfigFlow = MutableSharedFlow<InitConfig>()
  private val _initSuccessFlow = MutableSharedFlow<InitSuccessData>()
  private val _initFailureFlow = MutableSharedFlow<InitFailureData>()

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
        _initConfigFlow.collectLatest { handleInitConfig(it) }
      }
      _coroutineScope.launch {
        _initSuccessFlow.collect { it.handleInitSuccess() }
      }
      _coroutineScope.launch {
        _initFailureFlow.collect { it.handleInitFailure() }
      }
    }

    // 取消重试任务
    cancelRetryJob()

    _coroutineScope.launch {
      _initConfigFlow.emit(
        InitConfig(
          ip = ip,
          username = username,
          password = password,
          streamType = streamType,
        )
      )
    }
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
    cancelRetryJob()
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
      cancelRetryJob()
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
      startRetryJob(error) { startPlayInternal(isRetry = true) }
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
      _initFlag.set(false)
    }
  }

  /** 处理初始化配置 */
  private suspend fun handleInitConfig(config: InitConfig) = coroutineScope {
    log { "handleInitConfig ip:${config.ip}|streamType:${config.streamType}" }
    try {
      // 开始登录
      withContext(Dispatchers.IO) {
        HikVision.login(
          ip = config.ip,
          username = config.username,
          password = config.password,
        ).let { Result.success(it) }
      }
    } catch (error: HikVisionException) {
      callback.onError(error)
      Result.failure(error)
    }.onSuccess { userID ->
      // 登录成功
      launch {
        _initSuccessFlow.emit(
          InitSuccessData(
            ip = config.ip,
            streamType = config.streamType,
            userID = userID,
          )
        )
      }
    }.onFailure { e ->
      // 登录失败
      launch {
        _initFailureFlow.emit(
          InitFailureData(
            config = config,
            error = e as HikVisionException,
          )
        )
      }
    }
  }

  /** 处理初始化成功 */
  private fun InitSuccessData.handleInitSuccess() {
    log { "handleInitSuccess ip:${ip}|streamType:${streamType}|userID:${userID}" }
    initLoginUser(userID)
    initPlayConfig(ip = ip, streamType = streamType)
  }

  /** 处理初始化失败 */
  private fun InitFailureData.handleInitFailure() {
    log { "handleInitFailure error:${error}" }
    initLoginUser(userID = null)
    when (error) {
      is HikVisionExceptionLoginAccount -> {
        // 用户名或者密码错误，不重试
      }
      is HikVisionExceptionLoginLocked -> {
        // 账号被锁定，不重试
      }
      else -> {
        startRetryJob(error) {
          init(
            ip = config.ip,
            username = config.username,
            password = config.password,
            streamType = config.streamType
          )
        }
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

  /** 初始化成功数据 */
  private data class InitSuccessData(
    val ip: String,
    val streamType: Int,
    val userID: Int,
  )

  /** 初始化失败数据 */
  private data class InitFailureData(
    val config: InitConfig,
    val error: HikVisionException,
  )

  /** 播放配置 */
  private data class PlayConfig(
    val ip: String,
    val streamType: Int,
  )

  /** 重试任务 */
  private var _retryJob: Job? = null

  /** 开始重试任务 */
  @Synchronized
  private fun startRetryJob(
    error: HikVisionException,
    block: () -> Unit,
  ) {
    cancelRetryJob()
    _coroutineScope.launch {
      // 如果没有初始化，则尝试初始化
      if (error is HikVisionExceptionNotInit) HikVision.init()
      delay(5_000)
      block()
    }.also { job ->
      log { "startRetryJob ${error.javaClass.simpleName} job:$job" }
      _retryJob = job
      job.invokeOnCompletion { releaseRetryJob(job) }
    }
  }

  /** 取消重试任务 */
  @Synchronized
  private fun cancelRetryJob() {
    _retryJob?.also { job ->
      log { "cancelRetryJob job:$job" }
      _retryJob = null
      job.cancel()
    }
  }

  @Synchronized
  private fun releaseRetryJob(job: Job) {
    if (_retryJob === job) {
      _retryJob = null
      log { "releaseRetryJob job:$job" }
    }
  }

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