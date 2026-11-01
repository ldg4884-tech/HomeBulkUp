package kr.co.homebulkup

import android.os.Bundle
import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions

internal val Ink = Color(0xFF111713)
internal val Panel = Color(0xFF1C241F)
internal val Panel2 = Color(0xFF252F29)
internal val Lime = Color(0xFFC6F36A)
internal val Muted = Color(0xFF9BA99F)
internal val White = Color(0xFFF3F6F1)
internal val Orange = Color(0xFFFFB86B)

class MainActivity : ComponentActivity() {
    private fun route(intent: android.content.Intent) {
        if (intent.getBooleanExtra("open_workout", false)) {
            val editor = getSharedPreferences("homebulkup", 0).edit().putInt("active_tab", 1)
            val day = intent.getIntExtra("workout_day", -1)
            if (day in 0..3) editor.putInt("active_day", day)
            editor.apply()
            intent.removeExtra("open_workout"); intent.removeExtra("workout_day")
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); route(intent); setContent { HomeBulkUpApp() } }
    override fun onNewIntent(intent: android.content.Intent) { super.onNewIntent(intent); setIntent(intent); route(intent); recreate() }
}

data class Exercise(val name: String, val detail: String, val sets: Int, val reps: String, val id: String = java.util.UUID.randomUUID().toString())
data class WorkoutDay(val day: String, val title: String, val subtitle: String, val exercises: List<Exercise>)

internal val program = listOf(
    WorkoutDay("월요일", "가슴 · 어깨 · 삼두", "PUSH DAY · 상체 두께", listOf(Exercise("덤벨 플로어 프레스", "가슴", 3, "8–12회"), Exercise("푸시업", "가슴 · 삼두", 2, "8–15회"), Exercise("덤벨 숄더 프레스", "어깨", 2, "8–12회"), Exercise("덤벨 레터럴 레이즈", "측면 어깨", 3, "12–20회"), Exercise("오버헤드 트라이셉스 익스텐션", "삼두", 2, "10–15회"))),
    WorkoutDay("화요일", "등 · 이두", "PULL DAY · 등 넓이", listOf(Exercise("원암 덤벨 로우", "등", 3, "10–15회/측"), Exercise("벤트오버 덤벨 로우", "등", 2, "10–15회"), Exercise("문틀 철봉 턱걸이", "등 · 이두", 2, "가능한 만큼"), Exercise("덤벨 컬", "이두", 3, "10–15회"), Exercise("해머 컬", "전완 · 이두", 2, "10–15회"))),
    WorkoutDay("목요일", "하체 · 코어", "LEG DAY · 균형 잡힌 성장", listOf(Exercise("고블릿 스쿼트", "허벅지", 3, "10–15회"), Exercise("덤벨 루마니안 데드리프트", "햄스트링 · 둔근", 3, "10–15회"), Exercise("리버스 런지", "하체", 2, "8–12회/측"), Exercise("글루트 브리지", "둔근", 2, "12–20회"), Exercise("데드버그", "코어", 2, "8–10회/측"))),
    WorkoutDay("금요일", "가슴 · 등 · 어깨", "UPPER DAY · 상체 볼륨", listOf(Exercise("푸시업", "가슴", 3, "8–15회"), Exercise("원암 덤벨 로우", "등", 3, "10–15회/측"), Exercise("덤벨 숄더 프레스", "어깨", 2, "8–12회"), Exercise("덤벨 레터럴 레이즈", "측면 어깨", 2, "12–20회"), Exercise("덤벨 컬", "이두", 2, "10–15회")))
).mapIndexed { dayIndex, day -> day.copy(exercises = day.exercises.mapIndexed { index, ex -> ex.copy(id = "legacy-$dayIndex-$index") }) }


internal fun loadWorkoutProgram(prefs: android.content.SharedPreferences): List<WorkoutDay> {
    val raw = prefs.getString("custom_workout_program_v1", null) ?: return program
    return try {
        val arr = JSONArray(raw)
        (0 until arr.length()).map { i ->
            val d = arr.getJSONObject(i)
            val exercises = d.getJSONArray("exercises")
            WorkoutDay(d.optString("day"), d.optString("title"), d.optString("subtitle"),
                (0 until exercises.length()).map { j ->
                    val e = exercises.getJSONObject(j)
                    Exercise(e.optString("name"), e.optString("detail"), e.optInt("sets", 3).coerceIn(1, 10), e.optString("reps", "8–12회"), e.optString("id").ifBlank { "legacy-$i-$j" })
                })
        }.takeIf { it.size == program.size && it.all { d -> d.exercises.isNotEmpty() } } ?: program
    } catch (_: Exception) { program }
}

internal fun saveWorkoutProgram(prefs: android.content.SharedPreferences, days: List<WorkoutDay>) {
    val arr = JSONArray()
    days.forEach { day ->
        val exercises = JSONArray()
        day.exercises.forEach { e -> exercises.put(JSONObject().put("name", e.name).put("detail", e.detail).put("sets", e.sets).put("reps", e.reps).put("id", e.id)) }
        arr.put(JSONObject().put("day", day.day).put("title", day.title).put("subtitle", day.subtitle).put("exercises", exercises))
    }
    prefs.edit().putString("custom_workout_program_v1", arr.toString()).apply()
}

internal val exerciseCatalog = listOf(
    Exercise("덤벨 플로어 프레스", "가슴", 3, "8–12회"), Exercise("푸시업", "가슴 · 삼두", 3, "8–15회"),
    Exercise("덤벨 플라이", "가슴", 3, "10–15회"), Exercise("덤벨 풀오버", "가슴 · 등", 3, "10–15회"),
    Exercise("인클라인 푸시업", "가슴", 3, "10–15회"), Exercise("디클라인 푸시업", "가슴 · 삼두", 3, "8–12회"),
    Exercise("원암 덤벨 로우", "등", 3, "10–15회/측"), Exercise("벤트오버 덤벨 로우", "등", 3, "10–15회"),
    Exercise("문틀 철봉 턱걸이", "등 · 이두", 3, "가능한 만큼"), Exercise("덤벨 풀오버", "등 · 가슴", 3, "10–15회"),
    Exercise("덤벨 리버스 플라이", "후면 어깨 · 등", 3, "12–20회"), Exercise("슈퍼맨", "허리 · 등", 3, "12–15회"),
    Exercise("덤벨 숄더 프레스", "어깨", 3, "8–12회"), Exercise("덤벨 레터럴 레이즈", "측면 어깨", 3, "12–20회"),
    Exercise("덤벨 프런트 레이즈", "전면 어깨", 3, "10–15회"), Exercise("파이크 푸시업", "어깨", 3, "6–12회"),
    Exercise("덤벨 컬", "이두", 3, "10–15회"), Exercise("해머 컬", "이두 · 전완", 3, "10–15회"),
    Exercise("컨센트레이션 컬", "이두", 3, "10–12회"), Exercise("리버스 컬", "전완 · 이두", 3, "10–15회"),
    Exercise("오버헤드 트라이셉스 익스텐션", "삼두", 3, "10–15회"), Exercise("덤벨 킥백", "삼두", 3, "10–15회"),
    Exercise("클로즈그립 푸시업", "삼두 · 가슴", 3, "8–12회"),
    Exercise("고블릿 스쿼트", "하체 · 대퇴사두", 3, "10–15회"), Exercise("덤벨 루마니안 데드리프트", "햄스트링 · 둔근", 3, "10–15회"),
    Exercise("리버스 런지", "하체 · 둔근", 3, "8–12회/측"), Exercise("불가리안 스플릿 스쿼트", "하체", 3, "8–12회/측"),
    Exercise("글루트 브리지", "둔근", 3, "12–20회"), Exercise("카프 레이즈", "종아리", 3, "15–25회"),
    Exercise("덤벨 스모 스쿼트", "하체 · 둔근", 3, "10–15회"), Exercise("월싯", "허벅지", 3, "30–60초"),
    Exercise("플랭크", "코어", 3, "30–60초"), Exercise("사이드 플랭크", "옆구리 · 코어", 3, "20–45초/측"),
    Exercise("데드버그", "코어", 3, "8–10회/측"), Exercise("바이시클 크런치", "복근", 3, "15–20회"),
    Exercise("레그 레이즈", "하복부", 3, "10–15회"), Exercise("버드독", "코어 · 허리", 3, "10회/측"),
    Exercise("마운틴 클라이머", "코어 · 유산소", 3, "30–45초"),
    Exercise("맨몸 스쿼트", "하체", 3, "12–20회"), Exercise("맨몸 리버스 런지", "하체", 3, "10–15회/측"), Exercise("수건 이두 컬 (자가 저항)", "이두", 3, "10–15회")
)

