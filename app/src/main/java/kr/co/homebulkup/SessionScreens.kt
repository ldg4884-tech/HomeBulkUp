package kr.co.homebulkup

import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import java.time.LocalDate

@Composable internal fun SetEditor(record: TrainingSet, update: (TrainingSet) -> Unit, complete: () -> Unit) {
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    var menu by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = record.kind == "warmup", onClick = { update(record.copy(kind = "warmup")) }, label = { Text("준비") })
            FilterChip(selected = record.kind == "work", onClick = { update(record.copy(kind = "work")) }, label = { Text("본 세트") })
            Box {
                TextButton(onClick = { menu = true }) { Text("RIR ${record.rir?.let { if (it == 5) "5+" else it.toString() } ?: "미기록"}") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("미기록") }, onClick = { update(record.copy(rir = null)); menu = false })
                    (0..5).forEach { n -> DropdownMenuItem(text = { Text(if (n == 5) "5회 이상 여유" else "$n 회 더 가능") }, onClick = { update(record.copy(rir = n)); menu = false }) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = record.weight, onValueChange = { v -> if (v.length <= 7 && v.all { it.isDigit() || it == '.' }) update(record.copy(weight = v, done = false)) }, singleLine = true, label = { Text("무게 kg") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
            OutlinedTextField(value = record.reps, onValueChange = { v -> if (v.length <= 4 && v.all(Char::isDigit)) update(record.copy(reps = v, done = false)) }, singleLine = true, label = { Text(if (record.unit == "seconds") "시간 초" else "횟수") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
        }
        Button(onClick = { keyboard?.hide(); focusManager.clearFocus(); complete() }, enabled = record.valid, modifier = Modifier.fillMaxWidth()) { Text(if (record.done) "완료 ✓ · 누르면 취소" else "${record.number}세트 완료") }
    }
}

@Composable internal fun FocusWorkout(day: WorkoutDay, date: String, prefs: SharedPreferences, records: List<TrainingSet>, update: (TrainingSet) -> Unit, timer: () -> Unit, exit: () -> Unit, finish: () -> Unit, light: Boolean) {
    val slots = day.exercises.flatMap { e -> (1..e.sets).map { e to it } }
    var cursor by remember(day, date) { mutableIntStateOf(prefs.getInt("focus_cursor", 0).coerceIn(0, slots.size)) }
    fun move(index: Int) { cursor = index.coerceIn(0, slots.size); prefs.edit().putInt("focus_cursor", cursor).apply() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row { Text("운동 집중 모드", fontSize = 24.sp, color = Lime, modifier = Modifier.weight(1f)); TextButton(onClick = exit) { Text("나가기") } }
        Text("${cursor.coerceAtMost(slots.size)} / ${slots.size} · ${day.title}")
        Text("RIR은 세트 종료 후 더 할 수 있었던 횟수입니다. 준비 세트는 증량·운동량·최고 기록에서 제외해요.", color = Muted, fontSize = 12.sp)
        if (cursor < slots.size) {
            val (exercise, number) = slots[cursor]
            val prior = previousSession(records, exercise.name, date).firstOrNull { it.number == number }
            val record = records.firstOrNull { it.date == date && it.exerciseId == exercise.id && it.number == number }
                ?: TrainingSet(date, exercise.id, exercise.name, number, unit = if (exercise.reps.contains("초")) "seconds" else "reps", kind = prior?.kind ?: "work")
            Text(exercise.name, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("$number / ${exercise.sets} 세트 · 목표 ${exercise.reps}", color = Lime, fontSize = 20.sp)
            key(record.key) {
                SetEditor(record, update) {
                    update(record.copy(done = !record.done))
                    if (!record.done) { move(cursor + 1); timer() }
                }
            }
            if (prior != null && record.weight.isBlank() && record.reps.isBlank()) TextButton(onClick = {
                update(record.copy(weight = if (light) deloadWeight(prior.weight.toDouble(), loadEquipment(prefs)) else prior.weight, reps = prior.reps))
            }) { Text(if (light) "지난 무게의 80%로 채우기" else "지난 기록 ${prior.weight}kg × ${prior.reps} 채우기") }
        } else {
            Text("계획한 세트를 모두 살펴봤어요.", fontSize = 24.sp)
            val done = records.count { it.date == date && it.done && it.valid && slots.any { slot -> slot.first.id == it.exerciseId && slot.second == it.number } }
            Text("실제 완료 $done / ${slots.size}세트 · 건너뛴 세트는 이전 버튼으로 확인하세요.")
            Button(onClick = finish, enabled = done > 0) { Text("운동 완료하고 나가기") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { move(cursor - 1) }, enabled = cursor > 0) { Text("이전 세트") }
            OutlinedButton(onClick = { move(cursor + 1) }, enabled = cursor < slots.size) { Text("다음 세트") }
        }
        Text("맨몸 운동은 무게 0kg로 입력하세요. 입력과 현재 위치는 자동 저장됩니다.", color = Muted, fontSize = 12.sp)
    }
}

@Composable internal fun WellnessEditor(value: Wellness, save: (Wellness) -> Unit) {
    var draft by remember(value.date) { mutableStateOf(value) }
    var message by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(16.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("오늘의 컨디션 · ${value.date}", color = Lime, fontWeight = FontWeight.Bold)
        OutlinedTextField(value = draft.sleep, onValueChange = { if (it.length <= 4) draft = draft.copy(sleep = it) }, label = { Text("수면 시간 (선택)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        Text("컨디션 · 1 매우 피곤 ~ 5 좋음", fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { (1..5).forEach { n -> FilterChip(selected = draft.energy == n, onClick = { draft = draft.copy(energy = n) }, label = { Text("$n") }) } }
        OutlinedTextField(value = draft.pain, onValueChange = { draft = draft.copy(pain = it.take(200)) }, label = { Text("불편한 부위 / 통증 (선택)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = draft.note, onValueChange = { draft = draft.copy(note = it.take(500)) }, label = { Text("운동·회복 메모 (선택)") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            val sleep = draft.sleep.toDoubleOrNull()
            if (draft.sleep.isNotBlank() && (sleep == null || !sleep.isFinite() || sleep !in 0.0..24.0)) message = "수면 시간을 0–24 사이로 입력하세요."
            else { save(draft); message = "컨디션을 저장했어요." }
        }) { Text("컨디션 저장") }
        Text(message, color = Muted, fontSize = 12.sp)
    }
}

@Composable internal fun RecoverySettings() {
    val prefs = androidx.compose.ui.platform.LocalContext.current.getSharedPreferences("homebulkup", 0)
    var plan by remember { mutableStateOf(PlanStore.recovery(prefs)) }
    var start by remember { mutableStateOf(plan?.start ?: LocalDate.now().toString()) }
    var message by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(18.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("휴식 · 가벼운 운동 주간", color = Lime, fontSize = 20.sp)
        Text(plan?.let { "${it.start}부터 7일 · ${if (it.mode == "rest") "휴식" else "가볍게"}" } ?: "설정된 회복 주간 없음")
        OutlinedTextField(value = start, onValueChange = { start = it }, label = { Text("시작 날짜 YYYY-MM-DD") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        fun set(mode: String) {
            if (runCatching { LocalDate.parse(start) }.isFailure) message = "날짜 형식을 확인하세요."
            else { plan = RecoveryPlan(start, mode); PlanStore.saveRecovery(prefs, plan); message = "원래 루틴을 보존한 채 7일간 적용합니다." }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = { set("rest") }) { Text("휴식") }; Button(onClick = { set("light") }) { Text("가볍게") } }
        OutlinedButton(onClick = { plan = null; PlanStore.saveRecovery(prefs, null); message = "회복 주간을 해제했어요." }) { Text("회복 주간 해제") }
        Text("가벼운 주간은 세트 수 절반(올림), 불러올 무게 80%를 제안합니다. 기간 종료 후 원래 루틴으로 돌아옵니다.", color = Muted, fontSize = 12.sp)
        Text(message, color = Muted, fontSize = 12.sp)
    }
}
