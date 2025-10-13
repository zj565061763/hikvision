package com.sd.lib.hikvision

import java.net.URLDecoder

internal data class HikLoginConfig(
  val ip: String,
  val username: String,
  val password: String,
) {
  companion object {
    fun parseUrl(url: String?): HikLoginConfig? {
      return runCatching { urlToLoginConfig(url) }.getOrNull()
    }
  }
}

/**
 * rtsp://admin:admin@123456@192.168.100.110:554/Streaming/Channels/101
 * ip:192.168.100.110
 * username:admin
 * password:admin@123456
 */
private fun urlToLoginConfig(url: String?): HikLoginConfig? {
  if (url.isNullOrEmpty()) return null

  // 去掉协议前缀
  val withoutScheme = url.substringAfter(delimiter = "://", missingDelimiterValue = "")
  if (withoutScheme.isEmpty()) return null

  // 用最后一个 '@' 来分隔 credentials 与 host（因为 password 里可能包含 '@'）
  val indexOfLastAt = withoutScheme.lastIndexOf('@')
  if (indexOfLastAt < 0) return null

  // username:password（password 可能含 ':', '@' 等）
  val credPart = withoutScheme.substring(0, indexOfLastAt)

  // username 与 password：按第一个 ':' 分割（username 不应包含 ':'）
  val indexOfColonForCred = credPart.indexOf(':')
  if (indexOfColonForCred < 0) return null

  val username = credPart.substring(0, indexOfColonForCred)
  if (username.isEmpty()) return null

  val password = credPart.substring(indexOfColonForCred + 1)
  if (password.isEmpty()) return null

  // host:port/...
  val hostAndPath = withoutScheme.substring(indexOfLastAt + 1)
  if (hostAndPath.isEmpty()) return null

  // host 部分（去掉后面的路径）
  val hostPort = hostAndPath.substringBefore('/')
  val ip = hostPort.substringBefore(':')
  if (ip.isEmpty()) return null

  // 如果输入是编码的，对 username/password 做解码
  val safeUsername = URLDecoder.decode(username, "UTF-8")
  val safePassword = URLDecoder.decode(password, "UTF-8")

  return HikLoginConfig(
    ip = ip,
    username = safeUsername,
    password = safePassword,
  )
}
