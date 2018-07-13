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

import kafka.utils.nonthreadsafe

import java.util.concurrent.DelayQueue
import java.util.concurrent.atomic.AtomicInteger

/*
 * Hierarchical Timing Wheels
 *
 * A simple timing wheel is a circular list of buckets of timer tasks. Let u be the time unit.
 * A timing wheel with size n has n buckets and can hold timer tasks in n * u time interval.
 * Each bucket holds timer tasks that fall into the corresponding time range. At the beginning,
 * the first bucket holds tasks for [0, u), the second bucket holds tasks for [u, 2u), …,
 * the n-th bucket for [u * (n -1), u * n). Every interval of time unit u, the timer ticks and
 * moved to the next bucket then expire all timer tasks in it. So, the timer never insert a task
 * into the bucket for the current time since it is already expired. The timer immediately runs
 * the expired task. The emptied bucket is then available for the next round, so if the current
 * bucket is for the time t, it becomes the bucket for [t + u * n, t + (n + 1) * u) after a tick.
 * A timing wheel has O(1) cost for insert/delete (start-timer/stop-timer) whereas priority queue
 * based timers, such as java.util.concurrent.DelayQueue and java.util.Timer, have O(log n)
 * insert/delete cost.
 *
 * A major drawback of a simple timing wheel is that it assumes that a timer request is within
 * the time interval of n * u from the current time. If a timer request is out of this interval,
 * it is an overflow. A hierarchical timing wheel deals with such overflows. It is a hierarchically
 * organized timing wheels. The lowest level has the finest time resolution. As moving up the
 * hierarchy, time resolutions become coarser. If the resolution of a wheel at one level is u and
 * the size is n, the resolution of the next level should be n * u. At each level overflows are
 * delegated to the wheel in one level higher. When the wheel in the higher level ticks, it reinsert
 * timer tasks to the lower level. An overflow wheel can be created on-demand. When a bucket in an
 * overflow bucket expires, all tasks in it are reinserted into the timer recursively. The tasks
 * are then moved to the finer grain wheels or be executed. The insert (start-timer) cost is O(m)
 * where m is the number of wheels, which is usually very small compared to the number of requests
 * in the system, and the delete (stop-timer) cost is still O(1).
 *
 * Example
 * Let's say that u is 1 and n is 3. If the start time is c,
 * then the buckets at different levels are:
 *
 * level    buckets
 * 1        [c,c]   [c+1,c+1]  [c+2,c+2]
 * 2        [c,c+2] [c+3,c+5]  [c+6,c+8]
 * 3        [c,c+8] [c+9,c+17] [c+18,c+26]
 *
 * The bucket expiration is at the time of bucket beginning.
 * So at time = c+1, buckets [c,c], [c,c+2] and [c,c+8] are expired.
 * Level 1's clock moves to c+1, and [c+3,c+3] is created.
 * Level 2 and level3's clock stay at c since their clocks move in unit of 3 and 9, respectively.
 * So, no new buckets are created in level 2 and 3.
 *
 * Note that bucket [c,c+2] in level 2 won't receive any task since that range is already covered in level 1.
 * The same is true for the bucket [c,c+8] in level 3 since its range is covered in level 2.
 * This is a bit wasteful, but simplifies the implementation.
 *
 * 1        [c+1,c+1]  [c+2,c+2]  [c+3,c+3]
 * 2        [c,c+2]    [c+3,c+5]  [c+6,c+8]
 * 3        [c,c+8]    [c+9,c+17] [c+18,c+26]
 *
 * At time = c+2, [c+1,c+1] is newly expired.
 * Level 1 moves to c+2, and [c+4,c+4] is created,
 *
 * 1        [c+2,c+2]  [c+3,c+3]  [c+4,c+4]
 * 2        [c,c+2]    [c+3,c+5]  [c+6,c+8]
 * 3        [c,c+8]    [c+9,c+17] [c+18,c+18]
 *
 * At time = c+3, [c+2,c+2] is newly expired.
 * Level 2 moves to c+3, and [c+5,c+5] and [c+9,c+11] are created.
 * Level 3 stay at c.
 *
 * 1        [c+3,c+3]  [c+4,c+4]  [c+5,c+5]
 * 2        [c+3,c+5]  [c+6,c+8]  [c+9,c+11]
 * 3        [c,c+8]    [c+9,c+17] [c+8,c+11]
 *
 * The hierarchical timing wheels works especially well when operations are completed before they time out.
 * Even when everything times out, it still has advantageous when there are many items in the timer.
 * Its insert cost (including reinsert) and delete cost are O(m) and O(1), respectively while priority
 * queue based timers takes O(log N) for both insert and delete where N is the number of items in the queue.
 *
 * This class is not thread-safe. There should not be any add calls while advanceClock is executing.
 * It is caller's responsibility to enforce it. Simultaneous add calls are thread-safe.
 */
