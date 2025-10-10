package com.sd.lib.hikvision

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