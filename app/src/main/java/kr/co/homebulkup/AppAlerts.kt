package kr.co.homebulkup

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import java.time.*

internal object AppAlerts {
    private const val REST = "kr.co.homebulkup.REST_COMPLETE"
    private const val WORKOUT = "kr.co.homebulkup.WORKOUT_REMINDER"
    private fun prefs(context: Context) = context.getSharedPreferences("homebulkup", 0)
    private fun alarms(context: Context) = context.getSystemService(AlarmManager::class.java)
    fun permitted(context: Context): Boolean = (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) && context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
    fun exact(context: Context): Boolean = Build.VERSION.SDK_INT < 31 || alarms(context).canScheduleExactAlarms()
    private fun token(context: Context, code: Int, action: String, epoch: Long = 0, index: Int = -1): PendingIntent = PendingIntent.getBroadcast(context, code,
        Intent(context, AlertReceiver::class.java).setAction(action).putExtra("epoch", epoch).putExtra("index", index), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun schedule(context: Context, epoch: Long, token: PendingIntent) {
        val manager = alarms(context)
        if (exact(context)) try { manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, epoch, token); return } catch (_: SecurityException) { /* Permission may change after checking. */ }
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, epoch, token)
    }
    fun syncRest(context: Context, timer: RestTimer) {
        val token = token(context, 200, REST, timer.deadline)
        alarms(context).cancel(token)
        if (timer.running && prefs(context).getBoolean("notify_enabled", false) && permitted(context)) schedule(context, timer.deadline, token)
    }
    fun restore(context: Context) {
        val p = prefs(context)
        val deadline = p.getLong("rest_deadline", 0)
        if (deadline > System.currentTimeMillis()) syncRest(context, RestTimerStore.load(p, System.currentTimeMillis()))
        else if (deadline > 0) finishRest(context, deadline)
        syncWorkouts(context)
    }
    @Synchronized fun finishRest(context: Context, deadline: Long) {
        val p = prefs(context)
        if (deadline <= 0 || p.getLong("rest_alerted_deadline", 0) == deadline) return
        // An old alarm must not clear a newer timer or alert after a user pauses it.
        if (p.getLong("rest_deadline", 0) != deadline) return
        p.edit().putLong("rest_alerted_deadline", deadline).putLong("rest_deadline", 0).putInt("rest_remaining", 0).apply()
        if (System.currentTimeMillis() - deadline in 0..600000L) post(context, 200, "rest_v1", "휴식 완료", "다음 세트를 시작할 시간이에요.", -1)
    }
    fun syncWorkouts(context: Context, now: ZonedDateTime = ZonedDateTime.now()) {
        val p = prefs(context); val plan = PlanStore.schedule(p)
        repeat(4) { index ->
            val pending = token(context, 300 + index, WORKOUT, index = index)
            alarms(context).cancel(pending)
            p.edit().remove("scheduled_$index").apply()
            if (plan.enabled && p.getBoolean("notify_enabled", false) && permitted(context)) {
                val next = nextWorkoutTime(now, plan.days[index], LocalTime.parse(plan.time)).toInstant().toEpochMilli()
                p.edit().putLong("scheduled_$index", next).apply()
                schedule(context, next, token(context, 300 + index, WORKOUT, next, index))
            }
        }
    }
    @Synchronized fun workout(context: Context, index: Int, epoch: Long) {
        if (index !in 0..3) return
        val p = prefs(context); val plan = PlanStore.schedule(p)
        if (!plan.enabled || p.getLong("scheduled_$index", 0) != epoch || p.getLong("sent_$index", 0) == epoch) return
        p.edit().putLong("sent_$index", epoch).apply()
        val recovery = PlanStore.recovery(p)
        if (!(recovery?.active(LocalDate.now()) == true && recovery.mode == "rest")) {
            val title = loadWorkoutProgram(p)[index].title
            post(context, 300 + index, "workout_v1", "오늘의 운동 · $title", if (recovery?.active(LocalDate.now()) == true) "가벼운 주간 계획을 확인하세요." else "컨디션을 기록하고 운동을 시작해 보세요.", index)
        }
        syncWorkouts(context, Instant.ofEpochMilli(maxOf(System.currentTimeMillis(), epoch + 1000)).atZone(ZoneId.systemDefault()))
    }
    private fun post(context: Context, id: Int, channelId: String, title: String, text: String, day: Int) {
        if (!prefs(context).getBoolean("notify_enabled", false) || !permitted(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(channelId, if (channelId == "rest_v1") "휴식 종료" else "운동 일정", NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true); vibrationPattern = longArrayOf(0, 300, 150, 300)
        }
        manager.createNotificationChannel(channel)
        val intent = Intent(context, MainActivity::class.java).putExtra("open_workout", true).putExtra("workout_day", day).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val tap = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try { manager.notify(id, NotificationCompat.Builder(context, channelId).setSmallIcon(R.drawable.ic_homebulkup_foreground).setContentTitle(title).setContentText(text).setContentIntent(tap).setAutoCancel(true).setCategory(if (id == 200) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER).build()) } catch (_: SecurityException) { /* User may revoke permission at any time. */ }
    }
    internal fun receive(context: Context, intent: Intent) {
        when (intent.action) {
            REST -> {
                val epoch = intent.getLongExtra("epoch", 0)
                if (epoch > System.currentTimeMillis()) syncRest(context, RestTimerStore.load(prefs(context), System.currentTimeMillis())) else finishRest(context, epoch)
            }
            WORKOUT -> workout(context, intent.getIntExtra("index", -1), intent.getLongExtra("epoch", 0))
            else -> restore(context)
        }
    }
}
class AlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
            "kr.co.homebulkup.REST_COMPLETE", "kr.co.homebulkup.WORKOUT_REMINDER" -> AppAlerts.receive(context, intent)
            else -> return
        }
    }
}