@Composable fun HomeBulkUpApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("homebulkup", 0) }
    var tab by remember { mutableIntStateOf(prefs.getInt("active_tab", 0).coerceIn(0, 4)) }
    var weight by remember { mutableStateOf(prefs.getString("weight", "74.0") ?: "74.0") }
    var goalWeight by remember { mutableStateOf(prefs.getString("goal", "80.0") ?: "80.0") }
    var completed by remember { mutableStateOf(loadCompleted(prefs)) }
    var weightHistory by remember { mutableStateOf(loadWeightHistory(prefs)) }
    var selectedDay by remember { mutableIntStateOf(prefs.getInt("active_day", 0).coerceIn(0, 3)) }
    var protein by remember { mutableStateOf(prefs.getString("protein", "120") ?: "120") }
    var calories by remember { mutableStateOf(prefs.getString("calories", "2600") ?: "2600") }
    var rest by remember { mutableStateOf(RestTimerStore.load(prefs, System.currentTimeMillis())) }
    fun updateRest(value: RestTimer) {
        val old = rest
        if (old.running && !value.running && value.remaining == 0) AppAlerts.finishRest(context, old.deadline)
        rest = value; RestTimerStore.save(prefs, value)
        if (old.deadline != value.deadline) AppAlerts.syncRest(context, value)
    }
    var pendingImport by remember { mutableStateOf<PreparedBackup?>(null) }
    var restoring by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var dataMessage by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            try {
                val stream = context.contentResolver.openOutputStream(uri) ?: error("파일을 열 수 없어요.")
                stream.bufferedWriter(Charsets.UTF_8).use { it.write(BackupCodec.encode(prefs)) }
                dataMessage = "백업 파일을 저장했어요. 다른 기기에 보관해 두세요."
            } catch (e: Exception) { dataMessage = "백업 실패: ${e.message ?: "파일을 저장할 수 없어요."}" }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val raw = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { BackupCodec.read(it) }
                    ?: throw IllegalArgumentException("파일을 읽을 수 없어요.")
                pendingImport = prepareBackup(raw)
            } catch (e: Exception) { dataMessage = "복원 실패: ${e.message ?: "백업 파일을 확인해 주세요."}" }
        }
    }
    LaunchedEffect(Unit) { RestTimerStore.save(prefs, rest); AppAlerts.restore(context) }
    LaunchedEffect(rest.deadline, restoring) {
        while (rest.running && !restoring) { updateRest(rest.tick(System.currentTimeMillis())); delay(250) }
    }
    fun saveWeight(value: String) {
        weight = value
        prefs.edit().putString("weight", value).apply()
        val date = todayKey()
        val parsed = value.toFloatOrNull()
        if (parsed != null && parsed.isFinite() && parsed in 20f..400f) {
            weightHistory = (weightHistory.filterNot { it.first == date } + (date to parsed)).sortedBy { it.first }
            saveWeightHistory(prefs, weightHistory)
        }
    }
    MaterialTheme(colorScheme = darkColorScheme(primary = Lime, background = Ink, surface = Panel, onBackground = White, onSurface = White)) {
        Scaffold(containerColor = Ink, bottomBar = {
            NavigationBar(containerColor = Panel, contentColor = White) {
                listOf("홈", "운동", "기록", "영양", "설정").forEachIndexed { i, label ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i; prefs.edit().putInt("active_tab", i).apply() }, icon = { Icon(when(i){0->Icons.Default.Home;1->Icons.Default.FitnessCenter;2->Icons.Default.ShowChart;3->Icons.Default.Restaurant;else->Icons.Default.Settings}, null) }, label = { Text(label, fontSize = 11.sp) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = Ink, selectedTextColor = Lime, indicatorColor = Lime, unselectedIconColor = Muted, unselectedTextColor = Muted))
                }
            }
        }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when(tab) {
                    0 -> HomeScreen(weight, goalWeight, completedThisWeek(completed), onWeight = { saveWeight(it) }, onGoal = { goalWeight = it; prefs.edit().putString("goal", it).apply() }, onWorkout = { index -> selectedDay = index; tab = 1; prefs.edit().putInt("active_tab", 1).putInt("active_day", index).apply() })
                    1 -> WorkoutScreen(selectedDay, { selectedDay = it; prefs.edit().putInt("active_day", it).apply() }, completed, { index -> val key = "${todayKey()}-$index"; completed = completed.toMutableSet().also { if (!it.add(key)) it.remove(key) }; saveCompleted(prefs, completed) }, prefs, { updateRest(rest.fresh(System.currentTimeMillis())) })
                    2 -> RecordsScreen(weight, goalWeight, completedThisWeek(completed), completed, weightHistory, onCompletedChange = { completed = it; saveCompleted(prefs, it) }, onWeight = { saveWeight(it) }, onGoal = { goalWeight = it; prefs.edit().putString("goal", it).apply() })
                    3 -> NutritionScreen(protein, calories, { protein = it; if (it.toIntOrNull()?.let { n -> n in 20..400 } == true) prefs.edit().putString("protein", it).apply() }, { calories = it; if (it.toIntOrNull()?.let { n -> n in 500..8000 } == true) prefs.edit().putString("calories", it).apply() })
                    else -> DataManagementScreen(
                        onExport = { exportLauncher.launch("homebulkup-backup-${todayKey()}.json") },
                        onImport = { importLauncher.launch(arrayOf("application/json", "text/*", "application/octet-stream")) },
                        onReset = { dataMessage = "백업 파일을 먼저 저장한 뒤 복원하는 것을 권장해요. 앱 데이터 초기화는 Android 설정에서 진행할 수 있어요." }
                    )
                }
                if (!rest.visible && rest.running) TextButton(onClick = { updateRest(rest.copy(visible = true)) }, modifier = Modifier.align(Alignment.BottomEnd).background(Panel)) { Text("휴식 ${rest.remaining}초 · 열기", color = Lime) }
                pendingImport?.let { values ->
                    AlertDialog(onDismissRequest = { if (!restoring) pendingImport = null }, title = { Text("백업을 복원할까요?") }, text = { BackupPreviewText(values.preview) }, confirmButton = {
                        TextButton(enabled = !restoring, onClick = {
                            restoring = true
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { runCatching { BackupFiles.snapshot(context, safety = true); BackupCodec.restore(prefs, values.values) } }
                                rest = RestTimerStore.load(prefs, System.currentTimeMillis())
                                restoring = false; pendingImport = null
                                result.onSuccess { (context as? Activity)?.recreate() }.onFailure { dataMessage = "복원 실패: ${it.message}" }
                            }
                        }) { Text(if (restoring) "복원 중…" else "복원") }
                    }, dismissButton = { TextButton(enabled = !restoring, onClick = { pendingImport = null }) { Text("취소") } })
                }
                if (dataMessage != null) AlertDialog(onDismissRequest = { dataMessage = null }, containerColor = Panel, title = { Text("데이터 관리", color = White) }, text = { Text(dataMessage ?: "", color = Muted) }, confirmButton = { TextButton(onClick = { dataMessage = null }) { Text("확인", color = Lime) } })
                if (rest.visible) RestTimerDialog(rest, ::updateRest)
            }
        }
    }
}

