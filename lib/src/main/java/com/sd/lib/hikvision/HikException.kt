package com.sd.lib.hikvision

import com.hikvision.netsdk.HCNetSDK
import com.hikvision.netsdk.SDKError

open class HikException internal constructor(
  message: String? = null,
  cause: Throwable? = null,
) : Exception(message, cause)

/** 未初始化 */
class HikExceptionNotInit internal constructor() : HikException()

/** 登录失败，错误码[code] */
class HikExceptionLogin internal constructor(
  val code: Int,
  val ip: String,
  val username: String,
  val password: String,
) : HikException(message = "code($code)")

/** 登录失败，用户名或者密码错误 */
class HikExceptionLoginAccount internal constructor(
  val code: Int,
  val ip: String,
  val username: String,
  val password: String,
) : HikException(message = "code($code)")

/** 登录失败，账号被锁定 */
class HikExceptionLoginLocked internal constructor(
  val code: Int,
  val ip: String,
  val username: String,
  val password: String,
) : HikException(message = "code($code)")

/** 播放失败 */
class HikExceptionPlayFailed internal constructor(
  val code: Int,
) : HikException(message = "code($code)")

internal fun getSDKLastErrorCode(): Int {
  return HCNetSDK.getInstance().NET_DVR_GetLastError()
}

internal fun Int.asHikVisionExceptionNotInit(): HikExceptionNotInit? {
  return if (this == SDKError.NET_DVR_NOINIT) HikExceptionNotInit() else null
}