package kr.co.homebulkup

import android.Manifest
import android.app.*
import android.content.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AlertsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("homebulkup", 0)
    private val alarm get() = Shadows.shadowOf(context.getSystemService(AlarmManager::class.java))
    private val notifications get() = Shadows.shadowOf(context.getSystemService(NotificationManager::class.java))
    @Before fun reset() {
        prefs.edit().clear().putBoolean("notify_enabled", true).commit()
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifications.setNotificationsEnabled(true); ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }
    @Test fun restAlarmSurvivesSchedulingAndPauseCancelsIt() {
        val state = RestTimer().fresh(System.currentTimeMillis())
        RestTimerStore.save(prefs, state); AppAlerts.syncRest(context, state)
        assertEquals(1, alarm.scheduledAlarms.size)
        AppAlerts.syncRest(context, state.pause(System.currentTimeMillis()))
        assertEquals(0, alarm.scheduledAlarms.size)
    }
    @Test fun exactPermissionDeniedUsesAvailableAlarmPath() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        AppAlerts.syncRest(context, RestTimer().fresh(System.currentTimeMillis()))
        assertEquals(1, alarm.scheduledAlarms.size)
    }
    @Test fun expiryNotifiesAndDeduplicatesForegroundAndReceiver() {
        val deadline = System.currentTimeMillis() - 1000
        prefs.edit().putLong("rest_deadline", deadline).commit()
        AppAlerts.finishRest(context, deadline); AppAlerts.finishRest(context, deadline)
        assertEquals(1, notifications.size()); assertEquals(0, prefs.getInt("rest_remaining", -1))
        assertTrue(context.getSystemService(NotificationManager::class.java).getNotificationChannel("rest_v1").shouldVibrate())
    }
    @Test fun staleAlarmCannotClearNewTimer() {
        val next = System.currentTimeMillis() + 90000
        prefs.edit().putLong("rest_deadline", next).commit()
        AppAlerts.finishRest(context, next - 180000)
        assertEquals(next, prefs.getLong("rest_deadline", 0)); assertEquals(0, notifications.size())
    }
    @Test fun disabledNotificationDoesNotPreventTimerCompletion() {
        val end = System.currentTimeMillis() - 1
        prefs.edit().putBoolean("notify_enabled", false).putLong("rest_deadline", end).commit()
        AppAlerts.finishRest(context, end)
        assertEquals(0, prefs.getLong("rest_deadline", -1)); assertEquals(0, notifications.size())
    }
    @Test fun bootRestoreReschedulesTimerAndWeeklyPlan() {
        RestTimerStore.save(prefs, RestTimer().fresh(System.currentTimeMillis()))
        PlanStore.saveSchedule(prefs, SchedulePlan(enabled = true))
        AlertReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(5, alarm.scheduledAlarms.size)
    }
    @Test fun disablingPlanCancelsAllWorkoutAlarms() {
        PlanStore.saveSchedule(prefs, SchedulePlan(enabled = true)); AppAlerts.syncWorkouts(context)
        assertEquals(4, alarm.scheduledAlarms.size)
        PlanStore.saveSchedule(prefs, SchedulePlan(enabled = false)); AppAlerts.syncWorkouts(context)
        assertEquals(0, alarm.scheduledAlarms.size)
    }
    @Test fun workoutReminderPostsAndReschedulesNextWeek() {
        PlanStore.saveSchedule(prefs, SchedulePlan(enabled = true))
        AppAlerts.syncWorkouts(context)
        val epoch = prefs.getLong("scheduled_0", 0)
        AppAlerts.workout(context, 0, epoch)
        assertEquals(1, notifications.size()); assertEquals(epoch, prefs.getLong("sent_0", 0)); assertTrue(prefs.getLong("scheduled_0", 0) > epoch)
        AppAlerts.workout(context, 0, epoch)
        assertEquals(1, notifications.size())
    }
}