@Composable private fun HomeScreen(weight: String, goal: String, count: Int, onWeight:(String)->Unit, onGoal:(String)->Unit, onWorkout:(Int)->Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), contentPadding = PaddingValues(top = 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Column { Text("HOME BULK UP", color = Lime, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp); Text("오늘도 한 단계 성장", color = White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold) }; Box(Modifier.size(44.dp).background(Panel2, CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Default.FitnessCenter, null, tint = Lime) } } }
        item { Column(Modifier.fillMaxWidth().background(Lime, RoundedCornerShape(24.dp)).padding(20.dp)) { Text("YOUR 12-WEEK JOURNEY", color = Ink.copy(alpha=.7f), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp); Spacer(Modifier.height(8.dp)); Text("크게, 꾸준하게.", color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Black); Text("덤벨과 철봉으로 만드는 나만의 루틴", color = Ink.copy(alpha=.75f), fontSize = 13.sp); Spacer(Modifier.height(18.dp)); Row(verticalAlignment = Alignment.Bottom) { Column(Modifier.weight(1f)) { Text("현재 체중", color = Ink.copy(alpha=.65f), fontSize = 12.sp); Text("${weight.ifBlank { "74.0" }} kg", color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Bold) }; Column(horizontalAlignment = Alignment.End) { Text("목표 ${goal.ifBlank { "80.0" }} kg", color = Ink.copy(alpha=.7f), fontSize = 12.sp); Text("${progress(weight, goal)}%", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold) } }; Spacer(Modifier.height(8.dp)); LinearProgressIndicator(progress = { progress(weight, goal)/100f }, modifier = Modifier.fillMaxWidth().height(7.dp), color = Ink, trackColor = Ink.copy(alpha=.16f)) } }
        item { SectionTitle("오늘의 미션", "운동을 완료하고 기록을 쌓아봐") }
        item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { StatCard("이번 주 운동", "$count / 4", "완료 세션", Modifier.weight(1f)); StatCard("단백질 목표", "120–150g", "하루 기준", Modifier.weight(1f)) } }
        item { TodaySchedule(onWorkout) }
        item { Text("성장 원칙", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { MiniTip("01", "점진적 과부하", "횟수가 편해지면 덤벨 무게를 조금씩 올려요.", Modifier.weight(1f)); MiniTip("02", "회복도 훈련", "수면과 휴식일을 운동만큼 챙겨요.", Modifier.weight(1f)) } }
        item { Text("* 체중은 매일 비슷한 시간에 측정하고 주간 평균으로 추세를 확인하세요.", color = Muted, fontSize = 11.sp) }
    }
}

