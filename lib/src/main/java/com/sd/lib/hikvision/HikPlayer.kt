package com.sd.lib.hikvision

import android.os.Handler
import android.os.Looper
import android.view.Surface
import java.lang.ref.WeakReference

interface HikPlayer {
  /** 初始化 */
  fun init(
    /** IP */
    ip: String,
    /** 用户名 */
    username: String,
    /** 密码 */
    password: String,
  )

  /** 设置预览 */
  fun setSurface(surface: Surface?)

  /** 设置流类型，0-主码流；1-子码流，默认0 */
  fun setStreamType(type: Int)

  /** 开始播放 */
  fun startPlay()

  /** 停止播放 */
  fun stopPlay()

  /** 释放 */
  fun release()

  /** 回调接口，所有方法都在主线程回调 */
  open class Callback {
    /** 错误 */
    open fun onError(e: HikException) = Unit

    /** 开始播放 */
    open fun onStartPlay() = Unit
    /** 停止播放 */
    open fun onStopPlay() = Unit

    /** 重连 */
    open fun onReconnect() = Unit
    /** 重连成功 */
    open fun onReconnectSuccess() = Unit
  }

  companion object {
    /**
     * 创建[HikPlayer]，内部使用弱引用保存[callback]，因此外部需要强引用保存[callback]
     */
    @JvmStatic
    fun create(callback: Callback): HikPlayer {
      return HikPlayerImpl(MainPlayerCallback(callback))
    }
  }
}

private class MainPlayerCallback(
  callback: HikPlayer.Callback,
) : HikPlayer.Callback() {
  private val _mainHandler = Handler(Looper.getMainLooper())
  private val _callback = WeakReference(callback)
  private val callback get() = _callback.get()

  override fun onError(e: HikException) {
    _mainHandler.post { callback?.onError(e) }
  }

  override fun onStartPlay() {
    _mainHandler.post { callback?.onStartPlay() }
  }

  override fun onStopPlay() {
    _mainHandler.post { callback?.onStopPlay() }
  }

  override fun onReconnect() {
    _mainHandler.post { callback?.onReconnect() }
  }

  override fun onReconnectSuccess() {
    _mainHandler.post { callback?.onReconnectSuccess() }
  }
}