@nonthreadsafe
private[timer] class TimingWheel(tickMs: Long, wheelSize: Int, startMs: Long, taskCounter: AtomicInteger, queue: DelayQueue[TimerTaskList]) {

  // 格子范围：每1格的间隔 * 总格数（默认第一层1格是1毫秒，20格，那总共时间范围为20ms)
  private[this] val interval = tickMs * wheelSize

  // buckets 数组存放 TimerTaskList ，每个 bucket(TimerTaskList) 为一个时间格，过期的时间格会复用
  // TimerTaskList 是一个双向链表，实现了 Delayed ，为 DelayQueue 中的元素
  // TimerTaskEntry 为链表的每个节点
  private[this] val buckets = Array.tabulate[TimerTaskList](wheelSize) { _ => new TimerTaskList(taskCounter) }

  // 当前时间格的指针，为间隔(tickMs)的整数倍（减去startMs % tickMs）
  // 将整个时间轮划分为到期部分和未到期部分，currentTime当前指向的时间格也属于到期部分，表示刚好到期
  private[this] var currentTime = startMs - (startMs % tickMs) // rounding down to multiple of tickMs

  // overflowWheel can potentially be updated and read by two concurrent threads through add().
  // Therefore, it needs to be volatile due to the issue of Double-Checked Locking pattern with JVM
  // 超过当前时间轮刻度范围的时间轮
  @volatile private[this] var overflowWheel: TimingWheel = null

  private[this] def addOverflowWheel(): Unit = {
    synchronized {
      if (overflowWheel == null) {
        overflowWheel = new TimingWheel(
          tickMs = interval,
          wheelSize = wheelSize,
          startMs = currentTime,
          taskCounter = taskCounter,
          queue
        )
      }
    }
  }

  def add(timerTaskEntry: TimerTaskEntry): Boolean = {
    val expiration = timerTaskEntry.expirationMs

    if (timerTaskEntry.cancelled) {
      // Cancelled
      false
    } else if (expiration < currentTime + tickMs) { // 该Task的过期时间小于当前时间 ＋ 基本的tick单位（1ms)，说明此任务已经过期了，不用再加入timingWheel
      // Already expired
      false
    } else if (expiration < currentTime + interval) { // 过期时间 < 当前时间 ＋ 格子范围，则说明当前的刻度盘包含此过期时间。加入到此刻度盘中
      // Put in its own bucket
      // buckets 数组下标：task 过期时间除范围取整，再与时间轮长度取余。让同个时间范围的 task 落在同一 bucket 中
      val virtualId = expiration / tickMs
      val bucket = buckets((virtualId % wheelSize.toLong).toInt) // 根据过期时间，计算出bucket的位置，添加到该bucket中
      bucket.add(timerTaskEntry)

      // Set the bucket expiration time
      // 设置 bucket 的过期时间，第一次或过期时间有更新返回 true
      // 因为 bucket 为循环使用，所以每次循环 virtualId 会变大
      if (bucket.setExpiration(virtualId * tickMs)) {
        // The bucket needs to be enqueued because it was an expired bucket
        // We only need to enqueue the bucket when its expiration time has changed, i.e. the wheel has advanced
        // and the previous buckets gets reused; further calls to set the expiration within the same wheel cycle
        // will pass in the same value and hence return false, thus the bucket with the same expiration will not
        // be enqueued multiple times.
        queue.offer(bucket) // 该桶只会被加入到 delayedQueue 1次
      }
      true
    } else {
      // Out of the interval. Put it into the parent timer
      // 超过当前范围的，加入到 parent 时间轮中，递归
      if (overflowWheel == null) addOverflowWheel()
      overflowWheel.add(timerTaskEntry)
    }
  }

  // Try to advance the clock
  // 更新时间轮的 currentTime
  // timeMs：已经过期的 bucket 过期时间
  def advanceClock(timeMs: Long): Unit = {
    if (timeMs >= currentTime + tickMs) {
      // 更新currentTime，减去除间隔的余数，取整
      currentTime = timeMs - (timeMs % tickMs)

      // Try to advance the clock of the overflow wheel if present
      // 更新 parent timingWheel 的 currentTime
      if (overflowWheel != null) overflowWheel.advanceClock(currentTime)
    }
  }
}
