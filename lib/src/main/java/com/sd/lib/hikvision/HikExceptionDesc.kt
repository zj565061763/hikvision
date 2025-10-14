package com.sd.lib.hikvision

import android.content.Context

/** 播放器异常描述 */
fun HikException.descOfPlayer(
  context: Context,
  descPrefix: (() -> Unit)? = null,
  exceptionNotInit: (() -> String)? = null,
  exceptionLogin: (HikExceptionLogin.() -> String)? = null,
  exceptionLoginParams: (() -> String)? = null,
  exceptionLoginAccount: (HikExceptionLoginAccount.() -> String)? = null,
  exceptionLoginLocked: (HikExceptionLoginLocked.() -> String)? = null,
  exceptionPlayFailed: (HikExceptionPlayFailed.() -> String)? = null,
  exception: (HikException.() -> String)? = null,
): String {
  val prefix = descPrefix?.invoke() ?: context.getString(R.string.lib_hikvision_PrefixOfPlayerException)
  val desc = desc(
    context = context,
    exceptionNotInit = exceptionNotInit,
    exceptionLogin = exceptionLogin,
    exceptionLoginParams = exceptionLoginParams,
    exceptionLoginAccount = exceptionLoginAccount,
    exceptionLoginLocked = exceptionLoginLocked,
    exceptionPlayFailed = exceptionPlayFailed,
    exception = exception,
  )
  return "${prefix}${desc}"
}

private fun HikException.desc(
  context: Context,
  exceptionNotInit: (() -> String)? = null,
  exceptionLogin: (HikExceptionLogin.() -> String)? = null,
  exceptionLoginParams: (() -> String)? = null,
  exceptionLoginAccount: (HikExceptionLoginAccount.() -> String)? = null,
  exceptionLoginLocked: (HikExceptionLoginLocked.() -> String)? = null,
  exceptionPlayFailed: (HikExceptionPlayFailed.() -> String)? = null,
  exception: (HikException.() -> String)? = null,
): String {
  return when (this) {
    is HikExceptionNotInit -> exceptionNotInit?.invoke() ?: context.getString(R.string.lib_hikvision_HikExceptionNotInit)
    is HikExceptionLogin -> exceptionLogin?.invoke(this) ?: "${context.getString(R.string.lib_hikvision_HikExceptionLogin)}(${code})"
    is HikExceptionLoginParams -> exceptionLoginParams?.invoke() ?: context.getString(R.string.lib_hikvision_HikExceptionLoginParams)
    is HikExceptionLoginAccount -> exceptionLoginAccount?.invoke(this) ?: "${context.getString(R.string.lib_hikvision_HikExceptionLoginAccount)}(${code})"
    is HikExceptionLoginLocked -> exceptionLoginLocked?.invoke(this) ?: "${context.getString(R.string.lib_hikvision_HikExceptionLoginLocked)}(${code})"
    is HikExceptionPlayFailed -> exceptionPlayFailed?.invoke(this) ?: "(${code})"
    else -> exception?.invoke(this) ?: toString()
  }
}