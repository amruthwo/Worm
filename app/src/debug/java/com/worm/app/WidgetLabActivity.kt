package com.worm.app

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.Color
import android.os.Bundle
import android.util.SizeF
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Debug only. Shows one widget at many sizes at once so layouts can be checked without a launcher:
 *   adb shell appwidget grantbind --package com.worm.app.debug
 *   adb shell am start -n com.worm.app.debug/com.worm.app.WidgetLabActivity \
 *       --es provider com.worm.app.widget.PlayerWidgetReceiver --es sizes 80x80,240x70,320x70
 * Sizes are width x height in dp.
 */
class WidgetLabActivity : Activity() {
    private lateinit var host: AppWidgetHost
    private val ids = mutableListOf<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host = AppWidgetHost(this, 4242)
        host.startListening()
        val mgr = AppWidgetManager.getInstance(this)
        val provider = ComponentName(this, intent.getStringExtra("provider") ?: "com.worm.app.widget.PlayerWidgetReceiver")
        val sizes = (intent.getStringExtra("sizes") ?: "80x80,240x70,320x70,400x70,160x160,240x160,320x160,160x240,240x240,320x320,80x240")
            .split(',').mapNotNull { s -> s.split('x').takeIf { it.size == 2 }?.let { it[0].toInt() to it[1].toInt() } }
        val d = resources.displayMetrics.density
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24); setBackgroundColor(Color.rgb(60, 60, 70)) }
        sizes.forEach { (w, h) ->
            val id = host.allocateAppWidgetId().also { ids += it }
            if (!mgr.bindAppWidgetIdIfAllowed(id, provider)) {
                col.addView(TextView(this).apply { text = "Not allowed to bind: run appwidget grantbind"; setTextColor(Color.WHITE) }); return@forEach
            }
            val opts = Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, w); putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, w)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, h); putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, h)
                putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, arrayListOf(SizeF(w.toFloat(), h.toFloat())))
            }
            mgr.updateAppWidgetOptions(id, opts)
            val view = host.createView(this, id, mgr.getAppWidgetInfo(id))
            view.updateAppWidgetSize(opts, listOf(SizeF(w.toFloat(), h.toFloat())))
            col.addView(TextView(this).apply { text = "${w}×${h} dp"; setTextColor(Color.WHITE); setPadding(0, 16, 0, 4) })
            col.addView(view, LinearLayout.LayoutParams((w * d).toInt(), (h * d).toInt()))
        }
        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onDestroy() {
        ids.forEach { host.deleteAppWidgetId(it) }
        host.stopListening()
        super.onDestroy()
    }
}
