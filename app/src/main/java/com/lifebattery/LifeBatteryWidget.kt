package com.lifebattery

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews

class LifeBatteryWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { id -> renderWidget(context, mgr, id) }
    }

    companion object {
        fun renderWidget(context: Context, mgr: AppWidgetManager, widgetId: Int) {
            val prefs = context.getSharedPreferences("life_battery_v1", Context.MODE_PRIVATE)
            val battery = prefs.getFloat("battery", 100f).toInt().coerceIn(0, 100)

            val color = when {
                battery >= 60 -> Color.parseColor("#4ADE80")
                battery >= 35 -> Color.parseColor("#FBBF24")
                battery >= 15 -> Color.parseColor("#F97316")
                else -> Color.parseColor("#EF4444")
            }
            val status = when {
                battery >= 80 -> "Excellent"
                battery >= 60 -> "Good"
                battery >= 40 -> "Okay"
                battery >= 20 -> "Low"
                else -> "Critical"
            }

            val views = RemoteViews(context.packageName, R.layout.widget_life_battery)
            views.setTextViewText(R.id.widget_pct, "$battery%")
            views.setTextViewText(R.id.widget_status, status)
            views.setTextColor(R.id.widget_status, color)
            views.setInt(R.id.widget_indicator, "setBackgroundColor", color)

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, pi)

            mgr.updateAppWidget(widgetId, views)
        }

        fun refreshAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, LifeBatteryWidget::class.java))
            ids.forEach { renderWidget(context, mgr, it) }
        }
    }
}
