package kr.co.homebulkup

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BackupCodecTest {
    private fun backup(values: JSONObject) = JSONObject().put("app", "HomeBulkUp").put("backupVersion", 1).put("preferences", values).toString()
    @Test fun legacyAndNewTypedFieldsRoundTrip() {
        val values = mapOf("weight" to "74.0", "active_day" to 2, "gear_dumbbells" to false, "rest_deadline" to 1234567890123L, "training_sets_v2" to "[]")
        assertEquals(values, BackupCodec.decode(BackupCodec.encodeValues(values)))
    }
    @Test(expected = IllegalArgumentException::class) fun wrongTypeCannotCorruptTypedPreferences() {
        BackupCodec.decode(backup(JSONObject().put("active_day", "2")))
    }
    @Test(expected = IllegalArgumentException::class) fun outOfRangeTabRejected() {
        BackupCodec.decode(backup(JSONObject().put("active_tab", 999)))
    }
    @Test(expected = IllegalArgumentException::class) fun nonFiniteWeightRejected() {
        BackupCodec.decode(backup(JSONObject().put("weight", "NaN")))
    }
    @Test(expected = Exception::class) fun malformedNestedHistoryRejected() {
        BackupCodec.decode(backup(JSONObject().put("weight_history", "[{\"date\":\"bad\",\"weight\":74}]")))
    }
    @Test(expected = IllegalArgumentException::class) fun otherAppRejected() {
        BackupCodec.decode(backup(JSONObject().put("weight", "74")).replace("HomeBulkUp", "OtherApp"))
    }
    @Test(expected = IllegalArgumentException::class) fun emptyBackupRejected() { BackupCodec.decode(backup(JSONObject())) }
    @Test fun unknownFieldsCannotInjectSettings() {
        val decoded = BackupCodec.decode(backup(JSONObject().put("weight", "74").put("tree", "content://untrusted")))
        assertEquals(setOf("weight"), decoded.keys)
    }
    @Test fun externalDirectoryPermissionNeverExported() {
        val raw = BackupCodec.encodeValues(mapOf("weight" to "74", "tree" to "content://private", "token" to "sensitive"))
        assertFalse(raw.contains("content://private")); assertFalse(raw.contains("sensitive"))
    }
    @Test(expected = IllegalArgumentException::class) fun oversizedStreamRejectedBeforeReadingWholeFile() {
        BackupCodec.read(java.io.StringReader("x".repeat(8 * 1024 * 1024 + 1)))
    }
    @Test(expected = IllegalArgumentException::class) fun duplicateRoutineIdsRejected() {
        val routine = (0..3).joinToString(",", "[", "]") { "{\"exercises\":[{\"name\":\"컬\",\"sets\":3,\"id\":\"duplicate\"}]}" }
        BackupCodec.decode(backup(JSONObject().put("custom_workout_program_v1", routine)))
    }
}
