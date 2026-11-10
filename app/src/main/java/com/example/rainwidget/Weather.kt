package com.example.rainwidget

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

data class Place(val name: String, val lat: Double, val lon: Double)

data class Hour(val time: LocalDateTime, val prob: Int, val mm: Double, val code: Int)

/** 未来最多 12 小时（从当前整点开始）的降水情况。 */
data class RainOutlook(val hours: List<Hour>, val rainy: List<Boolean>) {
    val firstRain: Int get() = rainy.indexOf(true)
    val hasRain: Boolean get() = firstRain >= 0

    /** 降雨开始后第一个不下雨的小时下标；null 表示一直持续到 12 小时窗口末尾。 */
    val rainEnd: Int?
        get() {
            if (!hasRain) return null
            for (i in firstRain until hours.size) if (!rainy[i]) return i
            return null
        }

    val maxProb: Int get() = hours.maxOfOrNull { it.prob } ?: 0
    val totalMm: Double get() = hours.sumOf { it.mm }

    /** 雨 / 雷雨 / 雪，根据有雨时段的 WMO 天气代码判断。 */
    val kind: String
        get() {
            val codes = hours.filterIndexed { i, _ -> rainy[i] }.map { it.code }
            return when {
                codes.any { it in 95..99 } -> "雷雨"
                codes.isNotEmpty() && codes.all { it in 71..77 || it == 85 || it == 86 } -> "雪"
                else -> "雨"
            }
        }
}

object Weather {
    const val WINDOW_HOURS = 12

    /** 逐小时预报降水量达到这个值（mm）也视为有雨，即使概率低于阈值。 */
    private const val MM_THRESHOLD = 0.5

    private fun httpGet(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", "RainWidget/1.0 (Android)")
        try {
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("HTTP $code ${body.take(120)}")
            return body
        } finally {
            c.disconnect()
        }
    }

    /** 拉取 24 小时逐小时预报（多拿一些，缓存过一会儿再渲染时仍够 12 小时）。 */
    fun fetchForecastJson(lat: Double, lon: Double): String = httpGet(
        "https://api.open-meteo.com/v1/forecast" +
            "?latitude=$lat&longitude=$lon" +
            "&hourly=precipitation_probability,precipitation,weather_code" +
            "&forecast_hours=24&timezone=auto"
    )

    fun parse(json: String, probThreshold: Int, now: Instant = Instant.now()): RainOutlook {
        val o = JSONObject(json)
        val offset = ZoneOffset.ofTotalSeconds(o.optInt("utc_offset_seconds", 0))
        val nowHour = LocalDateTime.ofInstant(now, offset).truncatedTo(ChronoUnit.HOURS)
        val h = o.getJSONObject("hourly")
        val times = h.getJSONArray("time")
        val probs = h.optJSONArray("precipitation_probability")
        val mms = h.optJSONArray("precipitation")
        val codes = h.optJSONArray("weather_code")

        val hours = ArrayList<Hour>()
        for (i in 0 until times.length()) {
            val t = LocalDateTime.parse(times.getString(i))
            if (t.isBefore(nowHour)) continue
            if (hours.size >= WINDOW_HOURS) break
            hours += Hour(
                time = t,
                prob = if (probs == null || probs.isNull(i)) 0 else probs.getInt(i),
                mm = if (mms == null || mms.isNull(i)) 0.0 else mms.getDouble(i),
                code = if (codes == null || codes.isNull(i)) 0 else codes.getInt(i),
            )
        }
        val rainy = hours.map { it.prob >= probThreshold || it.mm >= MM_THRESHOLD }
        return RainOutlook(hours, rainy)
    }

    fun searchCity(query: String): List<Place> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val json = httpGet(
            "https://geocoding-api.open-meteo.com/v1/search?name=$q&count=8&language=zh&format=json"
        )
        val arr = JSONObject(json).optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val r = arr.getJSONObject(i)
            val label = listOf(r.optString("name"), r.optString("admin1"), r.optString("country"))
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(", ")
            Place(label, r.getDouble("latitude"), r.getDouble("longitude"))
        }
    }
}
