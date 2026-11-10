package com.example.rainwidget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class RainWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        mgr.updateAppWidget(ids, RainWidget.build(context))
        RefreshWorker.schedulePeriodic(context)
        // 数据超过 20 分钟就顺手刷新一次
        if (System.currentTimeMillis() - Prefs(context).lastFetch > 20 * 60_000L) {
            RefreshWorker.refreshNow(context)
        }
    }

    override fun onEnabled(context: Context) {
        RefreshWorker.schedulePeriodic(context)
    }

    override fun onDisabled(context: Context) {
        RefreshWorker.cancelAll(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(android.content.ComponentName(context, RainWidgetProvider::class.java))
            val v = RemoteViews(context.packageName, R.layout.widget_rain)
            v.setTextViewText(R.id.footer, "刷新中…")
            mgr.partiallyUpdateAppWidget(ids, v)
            RefreshWorker.refreshNow(context)
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.example.rainwidget.ACTION_REFRESH"
    }
}
