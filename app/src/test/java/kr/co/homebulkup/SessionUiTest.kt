package kr.co.homebulkup

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SessionUiTest {
    @get:Rule val compose = createComposeRule()
    private val prefs get() = RuntimeEnvironment.getApplication().getSharedPreferences("homebulkup", 0)
    @Before fun clear() { prefs.edit().clear().commit() }
    @Test fun setEditorRecordsTypeRirAndCompletion() {
        var record by mutableStateOf(TrainingSet("2026-11-01", "a", "덤벨 컬", 1))
        compose.setContent { MaterialTheme { SetEditor(record, { record = it }) { record = record.copy(done = !record.done) } } }
        compose.onNodeWithText("무게 kg").performTextInput("10")
        compose.onNodeWithText("횟수").performTextInput("12")
        compose.onNodeWithText("준비").performClick()
        compose.onNodeWithText("RIR 미기록").performClick()
        compose.onNodeWithText("2 회 더 가능").performClick()
        compose.onNodeWithText("1세트 완료").performClick()
        compose.runOnIdle { assertTrue(record.done); assertEquals("warmup", record.kind); assertEquals(2, record.rir) }
    }
    @Test fun focusCompletesSetAndMovesToNextExercise() {
        val day = WorkoutDay("월", "상체", "", listOf(Exercise("푸시업", "가슴", 1, "8–12회", "push"), Exercise("덤벨 컬", "이두", 1, "8–12회", "curl")))
        var records by mutableStateOf(emptyList<TrainingSet>())
        var rests = 0
        compose.setContent { MaterialTheme { FocusWorkout(day, "2026-11-01", prefs, records, { value -> records = records.filterNot { it.key == value.key } + value }, { rests++ }, {}, {}, false) } }
        compose.onNodeWithText("무게 kg").performTextInput("0")
        compose.onNodeWithText("횟수").performTextInput("12")
        compose.onNodeWithText("1세트 완료").performScrollTo().performClick()
        compose.onNodeWithText("덤벨 컬").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, prefs.getInt("focus_cursor", -1)); assertEquals(1, rests); assertTrue(records.single().done) }
    }
    @Test fun appNavigationStartsFocusModeAndRestTimer() {
        compose.setContent { HomeBulkUpApp() }
        compose.onNodeWithText("운동").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("운동 집중 모드 시작 · 이어하기"))
        compose.onNodeWithText("운동 집중 모드 시작 · 이어하기").performClick()
        compose.onNodeWithText("운동 집중 모드").assertIsDisplayed()
        compose.onNodeWithText("무게 kg").performTextInput("10")
        compose.onNodeWithText("횟수").performTextInput("12")
        compose.onNodeWithText("1세트 완료").performScrollTo().performClick()
        compose.onNodeWithText("세트 휴식 타이머").assertIsDisplayed()
        compose.onNodeWithText("닫기").performClick()
        compose.runOnIdle {
            assertEquals(1, prefs.getInt("focus_cursor", -1))
            assertTrue(TrainingStore.load(prefs, program).single().done)
        }
    }
    @Test fun focusRestoresSavedPosition() {
        prefs.edit().putInt("focus_cursor", 1).commit()
        val day = WorkoutDay("월", "상체", "", listOf(Exercise("푸시업", "가슴", 1, "12회", "a"), Exercise("덤벨 컬", "이두", 1, "12회", "b")))
        compose.setContent { MaterialTheme { FocusWorkout(day, "2026-11-01", prefs, emptyList(), {}, {}, {}, {}, false) } }
        compose.onNodeWithText("덤벨 컬").assertIsDisplayed()
    }
    @Test fun historyDeleteCanBeUndoneWithoutLosingOtherData() {
        val record = TrainingSet("2026-11-01", "a", "덤벨 컬", 1, "10", "12", true)
        TrainingStore.save(prefs, listOf(record))
        compose.setContent { MaterialTheme { HistoryManager(emptySet(), {}) } }
        compose.onNodeWithText("과거 기록 수정 · 삭제 · 실행 취소").performClick()
        compose.onNodeWithText("삭제").performClick()
        compose.onNodeWithText("실행 취소").performClick()
        compose.runOnIdle { assertEquals(record, TrainingStore.load(prefs, program).single()) }
    }
}
