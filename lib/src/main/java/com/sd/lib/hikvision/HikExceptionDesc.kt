package com.sd.lib.hikvision

import android.content.Context

/** 播放器异常描述 */
fun HikException.descOfPlayer(context: Context): String {
  val prefix = context.getString(R.string.lib_hikvision_PrefixOfPlayerException)
  return "${prefix}${desc(context)}"
}

private fun HikException.desc(context: Context): String {
  return when (this) {
    is HikExceptionNotInit -> context.getString(R.string.lib_hikvision_HikExceptionNotInit)
    is HikExceptionLogin -> "${context.getString(R.string.lib_hikvision_HikExceptionLogin)}(${code})"
    is HikExceptionLoginParams -> context.getString(R.string.lib_hikvision_HikExceptionLoginParams)
    is HikExceptionLoginAccount -> "${context.getString(R.string.lib_hikvision_HikExceptionLoginAccount)}(${code})"
    is HikExceptionLoginLocked -> "${context.getString(R.string.lib_hikvision_HikExceptionLoginLocked)}(${code})"
    is HikExceptionPlayFailed -> "(${code})"
    else -> toString()
  }
}