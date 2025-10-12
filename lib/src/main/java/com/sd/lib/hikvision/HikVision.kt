package com.sd.lib.hikvision

import android.os.Looper
import android.util.Log
import com.hikvision.netsdk.ExceptionCallBack
import com.hikvision.netsdk.HCNetSDK
import com.hikvision.netsdk.NET_DVR_DEVICEINFO_V30
import com.hikvision.netsdk.SDKError
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object HikVision {
  /** 是否已经初始化 */
  private var _hasInit = false
  /** 是否调试模式 */
  private var _debug = false

  /** IP对应的登录信息 */
  private val _loginInfo: MutableMap<String, LoginInfo> = mutableMapOf()

  private val _loginInfoFlow = MutableSharedFlow<HikLoginInfo>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
  private val _sdkEventFlow = MutableSharedFlow<HikSDKEvent>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

  internal val loginInfoFlow: Flow<HikLoginInfo> = _loginInfoFlow.asSharedFlow()
  internal val sdkEventFlow: Flow<HikSDKEvent> = _sdkEventFlow.asSharedFlow()

  /** 初始化 */
  @JvmStatic
  @JvmOverloads
  fun init(
    /** 是否调试模式，日志tag:HikVisionSDK */
    debug: Boolean = false,
  ): Boolean {
    synchronized(this@HikVision) {
      if (_hasInit) return true
      _debug = debug
      _hasInit = HCNetSDK.getInstance().NET_DVR_Init()
      log { "init:$_hasInit" }
      return _hasInit.also { init ->
        if (init) {
          log {
            val version = HCNetSDK.getInstance().NET_DVR_GetSDKVersion()
            val buildVersion = HCNetSDK.getInstance().NET_DVR_GetSDKBuildVersion()
            "version:$version|buildVersion:$buildVersion"
          }
          HCNetSDK.getInstance().NET_DVR_SetExceptionCallBack(_exceptionCallback)
            .also { log { "NET_DVR_SetExceptionCallBack ret:$it" } }
        }
      }
    }
  }

  /**
   * 登录并返回userID，需要捕获异常[HikVisionException]，
   * 注意：此方法不能在主线程调用，否则会抛异常[IllegalStateException]
   */
  @Throws(HikVisionException::class)
  @Synchronized
  internal fun login(
    /** IP */
    ip: String,
    /** 用户名 */
    username: String,
    /** 密码 */
    password: String,
  ): Int {
    check(Looper.myLooper() !== Looper.getMainLooper())
    require(ip.isNotEmpty())
    require(username.isNotEmpty())
    require(password.isNotEmpty())

    // 登录配置
    val config = LoginConfig(
      username = username,
      password = password
    )

    // 检查登录信息是否已存在
    _loginInfo[ip]?.also { info ->
      if (info.config == config) {
        return info.userID
      } else {
        logout(ip)
      }
    }

    // 开始登录
    val userID = HCNetSDK.getInstance().NET_DVR_Login_V30(
      ip,
      8000,
      username,
      password,
      NET_DVR_DEVICEINFO_V30(),
    )

    if (userID >= 0) {
      // 登录成功
      log { "login success ip:$ip|userID:$userID" }
      _loginInfo[ip] = LoginInfo(config = config, userID = userID)
      notifyLoginInfo(ip = ip, userID = userID)
      return userID
    }

    // 登录失败
    val code = getSDKLastErrorCode()
    log { "login failed ip:$ip|userID:$userID|code:$code" }
    code.asHikVisionExceptionNotInit()?.also { throw it }

    when (code) {
      // 用户名或者密码错误
      SDKError.NET_DVR_PASSWORD_ERROR,
        // 密码输入格式不正确
      SDKError.NET_DVR_PASSWORD_FORMAT_ERROR,
        -> {
        HikVisionExceptionLoginAccount(code = code, ip = ip, username = username, password = password)
      }

      // 账号被锁定
      SDKError.NET_DVR_USER_LOCKED -> {
        HikVisionExceptionLoginLocked(code = code, ip = ip, username = username, password = password)
      }

      else -> {
        HikVisionExceptionLogin(code = code, ip = ip, username = username, password = password)
      }
    }.also { throw it }
  }

  /** 退出登录 */
  private fun logout(ip: String) {
    _loginInfo.remove(ip)?.also { info ->
      notifyLoginInfo(ip = ip, userID = null)
      HCNetSDK.getInstance().NET_DVR_Logout_V30(info.userID).also {
        log { "logout ip:$ip|userID:${info.userID}|ret:$it" }
      }
    }
  }

  private fun notifyLoginInfo(ip: String, userID: Int?) {
    log { "notifyLoginInfo ip:$ip|userID:$userID" }
    _loginInfoFlow.tryEmit(HikLoginInfo(ip = ip, userID = userID))
  }

  private val _exceptionCallback = ExceptionCallBack { type, userID, handle ->
    log { "ExceptionCallBack type:$type|userID:$userID|handle:$handle" }
    _sdkEventFlow.tryEmit(HikSDKEvent(type = type, userID = userID))
  }

  internal inline fun log(block: () -> String) {
    if (_debug) {
      val msg = block()
      if (msg.isNotEmpty()) {
        Log.i("HikVisionSDK", msg)
      }
    }
  }

  /** 登录配置 */
  private data class LoginConfig(
    val username: String,
    val password: String,
  )

  /** 登录信息 */
  private data class LoginInfo(
    val config: LoginConfig,
    val userID: Int,
  )
}

internal data class HikLoginInfo(
  val ip: String,
  val userID: Int?,
)

internal data class HikSDKEvent(
  val type: Int,
  val userID: Int,
)