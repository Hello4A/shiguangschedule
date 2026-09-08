package com.xingheyuzhuan.shiguangschedule.service.qzh5

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.data.model.CourseImportExport
import com.xingheyuzhuan.shiguangschedule.data.model.CourseImportExport.CourseConfigJsonModel
import com.xingheyuzhuan.shiguangschedule.data.model.CourseImportExport.ImportCourseJsonModel
import com.xingheyuzhuan.shiguangschedule.data.repository.AppSettingsRepository
import com.xingheyuzhuan.shiguangschedule.data.repository.CourseConversionRepository
import com.xingheyuzhuan.shiguangschedule.data.sync.WidgetDataSynchronizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.android.annotation.KoinWorker
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.DayOfWeek
import java.time.LocalDate

@KoinWorker
class Qzh5AutoSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    private val appSettingsRepository: AppSettingsRepository,
    private val courseConversionRepository: CourseConversionRepository,
    private val widgetDataSynchronizer: WidgetDataSynchronizer
) : CoroutineWorker(appContext, workerParams) {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun doWork(): Result {
        val credentials = Qzh5CredentialStore(applicationContext).load()
            ?: return Result.success()

        return try {
            val tableId = credentials.tableId.ifBlank {
                appSettingsRepository.getAppSettingsOnce().currentCourseTableId
            }
            if (tableId.isBlank()) {
                Log.w(TAG, "没有可同步的目标课表")
                return Result.success()
            }

            val token = login(credentials.userNo, credentials.encryptedPwd)
            val curriculum = fetchCurriculum(token)
            val courses = convertCourses(curriculum)

            if (courses.isEmpty()) {
                Log.w(TAG, "服务器返回课程为空，避免覆盖本地课表")
                return Result.retry()
            }

            val config = buildCourseConfig(curriculum)
            val current = courseConversionRepository.exportCourseTableToJson(tableId)

            val courseChanged = current == null ||
                current.courses.map(::fingerprint).sorted() != courses.map(::fingerprint).sorted()

            val configChanged = config != null && (
                current == null ||
                current.config.semesterStartDate != config.semesterStartDate ||
                current.config.semesterTotalWeeks != config.semesterTotalWeeks ||
                current.config.firstDayOfWeek != config.firstDayOfWeek
            )

            if (!courseChanged && !configChanged) {
                Log.d(TAG, "课表无变化")
                return Result.success()
            }

            if (courseChanged) {
                courseConversionRepository.importCoursesFromList(tableId, courses)
            }
            if (config != null) {
                courseConversionRepository.importCourseConfig(tableId, config)
            }

            // 若同步的是当前课表，立刻刷新 Widget/提醒相关缓存。
            val currentTableId = appSettingsRepository.getAppSettingsOnce().currentCourseTableId
            if (tableId == currentTableId) {
                widgetDataSynchronizer.syncNow()
            }

            notifyChanged(courseChanged, configChanged)
            Log.i(TAG, "检测到课表变化并完成自动更新")
            Result.success()
        } catch (e: Throwable) {
            Log.e(TAG, "qzh5 自动同步失败", e)
            Result.retry()
        }
    }

    private suspend fun login(userNo: String, encryptedPwd: String): String = withContext(Dispatchers.IO) {
        val body = listOf(
            "userNo" to userNo,
            "pwd" to encryptedPwd,
            "encode" to "1",
            "captchaData" to "",
            "codeVal" to ""
        ).joinToString("&") { (k, v) ->
            URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
        }

        val response = request(
            url = LOGIN_URL,
            body = body,
            headers = mapOf("Content-Type" to "application/x-www-form-urlencoded")
        )

        val root = json.parseToJsonElement(response).jsonObject
        if (root["code"]?.jsonPrimitive?.contentOrNull != "1") {
            error(root["Msg"]?.jsonPrimitive?.contentOrNull ?: "qzh5 登录失败")
        }

        root["data"]?.jsonObject
            ?.get("token")?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: error("登录成功但未返回 token")
    }

    private suspend fun fetchCurriculum(token: String): JsonObject = withContext(Dispatchers.IO) {
        val url = CURRICULUM_URL +
            "?week=&kbjcmsid=" + URLEncoder.encode(KBJCMSID, "UTF-8")

        val response = request(
            url = url,
            body = "",
            headers = mapOf("token" to token)
        )

        val root = json.parseToJsonElement(response).jsonObject
        if (root["code"]?.jsonPrimitive?.contentOrNull != "1") {
            error(root["Msg"]?.jsonPrimitive?.contentOrNull ?: "课程表获取失败")
        }
        root
    }

    private fun request(url: String, body: String, headers: Map<String, String>): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            doInput = true
            doOutput = true
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json, text/plain, */*")
            setRequestProperty("Origin", "https://qzh5.ynvct.com")
            setRequestProperty("Referer", "https://qzh5.ynvct.com/sjd/")
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/140 Mobile Safari/537.36"
            )
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
        }

        try {
            connection.outputStream.use { out ->
                out.write(body.toByteArray(Charsets.UTF_8))
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("HTTP $code: $text")
            return text
        } finally {
            connection.disconnect()
        }
    }

    private fun convertCourses(root: JsonObject): List<ImportCourseJsonModel> {
        val firstData = root["data"]?.jsonArray?.firstOrNull()?.jsonObject ?: return emptyList()
        val rawCourses = firstData["courses"]?.jsonArray ?: return emptyList()

        return rawCourses.mapNotNull { element ->
            val c = element.jsonObject
            val name = string(c, "courseName", "name").trim()
            val day = normalizeDay(string(c, "weekDay", "weekday", "day"))
            val sections = parseSections(string(c, "weekNoteDetail", "classTime", "sections"))
            val weeks = parseWeeks(
                details = string(c, "classWeekDetails"),
                fallback = string(c, "classWeek", "weeks", "week")
            )

            if (name.isBlank() || day !in 1..7 || sections == null || weeks.isEmpty()) {
                return@mapNotNull null
            }

            val startTime = string(c, "startTime")
            val endTime = string(c, "endTIme", "endTime")
            val custom = TIME_REGEX.matches(startTime) && TIME_REGEX.matches(endTime)

            ImportCourseJsonModel(
                name = name,
                teacher = string(c, "teacherName", "teacher").trim(),
                position = string(c, "classroomName", "location", "position").trim(),
                day = day,
                startSection = sections.first,
                endSection = sections.second,
                weeks = weeks,
                isCustomTime = custom,
                customStartTime = if (custom) startTime else null,
                customEndTime = if (custom) endTime else null
            )
        }
    }

    private fun buildCourseConfig(root: JsonObject): CourseConfigJsonModel? {
        val firstData = root["data"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        val top = firstData["topInfo"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null

        val currentWeek = top["week"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            ?: firstData["week"]?.jsonPrimitive?.intOrNull
            ?: return null
        val maxWeek = top["maxWeek"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 20
        val today = top["today"]?.jsonPrimitive?.contentOrNull?.let { LocalDate.parse(it) } ?: return null

        val daysFromMonday = when (today.dayOfWeek) {
            DayOfWeek.SUNDAY -> 6L
            else -> (today.dayOfWeek.value - 1).toLong()
        }
        val currentMonday = today.minusDays(daysFromMonday)
        val semesterStart = currentMonday.minusWeeks((currentWeek - 1).toLong())

        return CourseConfigJsonModel(
            semesterStartDate = semesterStart.toString(),
            semesterTotalWeeks = maxWeek,
            firstDayOfWeek = 1
        )
    }

    private fun string(obj: JsonObject, vararg keys: String): String {
        for (key in keys) {
            val value = obj[key]?.jsonPrimitive?.contentOrNull
            if (!value.isNullOrBlank()) return value
        }
        return ""
    }

    private fun normalizeDay(raw: String): Int {
        raw.trim().toIntOrNull()?.let { return if (it == 0) 7 else it }
        return when (raw.trim()) {
            "一", "周一", "星期一" -> 1
            "二", "周二", "星期二" -> 2
            "三", "周三", "星期三" -> 3
            "四", "周四", "星期四" -> 4
            "五", "周五", "星期五" -> 5
            "六", "周六", "星期六" -> 6
            "日", "天", "周日", "星期日", "星期天" -> 7
            else -> 0
        }
    }

    private fun parseSections(raw: String): Pair<Int, Int>? {
        val values = DIGITS_REGEX.findAll(raw).mapNotNull { match ->
            var n = match.value.toIntOrNull() ?: return@mapNotNull null
            if (n >= 100) n %= 100
            n.takeIf { it in 1..30 }
        }.distinct().sorted().toList()

        return if (values.isEmpty()) null else values.first() to values.last()
    }

    private fun parseWeeks(details: String, fallback: String): List<Int> {
        if (details.isNotBlank()) {
            return DIGITS_REGEX.findAll(details)
                .mapNotNull { it.value.toIntOrNull() }
                .filter { it in 1..60 }
                .distinct()
                .sorted()
                .toList()
        }

        var text = fallback.trim()
            .replace('（', '(')
            .replace('）', ')')
            .replace("至", "-")
            .replace("到", "-")
            .replace("～", "-")
            .replace("~", "-")
            .replace("—", "-")
            .replace("–", "-")
            .replace("－", "-")
            .replace(" ", "")

        val oddOnly = text.contains("(单)") || text.contains("单周")
        val evenOnly = text.contains("(双)") || text.contains("双周")
        text = text
            .replace("(单)", "")
            .replace("(双)", "")
            .replace("单周", "")
            .replace("双周", "")
            .replace("周", "")
            .trim(',')

        val result = mutableSetOf<Int>()
        text.split(',').filter { it.isNotBlank() }.forEach { part ->
            val range = RANGE_REGEX.matchEntire(part)
            if (range != null) {
                val a = range.groupValues[1].toInt()
                val b = range.groupValues[2].toInt()
                for (week in minOf(a, b)..maxOf(a, b)) {
                    if (oddOnly && week % 2 == 0) continue
                    if (evenOnly && week % 2 != 0) continue
                    if (week in 1..60) result += week
                }
            } else {
                val week = part.toIntOrNull() ?: return@forEach
                if (oddOnly && week % 2 == 0) return@forEach
                if (evenOnly && week % 2 != 0) return@forEach
                if (week in 1..60) result += week
            }
        }
        return result.sorted()
    }

    private fun fingerprint(course: ImportCourseJsonModel): String = listOf(
        course.name.trim(),
        course.teacher.trim(),
        course.position.trim(),
        course.day.toString(),
        (course.startSection ?: 0).toString(),
        (course.endSection ?: 0).toString(),
        course.weeks.sorted().joinToString(","),
        course.isCustomTime.toString(),
        course.customStartTime.orEmpty(),
        course.customEndTime.orEmpty()
    ).joinToString("|")

    private fun fingerprint(course: CourseImportExport.ExportCourseJsonModel): String = listOf(
        course.name.trim(),
        course.teacher.trim(),
        course.position.trim(),
        course.day.toString(),
        (course.startSection ?: 0).toString(),
        (course.endSection ?: 0).toString(),
        course.weeks.sorted().joinToString(","),
        course.isCustomTime.toString(),
        course.customStartTime.orEmpty(),
        course.customEndTime.orEmpty()
    ).joinToString("|")

    private fun notifyChanged(courseChanged: Boolean, configChanged: Boolean) {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "课表自动同步",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "学校教务课表发生变化时提醒"
                }
            )
        }

        val detail = when {
            courseChanged && configChanged -> "课程和学期配置发生变化，已自动更新"
            courseChanged -> "检测到课程发生变化，已自动更新"
            else -> "检测到学期配置发生变化，已自动更新"
        }

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("拾光课程表已自动同步")
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setAutoCancel(true)
            .build()

        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    companion object {
        private const val TAG = "Qzh5AutoSync"
        private const val CHANNEL_ID = "qzh5_auto_sync"
        private const val NOTIFICATION_ID = 62027

        private const val LOGIN_URL = "https://qzh5.ynvct.com/bzb_njwhd/login"
        private const val CURRICULUM_URL = "https://qzh5.ynvct.com/bzb_njwhd/student/curriculum"
        private const val KBJCMSID = "F144CF7B11C446FAA4F813BCE82A79E3"

        private val DIGITS_REGEX = Regex("\\d+")
        private val RANGE_REGEX = Regex("^(\\d+)-(\\d+)$")
        private val TIME_REGEX = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
    }
}