@Composable private fun WorkoutScreen(selected: Int, select:(Int)->Unit, completed:Set<String>, toggle:(Int)->Unit, prefs: android.content.SharedPreferences, timer:()->Unit) {
    var editableProgram by remember { mutableStateOf(loadWorkoutProgram(prefs)) }
    var showRoutineEditor by remember { mutableStateOf(false) }
    var showExercisePicker by remember { mutableStateOf(false) }
    var exerciseSearch by remember { mutableStateOf("") }
    val day = editableProgram.getOrElse(selected) { editableProgram.first() }
    LaunchedEffect(editableProgram) { saveWorkoutProgram(prefs, editableProgram) }
    val dateKey = currentDateKey()
    var sets by remember { mutableStateOf(TrainingStore.load(prefs, editableProgram)) }
    val today = java.time.LocalDate.parse(dateKey)
    val recovery = remember { PlanStore.recovery(prefs) }
    val displayExercises = recovery?.exercises(day.exercises, today) ?: day.exercises
    val light = recovery?.active(today) == true && recovery.mode == "light"
    var wellness by remember(dateKey) { mutableStateOf(PlanStore.wellness(prefs).firstOrNull { it.date == dateKey } ?: Wellness(dateKey)) }
    var focus by remember(selected, dateKey) { mutableStateOf(prefs.getBoolean("focus_mode", false) && prefs.getInt("focus_day", -1) == selected && prefs.getString("focus_date", "") == dateKey) }
    fun leaveFocus() { focus = false; prefs.edit().putBoolean("focus_mode", false).apply() }
    var guideExercise by remember { mutableStateOf<Exercise?>(null) }
    val gear = remember { loadEquipment(prefs) }
    var replacement by remember { mutableStateOf<Int?>(null) }
    fun updateSet(record: TrainingSet) {
        sets = sets.filterNot { it.key == record.key } + record
        TrainingStore.save(prefs, sets)
    }
    if (focus && displayExercises.isNotEmpty()) {
        FocusWorkout(day.copy(exercises = displayExercises), dateKey, prefs, sets, ::updateSet, timer, ::leaveFocus, {
            if ("$dateKey-$selected" !in completed) toggle(selected)
            leaveFocus()
        }, light)
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp), contentPadding = PaddingValues(top = 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("TRAINING PLAN", color = Lime, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp); Text("운동 프로그램", color = White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold); Text("첫 2주는 무리하지 말고 2–4회 여유를 남겨요.", color = Muted, fontSize = 12.sp) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { editableProgram.forEachIndexed { i, d -> Column(Modifier.weight(1f).background(if (selected==i) Lime else Panel, RoundedCornerShape(12.dp)).clickable { select(i) }.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text(listOf("월", "화", "수", "목", "금", "토", "일")[PlanStore.schedule(prefs).days[i] - 1], color = if(selected==i) Ink else Muted, fontSize = 11.sp); Text(listOf("PUSH","PULL","LEGS","UPPER")[i], color = if(selected==i) Ink else White, fontSize = 10.sp, fontWeight = FontWeight.Bold) } } } }
        item { Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(20.dp)).padding(18.dp)) { Text(day.subtitle, color = Lime, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp); Text(day.title, color = White, fontSize = 22.sp, fontWeight = FontWeight.Bold); Text("${displayExercises.sumOf { it.sets }}세트 · 약 35–45분", color = Muted, fontSize = 12.sp) } }
        item { WellnessEditor(wellness) { wellness = it; PlanStore.saveWellness(prefs, it) } }
        item {
            if (recovery?.active(today) == true) Text(if (recovery.mode == "rest") "휴식 주간 · 이번 주는 회복을 우선해요. 설정에서 해제할 수 있어요." else "가벼운 주간 · 원래 루틴은 보존됩니다.", color = Orange)
            Button(enabled = displayExercises.isNotEmpty(), onClick = {
                val sameSession = prefs.getInt("focus_day", -1) == selected && prefs.getString("focus_date", "") == dateKey
                prefs.edit().putBoolean("focus_mode", true).putInt("focus_day", selected).putString("focus_date", dateKey).apply()
                if (!sameSession) prefs.edit().putInt("focus_cursor", 0).apply()
                focus = true
            }, modifier = Modifier.fillMaxWidth()) { Text("운동 집중 모드 시작 · 이어하기") }
            Text("RIR = 더 할 수 있었던 횟수 · 준비 세트는 통계와 증량 판단에서 제외해요.", color = Muted, fontSize = 12.sp)
        }
        item { OutlinedButton(onClick = { showRoutineEditor = true }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = Lime)) { Icon(Icons.Default.Edit, null); Spacer(Modifier.width(8.dp)); Text("내 루틴 편집 · 운동 추가/삭제/순서/세트") } }
        itemsIndexed(displayExercises, key = { index, _ -> "${selected}-${displayExercises[index].id}" }, contentType = { _, _ -> "workout-exercise" }) { idx, exercise ->
            Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(16.dp)).padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(36.dp).background(Panel2, RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) { Text("${idx+1}".padStart(2,'0'), color = Lime, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(exercise.name, color = White, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.clickable { guideExercise = exercise })
                        Text("${exercise.detail} · ${exercise.sets}세트 × ${exercise.reps}", color = Muted, fontSize = 11.sp)
                    }
                }
                val prior = previousSession(sets, exercise.name, dateKey)
                if (prior.isNotEmpty()) Text("지난 운동 (${prior.first().date}) · " + prior.joinToString(" / ") { "${it.weight}kg × ${it.reps}${if (it.unit == "seconds") "초" else "회"}" }, color = Lime, fontSize = 12.sp)
                Text(trainingAdvice(prior, exercise, gear, recovery, wellness, today), color = Muted, fontSize = 12.sp)
                if (!available(exercise.name, gear)) Text("보유 장비로 하기 어려운 운동이에요. 대체 운동을 선택하세요.", color = Orange, fontSize = 12.sp)
                OutlinedButton(onClick = { replacement = idx }) { Text("장비에 맞는 대체 운동") }
                Spacer(Modifier.height(8.dp))
                repeat(exercise.sets) { setIndex ->
                    val setNo = setIndex + 1
                    val record = sets.firstOrNull { it.date == dateKey && it.exerciseId == exercise.id && it.number == setNo }
                        ?: TrainingSet(dateKey, exercise.id, exercise.name, setNo, unit = if (exercise.reps.contains("초")) "seconds" else "reps", kind = prior.firstOrNull { it.number == setNo }?.kind ?: "work")
                    SetEditor(record, ::updateSet) { updateSet(record.copy(done = !record.done)); if (!record.done) timer() }
                    prior.firstOrNull { it.number == setNo }?.let { old ->
                        if (record.weight.isBlank() && record.reps.isBlank()) TextButton(onClick = { updateSet(record.copy(weight = if (light) deloadWeight(old.weight.toDouble(), gear) else old.weight, reps = old.reps, kind = old.kind)) }) { Text(if (light) "지난 무게의 80%로 채우기" else "지난 기록 ${old.weight}kg × ${old.reps} 채우기") }
                    }
                }
            }
        }
        item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { OutlinedButton(onClick = timer, modifier = Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = Lime)) { Icon(Icons.Default.Timer, null); Spacer(Modifier.width(6.dp)); Text("휴식 타이머") }; Button(onClick = { toggle(selected) }, enabled = displayExercises.isNotEmpty(), modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = if("${todayKey()}-$selected" in completed) Panel2 else Lime, contentColor = if("${todayKey()}-$selected" in completed) White else Ink)) { Icon(if("${todayKey()}-$selected" in completed) Icons.Default.CheckCircle else Icons.Default.Check, null); Spacer(Modifier.width(6.dp)); Text(if("${todayKey()}-$selected" in completed) "완료 취소" else "운동 완료") } } }
        item { Text("입력은 자동 저장돼요. 맨몸 운동은 무게에 0을 입력하고, 각 세트 완료를 눌러 주세요. 이전 버전의 위치 기반 기록은 당시 루틴이 변경됐다면 종목을 확인하세요.", color = Muted, fontSize = 11.sp) }
    }

    replacement?.let { index ->
        val old = day.exercises[index]
        val candidates = exerciseCatalog.distinctBy { it.name }.filter { it.name != old.name && available(it.name, gear) && sameMovement(old.name, it.name) }
        AlertDialog(onDismissRequest = { replacement = null }, title = { Text("대체 운동 · ${old.name}") }, text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text("같은 움직임을 우선 추천해요. 변경 전 기록은 보존됩니다.")
                if (candidates.isEmpty()) Text("현재 장비에 맞는 같은 움직임의 운동이 없어요. 장비 설정 또는 루틴을 확인하세요.")
                candidates.forEach { candidate -> TextButton(onClick = {
                    val list = day.exercises.toMutableList(); list[index] = candidate.copy(id = java.util.UUID.randomUUID().toString())
                    editableProgram = editableProgram.toMutableList().also { it[selected] = day.copy(exercises = list) }
                    saveWorkoutProgram(prefs, editableProgram); replacement = null
                }) { Text(candidate.name + " · " + candidate.reps) } }
            }
        }, confirmButton = { TextButton(onClick = { replacement = null }) { Text("닫기") } })
    }
    if (showRoutineEditor) {
        AlertDialog(
            onDismissRequest = { showRoutineEditor = false },
            containerColor = Panel,
            title = { Text("루틴 편집", color = White, fontWeight = FontWeight.Bold) },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("운동 순서와 세트 수를 조정하거나 종목을 삭제할 수 있어요.", color = Muted, fontSize = 12.sp)
                    day.exercises.forEachIndexed { index, ex ->
                        Row(Modifier.fillMaxWidth().background(Panel2, RoundedCornerShape(12.dp)).padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(ex.name, color = White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text("${ex.detail} · ${ex.reps}", color = Muted, fontSize = 10.sp)
                            }
                            IconButton(onClick = { if (index > 0) { val list = day.exercises.toMutableList(); val v = list.removeAt(index); list.add(index-1, v); editableProgram = editableProgram.toMutableList().also { it[selected] = day.copy(exercises = list) } } }, enabled = index > 0, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.KeyboardArrowUp, null, tint = if(index > 0) Lime else Muted) }
                            IconButton(onClick = { if (index < day.exercises.lastIndex) { val list = day.exercises.toMutableList(); val v = list.removeAt(index); list.add(index+1, v); editableProgram = editableProgram.toMutableList().also { it[selected] = day.copy(exercises = list) } } }, enabled = index < day.exercises.lastIndex, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.KeyboardArrowDown, null, tint = if(index < day.exercises.lastIndex) Lime else Muted) }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("${ex.sets}", color = Lime, fontWeight = FontWeight.Bold)
                                Row {
                                    IconButton(onClick = { val list = day.exercises.toMutableList(); list[index] = ex.copy(sets = (ex.sets-1).coerceAtLeast(1)); editableProgram = editableProgram.toMutableList().also { it[selected] = day.copy(exercises = list) } }, enabled = ex.sets > 1, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Remove, null, tint = Lime, modifier = Modifier.size(16.dp)) }
                                    IconButton(onClick = { val list = day.exercises.toMutableList(); list[index] = ex.copy(sets = (ex.sets+1).coerceAtMost(10)); editableProgram = editableProgram.toMutableList().also { it[selected] = day.copy(exercises = list) } }, enabled = ex.sets < 10, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Add, null, tint = Lime, modifier = Modifier.size(16.dp)) }
                                }
                            }
                            IconButton(onClick = { if (day.exercises.size > 1) { val list = day.exercises.toMutableList().also { it.removeAt(index) }; editableProgram = editableProgram.toMutableList().also { it[selected] = day.copy(exercises = list) } } }, enabled = day.exercises.size > 1, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.DeleteOutline, null, tint = Orange) }
                        }
                    }
                    OutlinedButton(onClick = { exerciseSearch = ""; showExercisePicker = true }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = Lime)) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("운동 종목 추가") }
                }
            },
            confirmButton = { TextButton(onClick = { showRoutineEditor = false }) { Text("완료", color = Lime) } },
            dismissButton = { TextButton(onClick = { editableProgram = program; showRoutineEditor = false }) { Text("기본 루틴으로 되돌리기", color = Muted) } }
        )
    }
    if (showExercisePicker) {
        AlertDialog(
            onDismissRequest = { showExercisePicker = false },
            containerColor = Panel,
            title = { Text("운동 종목 선택", color = White, fontWeight = FontWeight.Bold) },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                    OutlinedTextField(value = exerciseSearch, onValueChange = { exerciseSearch = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("종목 검색") }, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = White, unfocusedTextColor = White, focusedBorderColor = Lime, unfocusedBorderColor = Muted, focusedLabelColor = Lime, unfocusedLabelColor = Muted))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        itemsIndexed(exerciseCatalog.filter { it.name.contains(exerciseSearch, true) || it.detail.contains(exerciseSearch, true) }) { _, ex ->
                            Row(Modifier.fillMaxWidth().clickable {
                                val list = day.exercises.toMutableList()
                                list.add(ex.copy(id = java.util.UUID.randomUUID().toString()))
                                editableProgram = editableProgram.toMutableList().also { it[selected] = day.copy(exercises = list) }
                                showExercisePicker = false
                            }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) { Text(ex.name, color = White, fontWeight = FontWeight.Medium); Text("${ex.detail} · ${ex.sets}세트 × ${ex.reps}", color = Muted, fontSize = 11.sp) }
                                Icon(Icons.Default.AddCircle, null, tint = Lime)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showExercisePicker = false }) { Text("닫기", color = Lime) } }
        )
    }

    val guideContext = androidx.compose.ui.platform.LocalContext.current
    guideExercise?.let { exercise ->
        AlertDialog(
            onDismissRequest = { guideExercise = null },
            containerColor = Panel,
            title = { Text(exercise.name, color = White, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        Modifier.fillMaxWidth().height(120.dp)
                            .background(Panel2, RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.FitnessCenter, contentDescription = "운동 자세 참고 그림", tint = Lime, modifier = Modifier.size(76.dp))
                    }
                    Text("운동 자세", color = Lime, fontWeight = FontWeight.Bold)
                    val n = exercise.name.lowercase()
                    val steps = when {
                        listOf("스쿼트", "런지", "레그", "스텝업").any { n.contains(it) } ->
                            listOf("발을 어깨너비 정도로 벌리고 발끝은 자연스럽게 바깥으로 둡니다.", "엉덩이를 뒤로 보내며 무릎을 발끝 방향으로 굽힙니다.", "발바닥 전체로 바닥을 밀어 천천히 일어섭니다.")
                        listOf("로우", "풀업", "풀다운", "턱걸이").any { n.contains(it) } ->
                            listOf("가슴을 살짝 들고 허리는 중립 자세로 유지합니다.", "어깨를 으쓱하지 말고 팔꿈치를 몸통 쪽으로 당깁니다.", "반동을 쓰지 말고 천천히 시작 자세로 돌아갑니다.")
                        listOf("프레스", "푸시업", "푸쉬업", "플라이").any { n.contains(it) } ->
                            listOf("견갑을 안정시키고 손목과 팔꿈치를 편안하게 정렬합니다.", "복부에 힘을 주고 무게를 통제하며 밀거나 모읍니다.", "팔꿈치를 과하게 잠그지 말고 천천히 돌아옵니다.")
                        listOf("컬", "삼두", "익스텐션", "레이즈").any { n.contains(it) } ->
                            listOf("몸통을 흔들지 말고 팔꿈치 위치를 안정적으로 유지합니다.", "목과 어깨에 힘을 빼고 목표 근육으로 천천히 움직입니다.", "반동 없이 시작 자세로 돌아오며 무게를 통제합니다.")
                        else ->
                            listOf("시작 자세에서 몸통을 안정시키고 관절을 편안하게 정렬합니다.", "반동을 줄이고 운동 범위 전체를 천천히 움직입니다.", "통증이 생기면 중단하고 무게나 동작 범위를 줄입니다.")
                    }
                    steps.forEachIndexed { i, step ->
                        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${i + 1}.", color = Lime, fontWeight = FontWeight.Bold)
                            Text(step, color = White, fontSize = 13.sp)
                        }
                    }
                    Text("주의: 통증이 생기면 즉시 멈추세요. 위 그림은 참고용 아이콘이며 실제 동작 시범 사진은 아닙니다.", color = Muted, fontSize = 11.sp)
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        val query = java.net.URLEncoder.encode(exercise.name + " 운동 자세", "UTF-8")
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/results?search_query=" + query))
                        guideContext.startActivity(intent)
                    }) { Text("유튜브 자세 영상", color = Lime) }
                    TextButton(onClick = { guideExercise = null }) { Text("닫기", color = Lime) }
                }
            }
        )
    }
}

