package com.sd.lib.hikvision

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hikvision.netsdk.ExceptionCallBack
import com.hikvision.netsdk.HCNetSDK
import com.hikvision.netsdk.NET_DVR_DEVICEINFO_V30
import com.hikvision.netsdk.SDKError
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

object HikVision {
  /** 是否已经初始化 */
  private var _hasInit = false
  /** 是否调试模式 */
  private var _debug = false

  /** IP对应的登录信息 */
  private val _loginInfo: MutableMap<String, LoginInfo> = mutableMapOf()

  private val _callbacks: MutableSet<Callback> = Collections.newSetFromMap(ConcurrentHashMap())
  private val _mainCallback = MainHikVisionCallback { _callbacks }

  /** 初始化 */
  @JvmStatic
  @JvmOverloads
  fun init(
    /** 是否调试模式，日志tag:HikVisionSDK */
    debug: Boolean = false,
    callback: Callback? = null,
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

          if (callback != null) {
            addCallback(callback)
          }

          HCNetSDK.getInstance().NET_DVR_SetExceptionCallBack(_exceptionCallback)
            .also { log { "NET_DVR_SetExceptionCallBack ret:$it" } }
        }
      }
    }
  }

  /** 登录并返回userID，需要捕获异常[HikVisionException] */
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
    require(ip.isNotEmpty())
    require(username.isNotEmpty())
    require(password.isNotEmpty())

    // 登录配置
    val config = LoginConfig(
      ip = ip,
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
      config.ip,
      8000,
      config.username,
      config.password,
      NET_DVR_DEVICEINFO_V30(),
    )

    if (userID < 0) {
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
          HikVisionExceptionLoginAccount(
            code = code,
            ip = ip,
            username = username,
            password = password,
          )
        }

        // 账号被锁定
        SDKError.NET_DVR_USER_LOCKED -> {
          HikVisionExceptionLoginLocked(
            code = code,
            ip = ip,
            username = username,
            password = password,
          )
        }

        else -> {
          HikVisionExceptionLogin(
            code = code,
            ip = ip,
            username = username,
            password = password,
          )
        }
      }.also { throw it }
    }

    log { "login success ip:$ip|userID:$userID" }
    _loginInfo[ip] = LoginInfo(config = config, userID = userID)
    notifyLoginUser(ip = ip, userID = userID)
    return userID
  }

  /** 退出登录 */
  private fun logout(ip: String) {
    _loginInfo.remove(ip)?.also { info ->
      notifyLoginUser(ip = ip, userID = null)
      HCNetSDK.getInstance().NET_DVR_Logout_V30(info.userID).also {
        log { "logout ip:$ip|userID:${info.userID}|ret:$it" }
      }
    }
  }

  private fun notifyLoginUser(ip: String, userID: Int?) {
    log { "notifyLoginUser ip:$ip|userID:$userID" }
    _mainCallback.onUser(ip = ip, userID = userID)
  }

  internal fun addCallback(callback: Callback) {
    if (_callbacks.add(callback)) {
      log { "addCallback callback:$callback|size:${_callbacks.size}" }
    }
  }

  internal fun removeCallback(callback: Callback) {
    if (_callbacks.remove(callback)) {
      log { "removeCallback callback:$callback|size:${_callbacks.size}" }
    }
  }

  private val _exceptionCallback = ExceptionCallBack { type, userID, handle ->
    log { "ExceptionCallBack type:$type|userID:$userID|handle:$handle" }
    _mainCallback.onException(type = type, userID = userID)
  }

  internal inline fun log(block: () -> String) {
    if (_debug) {
      Log.i("HikVisionSDK", block())
    }
  }

  /** 登录配置 */
  private data class LoginConfig(
    /** IP */
    val ip: String,
    /** 用户名 */
    val username: String,
    /** 密码 */
    val password: String,
  )

  /** 登录信息 */
  private data class LoginInfo(
    val config: LoginConfig,
    val userID: Int,
  )

  interface Callback {
    fun onUser(ip: String, userID: Int?)
    fun onException(type: Int, userID: Int)
  }
}

private class MainHikVisionCallback(
  private val getCallbacks: () -> Iterable<HikVision.Callback>,
) : HikVision.Callback {
  private val _mainHandler = Handler(Looper.getMainLooper())

  override fun onUser(ip: String, userID: Int?) {
    _mainHandler.post {
      getCallbacks().forEach { it.onUser(ip = ip, userID = userID) }
    }
  }

  override fun onException(type: Int, userID: Int) {
    _mainHandler.post {
      getCallbacks().forEach { it.onException(type = type, userID = userID) }
    }
  }
}