package kr.co.homebulkup

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalTime

@Composable internal fun ScheduleSettings() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("homebulkup", 0) }
    var plan by remember { mutableStateOf(PlanStore.schedule(prefs)) }
    var time by remember { mutableStateOf(plan.time) }
    var enabled by remember { mutableStateOf(prefs.getBoolean("notify_enabled", false)) }
    var refresh by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("") }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        enabled = granted; prefs.edit().putBoolean("notify_enabled", granted).apply(); AppAlerts.restore(context); refresh++
        if (!granted) message = "알림 권한이 없어 휴식·일정 알림을 보낼 수 없어요. 타이머와 기록은 사용할 수 있어요."
    }
    DisposableEffect(context) {
        val lifecycle = (context as? ComponentActivity)?.lifecycle
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) { refresh++; AppAlerts.restore(context) } }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(18.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("휴식 알림 · 운동 일정", color = Lime, fontSize = 20.sp)
        Text(if (enabled && remember(refresh) { AppAlerts.permitted(context) }) "알림 사용 중 · 소리와 진동은 Android 알림 설정을 따릅니다." else "알림이 꺼져 있거나 시스템 권한이 필요해요.")
        Button(onClick = {
            if (enabled) { enabled = false; prefs.edit().putBoolean("notify_enabled", false).apply(); AppAlerts.restore(context) }
            else if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else { enabled = true; prefs.edit().putBoolean("notify_enabled", true).apply(); AppAlerts.restore(context) }
        }) { Text(if (enabled) "알림 끄기" else "휴식·일정 알림 켜기") }
        if (Build.VERSION.SDK_INT >= 31 && !remember(refresh) { AppAlerts.exact(context) }) {
            Text("정확한 알람 권한이 없으면 절전 상태에서 휴식 알림이 늦어질 수 있어요.", color = Orange)
            OutlinedButton(onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) }.onFailure { message = "기기의 앱 설정에서 알람 권한을 확인하세요." } }) { Text("정확한 알람 권한 설정") }
        }
        OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }) { Text("시스템 알림·진동 설정") }
        val days = listOf("월", "화", "수", "목", "금", "토", "일")
        loadWorkoutProgram(prefs).forEachIndexed { index, day ->
            Text("${index + 1}. ${day.title}")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { days.forEachIndexed { d, label ->
                TextButton(contentPadding = PaddingValues(2.dp), modifier = Modifier.weight(1f), onClick = { plan = plan.copy(days = plan.days.toMutableList().also { it[index] = d + 1 }) }) { Text(if (plan.days[index] == d + 1) "[$label]" else label) }
            } }
        }
        OutlinedTextField(value = time, onValueChange = { time = it.take(5) }, label = { Text("공통 알림 시각 HH:mm · 기기 현지 시간") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row { Checkbox(checked = plan.enabled, onCheckedChange = { plan = plan.copy(enabled = it) }); Text("주간 일정 알림 사용") }
        Button(onClick = {
            val parsed = runCatching { LocalTime.parse(time) }.getOrNull()
            if (parsed == null || !Regex("\\d{2}:\\d{2}").matches(time)) message = "예: 18:30 형식으로 입력하세요."
            else { plan = plan.copy(time = parsed.toString()); PlanStore.saveSchedule(prefs, plan); AppAlerts.syncWorkouts(context); message = "일정을 저장했어요. 같은 요일에 여러 루틴도 배치할 수 있어요." }
        }) { Text("일정 저장") }
        Text(message, color = Muted)
        Text("재부팅·시간대 변경 후 일정을 다시 예약합니다. 앱 강제 종료·제조사 절전·알림 차단 상태에서는 알림이 제한될 수 있어요.", color = Muted, fontSize = 12.sp)
    }
}

@Composable internal fun TodaySchedule(open: (Int) -> Unit) {
    val prefs = LocalContext.current.getSharedPreferences("homebulkup", 0)
    val date = java.time.LocalDate.parse(currentDateKey())
    val days = loadWorkoutProgram(prefs)
    val recovery = PlanStore.recovery(prefs)
    val indices = PlanStore.schedule(prefs).days.mapIndexedNotNull { index, weekday -> index.takeIf { weekday == date.dayOfWeek.value } }
    Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(18.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("오늘의 운동 일정", color = Lime, fontSize = 20.sp)
        when {
            recovery?.active(date) == true && recovery.mode == "rest" -> { Text("휴식 주간 · 컨디션을 확인하며 회복하세요."); OutlinedButton(onClick = { open(0) }) { Text("컨디션 기록하기") } }
            indices.isEmpty() -> { Text("오늘은 예정된 운동이 없어요. 회복하거나 자유롭게 루틴을 선택하세요."); OutlinedButton(onClick = { open(0) }) { Text("루틴 살펴보기") } }
            else -> indices.forEach { index -> Button(onClick = { open(index) }, modifier = Modifier.fillMaxWidth()) { Text(days[index].title + if (recovery?.active(date) == true) " · 가볍게" else " · 시작") } }
        }
    }
}
