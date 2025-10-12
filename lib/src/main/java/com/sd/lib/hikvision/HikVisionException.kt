package com.sd.lib.hikvision

import com.hikvision.netsdk.HCNetSDK
import com.hikvision.netsdk.SDKError

open class HikVisionException internal constructor(
  message: String? = null,
  cause: Throwable? = null,
) : Exception(message, cause)

/** 未初始化 */
class HikVisionExceptionNotInit internal constructor() : HikVisionException()

/** 登录失败，错误码[code] */
class HikVisionExceptionLogin internal constructor(
  val code: Int,
  val ip: String,
  val username: String,
  val password: String,
) : HikVisionException(message = "code($code)")

/** 登录失败，用户名或者密码错误 */
class HikVisionExceptionLoginAccount internal constructor(
  val code: Int,
  val ip: String,
  val username: String,
  val password: String,
) : HikVisionException(message = "code($code)")

/** 登录失败，账号被锁定 */
class HikVisionExceptionLoginLocked internal constructor(
  val code: Int,
  val ip: String,
  val username: String,
  val password: String,
) : HikVisionException(message = "code($code)")

/** 播放失败 */
class HikVisionExceptionPlayFailed internal constructor(
  val code: Int,
) : HikVisionException(message = "code($code)")

internal fun getSDKLastErrorCode(): Int {
  return HCNetSDK.getInstance().NET_DVR_GetLastError()
}

internal fun Int.asHikVisionExceptionNotInit(): HikVisionExceptionNotInit? {
  return if (this == SDKError.NET_DVR_NOINIT) HikVisionExceptionNotInit() else null
}