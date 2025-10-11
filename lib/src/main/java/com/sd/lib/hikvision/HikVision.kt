package com.sd.lib.hikvision

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hikvision.netsdk.ExceptionCallBack
import com.hikvision.netsdk.HCNetSDK
import com.hikvision.netsdk.NET_DVR_DEVICEINFO_V30
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

  internal val mainHandler = Handler(Looper.getMainLooper())

  /** 初始化 */
  @JvmStatic
  fun init(debug: Boolean = false): Boolean {
    synchronized(this@HikVision) {
      if (_hasInit) return true
      _debug = debug
      _hasInit = HCNetSDK.getInstance().NET_DVR_Init()
      log { "init:$_hasInit" }
      if (_hasInit) {
        HCNetSDK.getInstance().NET_DVR_SetExceptionCallBack(_exceptionCallback)
          .also { log { "NET_DVR_SetExceptionCallBack ret:$it" } }
      }
      return _hasInit
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
    checkInit()
    require(ip.isNotEmpty())
    require(username.isNotEmpty())
    require(password.isNotEmpty())

    val config = LoginConfig(
      ip = ip,
      username = username,
      password = password
    )

    _loginInfo[ip]?.also { info ->
      if (info.config == config) {
        return info.userID
      } else {
        logout(ip)
      }
    }

    val userID = HCNetSDK.getInstance().NET_DVR_Login_V30(
      config.ip,
      8000,
      config.username,
      config.password,
      NET_DVR_DEVICEINFO_V30(),
    )

    if (userID < 0) {
      // 登录失败
      val code = HCNetSDK.getInstance().NET_DVR_GetLastError()
      log { "login failed ip:$ip|userID:$userID|code:$code" }
      throw HikVisionExceptionLogin(
        code = code,
        ip = ip,
        username = username,
        password = password,
      )
    }

    log { "login success ip:$ip|userID:$userID" }
    _loginInfo[ip] = LoginInfo(config = config, userID = userID)
    notifyCallbacksLoginUser(ip = ip, userID = userID)
    return userID
  }

  /** 退出登录 */
  private fun logout(ip: String) {
    _loginInfo.remove(ip)?.also { info ->
      notifyCallbacksLoginUser(ip = ip, userID = null)
      HCNetSDK.getInstance().NET_DVR_Logout_V30(info.userID).also {
        log { "logout ip:$ip|userID:${info.userID}|ret:$it" }
      }
    }
  }

  private fun notifyCallbacksLoginUser(ip: String, userID: Int?) {
    log { "notifyCallbacksLoginUser ip:$ip|userID:$userID" }
    mainHandler.post {
      _callbacks.forEach { it.onUser(ip = ip, userID = userID) }
    }
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

  private fun checkInit() {
    if (!_hasInit) {
      throw HikVisionExceptionNotInit()
    }
  }

  private val _exceptionCallback = ExceptionCallBack { type, userID, handle ->
    log { "ExceptionCallBack type:$type|userID:$userID|handle:$handle" }
    mainHandler.post {
      _callbacks.forEach { it.onException(type = type, userID = userID) }
    }
  }

  internal inline fun log(block: () -> String) {
    if (_debug) {
      Log.i("HikVision", block())
    }
  }

  private data class LoginConfig(
    /** IP */
    val ip: String,
    /** 用户名 */
    val username: String,
    /** 密码 */
    val password: String,
  )

  private data class LoginInfo(
    val config: LoginConfig,
    val userID: Int,
  )

  internal interface Callback {
    fun onUser(ip: String, userID: Int?)
    fun onException(type: Int, userID: Int)
  }
}