package com.example.rainwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 负责把缓存的预报画到小组件上。 */
object RainWidget {
    private const val BLOCKS = "▁▂▃▄▅▆▇█"

    fun updateAll(context: Context) {
        val mgr = AppWidgetManager.getInstance(context)
        val ids = mgr.getAppWidgetIds(ComponentName(context, RainWidgetProvider::class.java))
        if (ids.isNotEmpty()) mgr.updateAppWidget(ids, build(context))
    }

    /** 标题 + 详情两行文字，设置页预览也复用它。 */
    fun describe(o: RainOutlook): Pair<String, String> {
        if (!o.hasRain) {
            return "☀ 12 小时内无雨" to "最高降水概率 ${o.maxProb}%"
        }
        val start = o.hours[o.firstRain].time
        val end = o.rainEnd?.let { o.hours[it].time }
        val title = if (o.firstRain == 0) {
            "☔ 现在可能有${o.kind}"
        } else {
            "☔ %02d:00 起可能有%s".format(start.hour, o.kind)
        }
        val parts = mutableListOf<String>()
        when {
            o.firstRain > 0 && end != null -> parts += "约 ${o.firstRain} 小时后，%02d:00 停".format(end.hour)
            o.firstRain > 0 -> parts += "约 ${o.firstRain} 小时后"
            end != null -> parts += "预计 %02d:00 停".format(end.hour)
            else -> parts += "持续 12 小时以上"
        }
        parts += "概率 ${o.maxProb}%"
        if (o.totalMm >= 0.1) parts += "约 %.1f mm".format(o.totalMm)
        return title to parts.joinToString(" · ")
    }

    /** 每小时一个方块，高度 = 降水概率；有雨的小时用亮色。 */
    private fun strip(o: RainOutlook): CharSequence {
        val sb = SpannableStringBuilder()
        o.hours.forEachIndexed { i, h ->
            val level = ((h.prob * (BLOCKS.length - 1) + 50) / 100).coerceIn(0, BLOCKS.length - 1)
            val s = sb.length
            sb.append(BLOCKS[level])
            val color = if (o.rainy[i]) Color.WHITE else Color.argb(90, 255, 255, 255)
            sb.setSpan(ForegroundColorSpan(color), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (o.hours.isNotEmpty()) {
            val s = sb.length
            sb.append("  %02d→%02d时".format(o.hours.first().time.hour, o.hours.last().time.hour))
            sb.setSpan(ForegroundColorSpan(Color.argb(150, 255, 255, 255)), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    fun build(context: Context): RemoteViews {
        val p = Prefs(context)
        val v = RemoteViews(context.packageName, R.layout.widget_rain)

        val settingsPi = PendingIntent.getActivity(
            context, 1,
            Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val refreshPi = PendingIntent.getBroadcast(
            context, 0,
            Intent(context, RainWidgetProvider::class.java).setAction(RainWidgetProvider.ACTION_REFRESH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        v.setOnClickPendingIntent(R.id.root, refreshPi)
        v.setOnClickPendingIntent(R.id.footer, settingsPi)

        fun message(title: String, detail: String) {
            v.setInt(R.id.root, "setBackgroundResource", R.drawable.bg_dry)
            v.setTextViewText(R.id.title, title)
            v.setTextViewText(R.id.detail, detail)
            v.setViewVisibility(R.id.strip, View.GONE)
            v.setTextViewText(R.id.footer, "⚙ 设置")
        }

        if (!p.hasLocation) {
            message("📍 未设置位置", "点按这里打开设置")
            v.setOnClickPendingIntent(R.id.root, settingsPi)
            return v
        }

        val json = p.lastJson
        val outlook = json?.let { runCatching { Weather.parse(it, p.threshold) }.getOrNull() }
        if (outlook == null || outlook.hours.size < Weather.WINDOW_HOURS / 2) {
            val err = p.lastError
            if (err != null) message("⚠ 获取天气失败", "点按重试 · $err")
            else message("⏳ 正在获取天气…", "点按可手动刷新")
            return v
        }

        val updated = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(p.lastFetch))
        val footer = buildString {
            append(p.placeName.substringBefore(","))
            append(" · ").append(updated).append(" 更新")
            if (p.lastError != null) append(" · 最近一次刷新失败")
        }

        if (!outlook.hasRain && p.hideWhenDry) {
            // 只在有雨时显示：无雨时整个小组件透明、无文字（点按仍可刷新）
            v.setInt(R.id.root, "setBackgroundResource", R.drawable.bg_empty)
            for (id in intArrayOf(R.id.title, R.id.detail, R.id.strip, R.id.footer)) {
                v.setViewVisibility(id, View.INVISIBLE)
            }
            return v
        }

        val (title, detail) = describe(outlook)
        v.setInt(
            R.id.root, "setBackgroundResource",
            if (outlook.hasRain) R.drawable.bg_rain else R.drawable.bg_dry,
        )
        v.setTextViewText(R.id.title, title)
        v.setTextViewText(R.id.detail, detail)
        v.setTextViewText(R.id.strip, strip(outlook))
        v.setTextViewText(R.id.footer, footer)
        return v
    }
}
