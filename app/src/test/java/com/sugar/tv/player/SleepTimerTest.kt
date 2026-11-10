package com.sugar.tv.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** 睡眠定时的边界回归；不依赖设备时钟、延迟等待或 Android 生命周期。 */
class SleepTimerTest {

    /** 进程恢复保留剩余时长，过期任务不能获得新的完整倒计时。 */
    @Test
    fun restore_keepsRemainingDurationAndExpiredBoundary() {
        assertEquals(30_000L, SleepTimer.restoreRemainingMillis(deadlineEpochMs = 100_000L, nowEpochMs = 70_000L))
        assertEquals(0L, SleepTimer.restoreRemainingMillis(deadlineEpochMs = 100_000L, nowEpochMs = 100_000L))
        assertEquals(0L, SleepTimer.restoreRemainingMillis(deadlineEpochMs = 100_000L, nowEpochMs = 110_000L))
    }

    /** 设备时钟回拨或异常恢复值不能把计时延长到最大选项以外。 */
    @Test
    fun restore_clampsToLongestSupportedDuration() {
        assertEquals(
            120L * SleepTimer.MILLIS_PER_MINUTE,
            SleepTimer.restoreRemainingMillis(deadlineEpochMs = 20_000_000L, nowEpochMs = 1_000L),
        )
    }

    /** 显示按分钟向上取整，在最后一毫秒仍显示一分钟，到期和过期才显示零。 */
    @Test
    fun remainingMinutes_roundsUpUntilExactDeadline() {
        assertEquals(2, SleepTimer.remainingMinutes(deadlineElapsedMs = 60_002L, nowElapsedMs = 1L))
        assertEquals(1, SleepTimer.remainingMinutes(deadlineElapsedMs = 60_001L, nowElapsedMs = 1L))
        assertEquals(1, SleepTimer.remainingMinutes(deadlineElapsedMs = 2L, nowElapsedMs = 1L))
        assertEquals(0, SleepTimer.remainingMinutes(deadlineElapsedMs = 1L, nowElapsedMs = 1L))
        assertEquals(0, SleepTimer.remainingMinutes(deadlineElapsedMs = 1L, nowElapsedMs = 2L))
    }
}