@Composable private fun RecordsScreen(weight:String, goal:String, count:Int, completed:Set<String>, history:List<Pair<String,Float>>, onCompletedChange:(Set<String>)->Unit, onWeight:(String)->Unit, onGoal:(String)->Unit) {
    var showWeight by remember { mutableStateOf(false) }; var showGoal by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().padding(20.dp), contentPadding = PaddingValues(top = 24.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("YOUR PROGRESS", color = Lime, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp); Text("성장 기록", color = White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold); Text("작은 변화가 모여 큰 차이를 만듭니다.", color = Muted, fontSize = 13.sp) }
        item { GrowthReport(completed, history) }
        item { HistoryManager(completed, onCompletedChange) }
        item { Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(22.dp)).padding(20.dp)) { Text("현재 체중", color = Muted, fontSize = 12.sp); Row(verticalAlignment = Alignment.Bottom) { Text("$weight", color = White, fontSize = 38.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.width(6.dp)); Text("kg", color = Lime, fontSize = 15.sp); Spacer(Modifier.weight(1f)); TextButton(onClick = { showWeight = true }) { Text("수정", color = Lime) } }; HorizontalDivider(color = Panel2); Spacer(Modifier.height(14.dp)); Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("목표 체중", color = Muted, fontSize = 12.sp); Text("$goal kg", color = White, fontSize = 21.sp, fontWeight = FontWeight.Bold) }; TextButton(onClick = { showGoal = true }) { Text("목표 설정", color = Lime) } }; LinearProgressIndicator(progress = { progress(weight, goal)/100f }, modifier = Modifier.fillMaxWidth().height(7.dp), color = Lime, trackColor = Panel2) } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { StatCard("완료한 운동", "$count 회", "선택한 프로그램 기준", Modifier.weight(1f)); StatCard("목표 증가량", "+${(goal.toFloatOrNull()?:80f)-(weight.toFloatOrNull()?:74f)} kg", "현재 설정 기준", Modifier.weight(1f)) } }
        item { Text("최근 4주 운동 달력", color = White, fontWeight = FontWeight.Bold, fontSize = 18.sp) }
        item {
            Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(18.dp)).padding(14.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf("월", "화", "수", "목", "금", "토", "일").forEach { Text(it, color = Muted, fontSize = 11.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
                }
                val calendarDays = remember {
                    val cal = java.util.Calendar.getInstance()
                    cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0); cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0)
                    cal.add(java.util.Calendar.DAY_OF_YEAR, -27)
                    (0 until 28).map {
                        val key = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
                        val dayNumber = cal.get(java.util.Calendar.DAY_OF_MONTH)
                        cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
                        key to dayNumber
                    }
                }
                calendarDays.chunked(7).forEach { week ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        week.forEach { (date, number) ->
                            val didWorkout = completed.any { it.startsWith("$date-") }
                            Column(Modifier.weight(1f).padding(vertical = 5.dp).background(if (didWorkout) Lime else Panel2, RoundedCornerShape(9.dp)).padding(vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(number.toString(), color = if (didWorkout) Ink else White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text(if (didWorkout) "✓" else "·", color = if (didWorkout) Ink else Muted, fontSize = 11.sp)
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(9.dp).background(Lime, CircleShape)); Spacer(Modifier.width(6.dp)); Text("운동 완료", color = Muted, fontSize = 11.sp); Spacer(Modifier.width(12.dp)); Text("날짜를 기준으로 완료한 운동을 표시해요.", color = Muted, fontSize = 11.sp) }
            }
        }
        item { Text("체중 변화 그래프", color = White, fontWeight = FontWeight.Bold, fontSize = 18.sp) }
        item { Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(18.dp)).padding(16.dp)) {
            if (history.size < 2) Text("서로 다른 날짜에 체중을 2회 이상 기록하면 그래프가 표시돼요.", color = Muted, fontSize = 13.sp)
            else {
                WeightTrendChart(history.takeLast(14).map { it.second })
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatDate(history.takeLast(14).first().first), color = Muted, fontSize = 10.sp)
                    Text(formatDate(history.last().first), color = Muted, fontSize = 10.sp)
                }
            }
        } }
        item { Text("체중 변화 기록", color = White, fontWeight = FontWeight.Bold, fontSize = 18.sp) }
        item { Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(18.dp)).padding(16.dp)) {
            if (history.isEmpty()) Text("체중을 저장하면 날짜별 기록이 여기에 쌓여요.", color = Muted, fontSize = 13.sp)
            else {
                val recent = history.takeLast(10).reversed()
                recent.forEachIndexed { index, item ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(formatDate(item.first), color = Muted, fontSize = 12.sp)
                        Text(String.format(Locale.getDefault(), "%.1f kg", item.second), color = if(index == 0) Lime else White, fontWeight = FontWeight.Bold)
                    }
                    if(index != recent.lastIndex) HorizontalDivider(color = Panel2)
                }
                if (history.size >= 2) { val delta = history.last().second - history.first().second; Text("첫 기록 대비 ${if(delta >= 0) "+" else ""}${String.format(Locale.getDefault(), "%.1f", delta)} kg", color = Lime, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp)) }
            }
        } }
        item { Text("체중 기록 가이드", color = White, fontWeight = FontWeight.Bold, fontSize = 18.sp) }
        item { Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(18.dp)).padding(16.dp)) { Text("01  매일 같은 조건으로 측정", color = Lime, fontWeight = FontWeight.Bold); Text("기상 후 화장실을 다녀온 뒤, 식사 전 측정하면 비교하기 좋아요.", color = Muted, fontSize = 13.sp); Spacer(Modifier.height(14.dp)); Text("02  주간 평균을 확인", color = Lime, fontWeight = FontWeight.Bold); Text("하루 수치보다 2–3주 동안의 추세를 보고 섭취량을 조절하세요.", color = Muted, fontSize = 13.sp) } }
        item { Text("체중은 날짜별로 저장되며 같은 날 다시 입력하면 해당 날짜의 기록이 갱신돼요.", color = Muted, fontSize = 11.sp) }
    }
    if(showWeight) NumberDialog("현재 체중 (kg)", weight, { showWeight=false }, onWeight)
    if(showGoal) NumberDialog("목표 체중 (kg)", goal, { showGoal=false }, onGoal)
}

