/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kafka.utils.timer

import java.util.concurrent.{DelayQueue, Executors, ThreadFactory, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantReadWriteLock

import kafka.utils.threadsafe
import org.apache.kafka.common.utils.Utils

trait Timer {
  /**
    * Add a new task to this executor. It will be executed after the task's delay
    * (beginning from the time of submission)
    * @param timerTask the task to add
    */
  def add(timerTask: TimerTask): Unit

  /**
    * Advance the internal clock, executing any tasks whose expiration has been
    * reached within the duration of the passed timeout.
    * @param timeoutMs
    * @return whether or not any tasks were executed
    */
  def advanceClock(timeoutMs: Long): Boolean

  /**
    * Get the number of tasks pending execution
    * @return the number of tasks
    */
  def size: Int

  /**
    * Shutdown the timer service, leaving pending tasks unexecuted
    */
  def shutdown(): Unit
}

@threadsafe
class SystemTimer(executorName: String,
                  tickMs: Long = 1,
                  wheelSize: Int = 20,
                  startMs: Long = System.currentTimeMillis) extends Timer {

  // timeout timer
  private[this] val taskExecutor = Executors.newFixedThreadPool(1, new ThreadFactory() {
    def newThread(runnable: Runnable): Thread =
      Utils.newThread("executor-"+executorName, runnable, false)
  })

  private[this] val delayQueue = new DelayQueue[TimerTaskList]()
  private[this] val taskCounter = new AtomicInteger(0)
  private[this] val timingWheel = new TimingWheel(
    tickMs = tickMs,
    wheelSize = wheelSize,
    startMs = startMs,
    taskCounter = taskCounter,
    delayQueue
  )

  // Locks used to protect data structures while ticking
  private[this] val readWriteLock = new ReentrantReadWriteLock()
  private[this] val readLock = readWriteLock.readLock()
  private[this] val writeLock = readWriteLock.writeLock()

  def add(timerTask: TimerTask): Unit = {
    readLock.lock()
    try {
      addTimerTaskEntry(new TimerTaskEntry(timerTask, timerTask.delayMs + System.currentTimeMillis()))
    } finally {
      readLock.unlock()
    }
  }

  private def addTimerTaskEntry(timerTaskEntry: TimerTaskEntry): Unit = {
    // 最底层的时间轮精度为1ms，往上一层是20ms，再往上是400ms...
    // 上层时间轮存在降级，假设add一个task在445ms后执行(不考虑当前时间)，445ms/400=1，1%20=1，bucket过期时间为1*400=400ms。
    // 当 bucket 过期时，此 task 还未过期，会重新add到下层的时间轮中。只有最底层都加不进去，才说明真正的过期
    if (!timingWheel.add(timerTaskEntry)) { // 任务已过期为 true
      // Already expired or cancelled
      if (!timerTaskEntry.cancelled)
        taskExecutor.submit(timerTaskEntry.timerTask) // 将过期的任务添加到 taskExecutor 线程池中
    }
  }

  private[this] val reinsert = (timerTaskEntry: TimerTaskEntry) => addTimerTaskEntry(timerTaskEntry)

  /*
   * Advances the clock if there is an expired bucket. If there isn't any expired bucket when called,
   * waits up to timeoutMs before giving up.
   */
  // 时钟前进
  def advanceClock(timeoutMs: Long): Boolean = {
    var bucket = delayQueue.poll(timeoutMs, TimeUnit.MILLISECONDS) // 尝试从 delayQueue 中获取元素，超时时间 timeoutMs
    if (bucket != null) {
      writeLock.lock()
      try {
        while (bucket != null) {
          timingWheel.advanceClock(bucket.getExpiration())

          // 清空 bucket：从链表的 head 开始遍历将这些过期的 Task ，放入 executors
          bucket.flush(reinsert)

          // 再次尝试获取过期的 bucket 。如果没有，直接返回 null 。该函数不会阻塞
          bucket = delayQueue.poll()
        }
      } finally {
        writeLock.unlock()
      }
      true
    } else {
      false
    }
  }

  def size: Int = taskCounter.get

  override def shutdown() {
    taskExecutor.shutdown()
  }

}

