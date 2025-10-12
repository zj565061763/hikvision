package com.sd.lib.hikvision

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class HikRetryHandler(
  private val coroutineScope: CoroutineScope,
) {
  private var _retryJob: Job? = null

  @Synchronized
  fun startRetryJob(
    error: HikVisionException,
    block: () -> Unit,
  ) {
    cancelRetryJob()
    coroutineScope.launch {
      // 如果没有初始化，则尝试初始化
      if (error is HikVisionExceptionNotInit) HikVision.init()
      delay(5_000)
      block()
    }.also { job ->
      _retryJob = job
      job.invokeOnCompletion { releaseRetryJob(job) }
    }
  }

  @Synchronized
  fun cancelRetryJob() {
    _retryJob?.also { job ->
      _retryJob = null
      job.cancel()
    }
  }

  @Synchronized
  private fun releaseRetryJob(job: Job) {
    if (_retryJob === job) {
      _retryJob = null
    }
  }
}