@Composable private fun WeightTrendChart(values: List<Float>) {
    val minValue = (values.minOrNull() ?: 0f) - 0.5f
    val maxValue = (values.maxOrNull() ?: 1f) + 0.5f
    val range = (maxValue - minValue).coerceAtLeast(1f)
    Canvas(Modifier.fillMaxWidth().height(150.dp)) {
        val left = 12f; val right = size.width - 12f
        val top = 12f; val bottom = size.height - 12f
        repeat(3) { i ->
            val y = top + (bottom - top) * i / 2f
            drawLine(Panel2, Offset(left, y), Offset(right, y), strokeWidth = 1.dp.toPx())
        }
        val points = values.mapIndexed { index, value ->
            val x = if (values.size == 1) (left + right) / 2f else left + (right - left) * index / (values.size - 1).toFloat()
            val y = bottom - ((value - minValue) / range) * (bottom - top)
            Offset(x, y)
        }
        points.zipWithNext().forEach { (a, b) -> drawLine(Lime, a, b, strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round) }
        points.forEach { drawCircle(Lime, radius = 4.dp.toPx(), center = it) }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(String.format(Locale.getDefault(), "최저 %.1fkg", values.minOrNull() ?: 0f), color = Muted, fontSize = 11.sp)
        Text(String.format(Locale.getDefault(), "최고 %.1fkg", values.maxOrNull() ?: 0f), color = Muted, fontSize = 11.sp)
    }
}

