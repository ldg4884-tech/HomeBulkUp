package kr.co.homebulkup

import androidx.compose.runtime.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.delay
import java.time.LocalDate

@Composable internal fun currentDateKey(): String {
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    LaunchedEffect(Unit) { while (true) { date = LocalDate.now().toString(); delay(1000) } }
    return date
}

@Composable internal fun GrowthReport(completed: Set<String>, history: List<Pair<String, Float>>) {
    val context = LocalContext.current
    val date = LocalDate.parse(currentDateKey())
    val prefs = remember { context.getSharedPreferences("homebulkup", 0) }
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == "training_sets_v2") revision++ }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val sets = remember(revision) { TrainingStore.load(prefs, loadWorkoutProgram(prefs)) }
    val week = weeklySummary(sets, completed, date)
    val previous = weeklySummary(sets, completed, date.minusDays(7))
    val weights = weightSummary(history, date)
    Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(20.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("주간 성장 리포트", color = Lime, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text("${date.minusDays(6)} ~ $date · 최근 7일", color = Muted, fontSize = 12.sp)
        Text("운동 ${week.sessions}회 · 본 세트 ${week.sets} · 준비 ${week.warmups}세트", color = White)
        Text("총 운동량 ${formatKg(week.volume)} kg·회", color = White)
        Text("이전 7일 ${formatKg(previous.volume)} kg·회 · 변화 ${formatKg(week.volume - previous.volume)} kg·회", color = Muted, fontSize = 12.sp)
        Text("완료 버튼을 누른 유효한 세트만 집계해요. 맨몸 운동의 0kg 세트와 시간 기준 운동은 운동량에서 제외됩니다. 덤벨은 한 개 기준으로 비교하세요.", color = Muted, fontSize = 11.sp)
        if (week.records.isEmpty()) Text("최고 기록: 비교할 과거 기록이 없거나 이번 주 갱신이 없어요.", color = Muted, fontSize = 12.sp)
        else week.records.forEach { Text("최고 무게 갱신 · $it", color = Lime) }
        HorizontalDivider()
        Text("체중 주간 평균", color = White, fontWeight = FontWeight.Bold)
        Text("최근 7일 ${weights.current?.let { formatKg(it) + "kg" } ?: "기록 없음"} / 이전 7일 ${weights.previous?.let { formatKg(it) + "kg" } ?: "기록 없음"}", color = Lime)
        Text(when {
            !weights.enough -> "각 7일 구간에 서로 다른 날짜의 체중을 3회 이상 기록하면 정체 여부를 확인해요."
            weights.plateau -> "체중 증가 정체 알림 · 주간 평균 증가가 0.1kg 미만이에요. 식단 누락과 측정 조건을 확인하고, 벌크업 중이라면 하루 섭취량을 100–150kcal 늘리는 방법을 고려하세요."
            else -> "주간 평균이 증가하고 있어요. 같은 조건에서 측정하며 회복 상태도 함께 확인하세요."
        }, color = if (weights.plateau) Orange else Muted, fontSize = 12.sp)
    }
}

@Composable internal fun EquipmentSettings() {
    val prefs = LocalContext.current.getSharedPreferences("homebulkup", 0)
    var gear by remember { mutableStateOf(loadEquipment(prefs)) }
    var max by remember { mutableStateOf(formatKg(gear.maxKg)) }
    var step by remember { mutableStateOf(formatKg(gear.increment)) }
    var message by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(20.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("내 운동 장비", color = Lime, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Row { Text("덤벨", Modifier.weight(1f)); Switch(checked = gear.dumbbells, onCheckedChange = { gear = gear.copy(dumbbells = it); prefs.edit().putBoolean("gear_dumbbells", it).apply() }) }
        Row { Text("문틀 철봉", Modifier.weight(1f)); Switch(checked = gear.pullupBar, onCheckedChange = { gear = gear.copy(pullupBar = it); prefs.edit().putBoolean("gear_pullup", it).apply() }) }
        OutlinedTextField(value = max, onValueChange = { max = it }, label = { Text("덤벨 한 개 최대 무게 (kg)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = step, onValueChange = { step = it }, label = { Text("조절 가능한 무게 간격 (kg)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            val m = max.toDoubleOrNull(); val s = step.toDoubleOrNull()
            if (m == null || s == null || !m.isFinite() || !s.isFinite() || m !in 0.5..200.0 || s !in 0.25..20.0 || s > m) message = "최대 무게 0.5–200kg, 간격 0.25–20kg를 입력하세요. 간격은 최대 무게 이하여야 해요."
            else { prefs.edit().putString("gear_max", max).putString("gear_step", step).apply(); message = "저장했어요. 운동 화면에서 대체 운동과 다음 목표에 반영됩니다." }
        }) { Text("장비 설정 저장") }
        if (message.isNotBlank()) Text(message, color = Muted, fontSize = 12.sp)
        Text("기본 루틴은 자동으로 바뀌지 않아요. 운동 화면의 대체 운동에서 직접 선택하세요.", color = Muted, fontSize = 12.sp)
    }
}
