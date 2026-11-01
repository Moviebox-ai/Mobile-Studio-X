package com.aaryo.mobilestudio.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class BuildRecord(
    val id: String,
    val projectName: String,
    val task: String,
    val timestamp: Long,
    val durationMs: Long,
    val isSuccess: Boolean,
    val artifactPath: String?,
    val artifactSizeBytes: Long?,
    val errorSummary: String? = null,
    val errorCategory: String? = null
)

class BuildHistoryManager(context: Context) {
    private val historyFile = File(context.filesDir, "build_history.json")

    @Synchronized
    fun listRecords(): List<BuildRecord> {
        if (!historyFile.isFile) return emptyList()
        return runCatching {
            val jsonArray = JSONArray(historyFile.readText())
            val list = mutableListOf<BuildRecord>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    BuildRecord(
                        id = obj.optString("id", "${obj.optLong("timestamp")}"),
                        projectName = obj.optString("projectName", "Project"),
                        task = obj.optString("task", "assembleDebug"),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                        durationMs = obj.optLong("durationMs", 0L),
                        isSuccess = obj.optBoolean("isSuccess", false),
                        artifactPath = obj.optString("artifactPath").takeIf { it.isNotBlank() },
                        artifactSizeBytes = if (obj.has("artifactSizeBytes")) obj.optLong("artifactSizeBytes") else null,
                        errorSummary = obj.optString("errorSummary").takeIf { it.isNotBlank() },
                        errorCategory = obj.optString("errorCategory").takeIf { it.isNotBlank() }
                    )
                )
            }
            list.sortedByDescending { it.timestamp }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun recordBuild(record: BuildRecord) {
        val current = listRecords().toMutableList()
        current.add(0, record)
        val trimmed = current.take(50) // keep last 50 builds
        val jsonArray = JSONArray()
        trimmed.forEach { r ->
            val obj = JSONObject().apply {
                put("id", r.id)
                put("projectName", r.projectName)
                put("task", r.task)
                put("timestamp", r.timestamp)
                put("durationMs", r.durationMs)
                put("isSuccess", r.isSuccess)
                r.artifactPath?.let { put("artifactPath", it) }
                r.artifactSizeBytes?.let { put("artifactSizeBytes", it) }
                r.errorSummary?.let { put("errorSummary", it) }
                r.errorCategory?.let { put("errorCategory", it) }
            }
            jsonArray.put(obj)
        }
        runCatching { historyFile.writeText(jsonArray.toString(2)) }
    }

    @Synchronized
    fun clearHistory() {
        if (historyFile.exists()) historyFile.delete()
    }
}