@Composable private fun NutritionScreen(protein:String, calories:String, onProtein:(String)->Unit, onCalories:(String)->Unit) {

    val context = LocalContext.current
    val photoPrefs = remember { context.getSharedPreferences("homebulkup", 0) }
    var foodPhoto by remember { mutableStateOf<Uri?>(null) }
    var foodName by remember { mutableStateOf("닭가슴살(조리)") }
    var foodGrams by remember { mutableStateOf("150") }
    var foodSearchOpen by remember { mutableStateOf(false) }
    var foodEntries by remember { mutableStateOf(runCatching { JSONArray(photoPrefs.getString("food_logs_v1", "[]")) }.getOrElse { JSONArray() }) }
    var imageLabelText by remember { mutableStateOf("사진을 선택하면 기기 내 이미지 인식 결과가 여기에 표시됩니다.") }
    val photoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        foodPhoto = uri
        if (uri == null) { imageLabelText = "사진이 선택되지 않았어요." }
        else {
            imageLabelText = "사진 분석 중…"
            try {
                val inputImage = InputImage.fromFilePath(context, uri)
                ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS).process(inputImage)
                    .addOnSuccessListener { labels ->
                        imageLabelText = if (labels.isEmpty()) "인식된 항목이 없어요. 음식 종류를 직접 선택해 주세요." else labels.take(5).joinToString(" · ") { "${it.text} ${(it.confidence * 100).toInt()}%" }
                    }
                    .addOnFailureListener { imageLabelText = "사진 자동 인식에 실패했어요. 음식 종류를 직접 선택해 주세요." }
            } catch (_: Exception) { imageLabelText = "사진을 읽을 수 없어요. 다른 사진을 선택해 주세요." }
        }
    }
    val foods = listOf(Triple("흰쌀밥", 130, 2.4), Triple("현미밥", 123, 2.7), Triple("닭가슴살(조리)", 165, 31.0), Triple("계란(삶은 것)", 155, 13.0), Triple("두부", 76, 8.1), Triple("연어(조리)", 206, 22.0), Triple("소고기 살코기(조리)", 217, 26.0), Triple("고구마(찐 것)", 130, 1.6), Triple("바나나", 89, 1.1), Triple("오트밀(마른 것)", 389, 16.9), Triple("우유", 61, 3.2), Triple("그릭요거트", 97, 9.0), Triple("아몬드", 579, 21.2), Triple("브로콜리", 35, 2.4), Triple("두유(무가당)", 33, 3.3), Triple("식빵", 265, 9.0), Triple("돼지고기 등심(조리)", 196, 27.0), Triple("김치", 15, 1.1))
    val selectedFood = foods.firstOrNull { it.first == foodName } ?: foods[0]
    val grams = (foodGrams.toFloatOrNull() ?: 0f).coerceAtLeast(0f)
    val foodKcal = selectedFood.second * grams / 100f
    val foodProtein = selectedFood.third * grams / 100f
              val macroPer100 = when (foodName) {
        "흰쌀밥" -> 28.7 to 0.3
        "현미밥" -> 25.6 to 1.0
        "닭가슴살(조리)" -> 0.0 to 3.6
        "계란(삶은 것)" -> 1.1 to 10.6
        "두부" -> 1.9 to 4.8
        "연어(조리)" -> 0.0 to 12.4
        "소고기 살코기(조리)" -> 0.0 to 11.8
        "고구마(찐 것)" -> 30.0 to 0.2
        "바나나" -> 22.8 to 0.3
        "오트밀(마른 것)" -> 66.3 to 6.9
        "우유" -> 4.8 to 3.3
        "그릭요거트" -> 3.9 to 5.0
        "아몬드" -> 21.6 to 49.9
        "브로콜리" -> 7.2 to 0.4
        "두유(무가당)" -> 1.8 to 1.8
        "식빵" -> 49.0 to 3.2
        "돼지고기 등심(조리)" -> 0.0 to 8.0
        "김치" -> 2.4 to 0.5
        else -> 0.0 to 0.0
    }
    val foodCarbs = macroPer100.first * grams / 100f
    val foodFat = macroPer100.second * grams / 100f
    var calorieGoal by remember { mutableStateOf(photoPrefs.getInt("food_goal_kcal", 2800).toString()) }
    var proteinGoal by remember { mutableStateOf(photoPrefs.getInt("food_goal_protein", 130).toString()) }
    fun persistFoodGoals() {
        photoPrefs.edit().putInt("food_goal_kcal", calorieGoal.toIntOrNull()?.coerceIn(500, 8000) ?: 2800)
            .putInt("food_goal_protein", proteinGoal.toIntOrNull()?.coerceIn(20, 400) ?: 130).apply()
    }
    fun saveFoodEntry() {
        if (grams <= 0f) return
        foodEntries.put(JSONObject().put("date", todayKey()).put("name", foodName).put("grams", grams.toInt()).put("kcal", foodKcal.toInt()).put("protein", foodProtein.toDouble()).put("carbs", foodCarbs.toDouble()).put("fat", foodFat.toDouble()))
        photoPrefs.edit().putString("food_logs_v1", foodEntries.toString()).apply()
        foodPhoto = null
    }

    LazyColumn(Modifier.fillMaxSize().padding(20.dp), contentPadding = PaddingValues(top = 24.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("FUEL YOUR GROWTH", color = Lime, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp); Text("영양 관리", color = White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold); Text("근육은 운동과 영양, 회복이 함께 만듭니다.", color = Muted, fontSize = 13.sp) }
        item { Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(22.dp)).padding(20.dp)) { Text("하루 목표", color = Muted, fontSize = 12.sp); Spacer(Modifier.height(12.dp)); Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("단백질", color = White, fontSize = 15.sp, fontWeight = FontWeight.Bold); Text("근육 회복과 성장", color = Muted, fontSize = 11.sp) }; OutlinedTextField(value = protein, onValueChange = onProtein, isError = protein.toIntOrNull()?.let { it in 20..400 } != true, supportingText = { Text("20–400g") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(115.dp), singleLine = true, suffix = { Text("g") }, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = White, unfocusedTextColor = White, focusedBorderColor = Lime, unfocusedBorderColor = Panel2)) }; Spacer(Modifier.height(16.dp)); HorizontalDivider(color = Panel2); Spacer(Modifier.height(16.dp)); Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("칼로리", color = White, fontSize = 15.sp, fontWeight = FontWeight.Bold); Text("체중 증가용 시작 목표", color = Muted, fontSize = 11.sp) }; OutlinedTextField(value = calories, onValueChange = onCalories, isError = calories.toIntOrNull()?.let { it in 500..8000 } != true, supportingText = { Text("500–8000kcal") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(130.dp), singleLine = true, suffix = { Text("kcal") }, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = White, unfocusedTextColor = White, focusedBorderColor = Lime, unfocusedBorderColor = Panel2)) } } }

        item { Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(22.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("음식 사진으로 식단 기록", color = White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(if (foodPhoto == null) "사진을 선택한 뒤 음식 종류와 섭취량을 확인해 주세요." else "사진 선택 완료 · 음식 종류와 양을 확인해 주세요.", color = Muted, fontSize = 12.sp)
            Button(onClick = { photoLauncher.launch("image/*") }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Ink)) { Icon(Icons.Default.AddAPhoto, null); Spacer(Modifier.width(8.dp)); Text("음식 사진 선택") }
            Text("기기 내 자동 인식 결과: $imageLabelText", color = Lime, fontSize = 12.sp)
                      Text("이미지 인식은 일반적인 사물·음식 라벨을 추정합니다. 정확한 음식명과 섭취량은 직접 확인해 주세요.", color = Muted, fontSize = 11.sp)
            OutlinedButton(onClick = { foodSearchOpen = true }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = Lime)) { Text(foodName, modifier = Modifier.weight(1f)); Icon(Icons.Default.ExpandMore, null) }
            OutlinedTextField(value = foodGrams, onValueChange = { foodGrams = it.filter(Char::isDigit).take(4) }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("섭취량 (g)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = White, unfocusedTextColor = White, focusedBorderColor = Lime, unfocusedBorderColor = Panel2))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f).background(Panel2, RoundedCornerShape(12.dp)).padding(12.dp)) { Text("열량", color = Muted, fontSize = 11.sp); Text("${foodKcal.toInt()} kcal", color = Lime, fontWeight = FontWeight.Bold, fontSize = 18.sp) }
                Column(Modifier.weight(1f).background(Panel2, RoundedCornerShape(12.dp)).padding(12.dp)) { Text("단백질", color = Muted, fontSize = 11.sp); Text("${String.format(Locale.getDefault(), "%.1f", foodProtein)} g", color = Lime, fontWeight = FontWeight.Bold, fontSize = 18.sp) }
            }
            Button(onClick = { saveFoodEntry() }, enabled = grams > 0f, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Ink)) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("식단 기록 저장") }
            val todayFoods = (0 until foodEntries.length()).mapNotNull { i -> runCatching { foodEntries.getJSONObject(i) }.getOrNull() }.filter { it.optString("date") == todayKey() }
            Text("오늘 기록 · ${todayFoods.sumOf { it.optInt("kcal") }} kcal · 단백질 ${String.format(Locale.getDefault(), "%.1f", todayFoods.sumOf { it.optDouble("protein") })} g", color = White, fontWeight = FontWeight.Bold)
            todayFoods.reversed().take(5).forEach { entry -> Text("${entry.optString("name")} ${entry.optInt("grams")}g · ${entry.optInt("kcal")} kcal", color = Muted, fontSize = 12.sp) }
        } }

        item { Text("간단한 벌크업 원칙", color = White, fontWeight = FontWeight.Bold, fontSize = 18.sp) }
        item { MiniTip("01", "단백질을 나눠 먹기", "매끼 단백질 식품을 포함하고 하루 목표를 여러 끼니로 나눠요.", Modifier.fillMaxWidth()) }
        item { MiniTip("02", "조금씩 더 먹기", "체중이 2주간 거의 늘지 않으면 하루 섭취량을 100–150kcal 정도 늘려보세요.", Modifier.fillMaxWidth()) }
        item { MiniTip("03", "숫자는 출발점", "칼로리 목표는 추정치예요. 활동량과 체중 추세에 맞춰 조절하세요.", Modifier.fillMaxWidth()) }
        item { Text("안내: 신장 질환 등 단백질 섭취에 제한이 필요한 건강 문제가 있다면 의료진과 상의하세요.", color = Muted, fontSize = 11.sp) }
    }

    if (foodSearchOpen) AlertDialog(onDismissRequest = { foodSearchOpen = false }, containerColor = Panel, title = { Text("음식 선택", color = White) }, text = { LazyColumn(Modifier.heightIn(max = 420.dp)) { itemsIndexed(foods) { _, food -> Row(Modifier.fillMaxWidth().clickable { foodName = food.first; foodSearchOpen = false }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(food.first, color = White); Text("100g · ${food.second} kcal · 단백질 ${food.third}g", color = Muted, fontSize = 11.sp) }; Icon(Icons.Default.ChevronRight, null, tint = Lime) } } } }, confirmButton = { TextButton(onClick = { foodSearchOpen = false }) { Text("닫기", color = Lime) } })

}

