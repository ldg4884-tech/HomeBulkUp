package kr.co.homebulkup

import androidx.compose.runtime.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable internal fun HistoryManager(completed: Set<String>, changeCompleted: (Set<String>) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("homebulkup", 0) }
    var open by remember { mutableStateOf(false) }
    var records by remember { mutableStateOf(TrainingStore.load(prefs, loadWorkoutProgram(prefs))) }
    var edit by remember { mutableStateOf<TrainingSet?>(null) }
    var search by remember { mutableStateOf("") }
    var undoJob by remember { mutableStateOf<Job?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun persist(values: List<TrainingSet>) { records = values; TrainingStore.save(prefs, values) }
    fun undoable(message: String, undo: () -> Unit) {
        undoJob?.cancel(); snackbar.currentSnackbarData?.dismiss()
        undoJob = scope.launch { if (snackbar.showSnackbar(message, actionLabel = "실행 취소", withDismissAction = true, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) undo() }
    }
    OutlinedButton(onClick = { records = TrainingStore.load(prefs, loadWorkoutProgram(prefs)); open = true }, modifier = Modifier.fillMaxWidth()) { Text("과거 기록 수정 · 삭제 · 실행 취소") }
    if (open) Dialog(onDismissRequest = { open = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row { Text("운동 기록 관리", fontSize = 24.sp, modifier = Modifier.weight(1f)); TextButton(onClick = { open = false }) { Text("닫기") } }
                OutlinedTextField(value = search, onValueChange = { search = it }, label = { Text("날짜 YYYY-MM-DD 또는 종목 검색") }, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                    item { Text("세트 기록 · 준비/본 세트와 RIR도 수정할 수 있어요.", color = Muted) }
                    val visible = records.sortedWith(compareByDescending<TrainingSet> { it.date }.thenBy { it.name }.thenBy { it.number }).filter { search.isBlank() || it.date.contains(search) || it.name.contains(search) }
                    if (visible.isEmpty()) item { Text("조건에 맞는 세트 기록이 없어요.") }
                    items(visible, key = { it.key }) { record ->
                        Column(Modifier.fillMaxWidth().background(Panel).padding(12.dp)) {
                            Text("${record.date} · ${record.name} · ${record.number}세트")
                            Text("${record.weight}kg × ${record.reps}${if (record.unit == "seconds") "초" else "회"} · ${if (record.kind == "warmup") "준비" else "본 세트"} · RIR ${record.rir ?: "미기록"} · ${if (record.done) "완료" else "입력 중"}", color = Muted)
                            Row { TextButton(onClick = { edit = record }) { Text("수정") }; TextButton(onClick = {
                                persist(records.filterNot { it.key == record.key })
                                undoable("세트 기록을 삭제했어요.") { persist(undoSetChange(records, record.key, record)) }
                            }) { Text("삭제") } }
                        }
                    }
                    item { Text("운동 완료 표시", color = Lime, fontSize = 18.sp) }
                    items(completed.sortedDescending().filter { search.isBlank() || it.contains(search) }, key = { "completion-$it" }) { key ->
                        Row(Modifier.fillMaxWidth()) {
                            Text("${key.substringBeforeLast('-')} · 루틴 ${key.substringAfterLast('-').toIntOrNull()?.plus(1)}", modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                changeCompleted(loadCompleted(prefs) - key)
                                undoable("완료 표시를 취소했어요.") { changeCompleted(loadCompleted(prefs) + key) }
                            }) { Text("완료 취소") }
                        }
                    }
                    item { Text("컨디션 메모", color = Lime, fontSize = 18.sp) }
                    items(PlanStore.wellness(prefs).sortedByDescending { it.date }.filter { search.isBlank() || it.date.contains(search) || it.note.contains(search) || it.pain.contains(search) }, key = { "wellness-${it.date}" }) { value ->
                        Text("${value.date} · 수면 ${value.sleep.ifBlank { "미기록" }}시간 · 컨디션 ${value.energy}/5\n불편한 부위: ${value.pain.ifBlank { "없음" }}\n${value.note}", modifier = Modifier.padding(8.dp))
                    }
                }
                SnackbarHost(snackbar)
            }
        }
    }
    edit?.let { original ->
        var draft by remember(original.key) { mutableStateOf(original) }
        AlertDialog(onDismissRequest = { edit = null }, title = { Text("${original.date} · ${original.name}") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SetEditor(draft, { draft = it }) { draft = draft.copy(done = !draft.done) }
                Text("입력을 바꾸면 완료 상태가 해제돼요. 실제 완료한 기록이라면 완료를 다시 표시하세요.", color = Muted)
            }
        }, confirmButton = { TextButton(enabled = !draft.done || draft.valid, onClick = {
            persist(records.filterNot { it.key == original.key } + draft); edit = null
            undoable("기록을 수정했어요.") { persist(undoSetChange(records, original.key, original)) }
        }) { Text("변경 저장") } }, dismissButton = { TextButton(onClick = { edit = null }) { Text("취소") } })
    }
}
