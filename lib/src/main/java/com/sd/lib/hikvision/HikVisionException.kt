package com.sd.lib.hikvision

import com.hikvision.netsdk.HCNetSDK
import com.hikvision.netsdk.SDKError

open class HikVisionException(
  message: String? = null,
  cause: Throwable? = null,
) : Exception(message, cause)

/** 未初始化 */
class HikVisionExceptionNotInit : HikVisionException()

/** 登录失败，错误码[code] */
class HikVisionExceptionLogin(
  val code: Int,
  val ip: String,
  val username: String,
  val password: String,
) : HikVisionException(message = "code($code)")

/** 播放失败 */
class HikVisionExceptionPlayFailed(val code: Int) : HikVisionException(message = "code($code)")

internal fun getSDKLastErrorCode(): Int {
  return HCNetSDK.getInstance().NET_DVR_GetLastError()
}

internal fun Int.asHikVisionExceptionNotInit(): HikVisionExceptionNotInit? {
  return if (this == SDKError.NET_DVR_NOINIT) HikVisionExceptionNotInit() else null
}