@Composable private fun DataManagementScreen(onExport: () -> Unit, onImport: () -> Unit, onReset: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), contentPadding = PaddingValues(top = 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { ScheduleSettings() }
        item { RecoverySettings() }
        item { EquipmentSettings() }
        item { AutoBackupSettings() }
        item { Text("SETTINGS", color = Lime, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp); Text("데이터 관리", color = White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold); Text("운동 기록을 안전하게 보관하세요.", color = Muted, fontSize = 13.sp) }
        item { Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(22.dp)).padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.CloudDownload, null, tint = Lime, modifier = Modifier.size(28.dp)); Spacer(Modifier.width(12.dp)); Column { Text("백업 파일 내보내기", color = White, fontWeight = FontWeight.Bold, fontSize = 16.sp); Text("체중·목표·운동 완료·세트 기록·영양 설정을 JSON으로 저장", color = Muted, fontSize = 12.sp) } }
            Spacer(Modifier.height(14.dp)); Button(onClick = onExport, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Ink)) { Icon(Icons.Default.SaveAlt, null); Spacer(Modifier.width(8.dp)); Text("백업 파일 저장") }
        } }
        item { Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(22.dp)).padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Restore, null, tint = Lime, modifier = Modifier.size(28.dp)); Spacer(Modifier.width(12.dp)); Column { Text("백업 파일 복원", color = White, fontWeight = FontWeight.Bold, fontSize = 16.sp); Text("선택한 백업의 데이터로 현재 앱 기록을 교체합니다.", color = Muted, fontSize = 12.sp) } }
            Spacer(Modifier.height(10.dp)); Text("주의: 복원하면 현재 앱 데이터가 백업 파일의 내용으로 바뀝니다. 먼저 현재 기록을 백업해 두세요.", color = Orange, fontSize = 12.sp, lineHeight = 17.sp)
            Spacer(Modifier.height(14.dp)); OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = Lime)) { Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("백업 파일 선택") }
        } }
        item { Column(Modifier.fillMaxWidth().background(Panel2, RoundedCornerShape(18.dp)).padding(16.dp)) { Text("백업에 포함되는 정보", color = White, fontWeight = FontWeight.Bold); Spacer(Modifier.height(8.dp)); listOf("현재 체중과 목표 체중", "날짜별 체중 기록", "운동 완료 기록", "세트별 무게와 반복 횟수", "단백질·칼로리 목표").forEach { Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) { Icon(Icons.Default.CheckCircle, null, tint = Lime, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text(it, color = Muted, fontSize = 13.sp) } } } }
        item { Text("백업 파일은 휴대폰 외부나 클라우드 등 안전한 위치에 보관하세요. 앱 데이터 삭제나 휴대폰 분실에 대비해 정기적으로 백업하는 것을 권장합니다.", color = Muted, fontSize = 12.sp, lineHeight = 17.sp) }
    }
}

@Composable private fun SectionTitle(title:String, subtitle:String) { Column { Text(title, color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold); Text(subtitle, color = Muted, fontSize = 12.sp) } }
@Composable private fun StatCard(title:String, value:String, caption:String, modifier:Modifier=Modifier) { Column(modifier.background(Panel, RoundedCornerShape(18.dp)).padding(16.dp)) { Text(title, color = Muted, fontSize = 11.sp); Spacer(Modifier.height(7.dp)); Text(value, color = White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold); Text(caption, color = Muted, fontSize = 10.sp) } }
@Composable private fun MiniTip(number:String, title:String, text:String, modifier:Modifier=Modifier) { Row(modifier.background(Panel, RoundedCornerShape(16.dp)).padding(14.dp), verticalAlignment = Alignment.Top) { Text(number, color = Lime, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold); Spacer(Modifier.width(10.dp)); Column { Text(title, color = White, fontSize = 13.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(4.dp)); Text(text, color = Muted, fontSize = 11.sp, lineHeight = 16.sp) } } }
@Composable private fun NumberDialog(title:String, value:String, dismiss:()->Unit, save:(String)->Unit) { var input by remember { mutableStateOf(value) }; AlertDialog(onDismissRequest = dismiss, containerColor = Panel, title = { Text(title, color = White) }, text = { OutlinedTextField(value = input, onValueChange = { input=it }, singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = White, unfocusedTextColor = White, focusedBorderColor = Lime, unfocusedBorderColor = Panel2)) }, confirmButton = { TextButton(onClick = { if(input.toFloatOrNull()?.let { it.isFinite() && it in 20f..400f } == true) { save(input); dismiss() } }) { Text("저장", color = Lime) } }, dismissButton = { TextButton(onClick = dismiss) { Text("취소", color = Muted) } }) }
private fun progress(weight:String, goal:String):Int { val w=weight.toFloatOrNull()?:74f; val g=goal.toFloatOrNull()?:80f; return if(g<=0) 0 else ((w/g)*100).toInt().coerceIn(0,100) }
private fun todayWorkout():WorkoutDay { val d = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK); return when(d) { java.util.Calendar.MONDAY -> program[0]; java.util.Calendar.TUESDAY -> program[1]; java.util.Calendar.THURSDAY -> program[2]; java.util.Calendar.FRIDAY -> program[3]; else -> program[0] } }
internal fun loadCompleted(prefs:android.content.SharedPreferences):Set<String> = try { val a=JSONArray(prefs.getString("completed", "[]")); (0 until a.length()).map { a.optString(it) }.filter { it.contains("-") }.toSet() } catch (_:Exception) { emptySet() }
private fun saveCompleted(prefs:android.content.SharedPreferences, set:Set<String>) { val a=JSONArray(); set.sorted().forEach { a.put(it) }; prefs.edit().putString("completed", a.toString()).apply() }
private fun completedThisWeek(set: Set<String>): Int {
    val today = java.time.LocalDate.now()
    val start = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)).toString()
    return set.count { it.substringBeforeLast("-") in start..today.toString() }
}


private fun todayKey(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
private fun formatDate(key: String): String = try { val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(key); SimpleDateFormat("M월 d일", Locale.KOREAN).format(d ?: Date()) } catch (_: Exception) { key }
private fun loadWeightHistory(prefs: android.content.SharedPreferences): List<Pair<String, Float>> = try {
    val arr = JSONArray(prefs.getString("weight_history", "[]"))
    (0 until arr.length()).mapNotNull { i -> val o = arr.getJSONObject(i); val v = o.optDouble("weight", Double.NaN); if (v.isNaN()) null else o.optString("date") to v.toFloat() }.sortedBy { it.first }
} catch (_: Exception) { emptyList() }
private fun saveWeightHistory(prefs: android.content.SharedPreferences, history: List<Pair<String, Float>>) {
    val arr = JSONArray(); history.forEach { (date, weight) -> arr.put(JSONObject().put("date", date).put("weight", weight)) }
    prefs.edit().putString("weight_history", arr.toString()).apply()
}
private fun loadExerciseLogs(prefs: android.content.SharedPreferences): Map<String, String> = try {
    val obj = JSONObject(prefs.getString("exercise_logs", "{}") ?: "{}"); obj.keys().asSequence().associateWith { obj.optString(it, "") }
} catch (_: Exception) { emptyMap() }
private fun saveExerciseLogs(prefs: android.content.SharedPreferences, logs: Map<String, String>) {
    val obj = JSONObject(); logs.forEach { (key, value) -> obj.put(key, value) }; prefs.edit().putString("exercise_logs", obj.toString()).apply()
}
