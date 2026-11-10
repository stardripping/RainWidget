package com.example.rainwidget

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.Executors

class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private lateinit var locationText: TextView
    private lateinit var thresholdLabel: TextView
    private lateinit var preview: TextView
    private lateinit var cityInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = Prefs(this)

        // 作为小组件配置页打开时，直接返回 OK，这样不管用户做没做设置，小组件都能放上桌面
        val widgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        }

        locationText = findViewById(R.id.locationText)
        thresholdLabel = findViewById(R.id.thresholdLabel)
        preview = findViewById(R.id.preview)
        cityInput = findViewById(R.id.cityInput)

        findViewById<Button>(R.id.btnLocate).setOnClickListener { locate() }
        findViewById<Button>(R.id.btnSearch).setOnClickListener { search() }
        cityInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { search(); true } else false
        }
        findViewById<Button>(R.id.btnRefresh).setOnClickListener { refresh() }

        // 阈值：10% ~ 90%，步长 10
        val bar = findViewById<SeekBar>(R.id.thresholdBar)
        bar.progress = (prefs.threshold - 10) / 10
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, progress: Int, fromUser: Boolean) {
                prefs.threshold = 10 + progress * 10
                updateLabels()
                showPreviewFromCache()
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) { RainWidget.updateAll(this@SettingsActivity) }
        })

        val hide = findViewById<CheckBox>(R.id.hideWhenDry)
        hide.isChecked = prefs.hideWhenDry
        hide.setOnCheckedChangeListener { _, checked ->
            prefs.hideWhenDry = checked
            RainWidget.updateAll(this)
        }

        updateLabels()
        showPreviewFromCache()
        if (prefs.hasLocation && System.currentTimeMillis() - prefs.lastFetch > 10 * 60_000L) refresh()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    private fun updateLabels() {
        locationText.text = if (prefs.hasLocation) {
            "📍 %s\n(%.3f, %.3f)".format(prefs.placeName, prefs.lat, prefs.lon)
        } else {
            "📍 尚未设置位置"
        }
        thresholdLabel.text = "降水概率 ≥ ${prefs.threshold}% 视为有雨"
    }

    private fun showPreviewFromCache() {
        val json = prefs.lastJson ?: return
        runCatching { Weather.parse(json, prefs.threshold) }.onSuccess { o ->
            val (t, d) = RainWidget.describe(o)
            val hours = o.hours.mapIndexed { i, h ->
                "%02d:00  %3d%%  %.1fmm%s".format(h.time.hour, h.prob, h.mm, if (o.rainy[i]) "  ☔" else "")
            }.joinToString("\n")
            preview.text = "$t\n$d\n\n$hours"
        }
    }

    private fun refresh() {
        if (!prefs.hasLocation) {
            toast("请先设置位置")
            return
        }
        preview.text = "正在获取天气…"
        val lat = prefs.lat
        val lon = prefs.lon
        io.execute {
            try {
                val json = Weather.fetchForecastJson(lat, lon)
                Weather.parse(json, prefs.threshold)
                prefs.lastJson = json
                prefs.lastFetch = System.currentTimeMillis()
                prefs.lastError = null
                main.post {
                    if (isFinishing) return@post
                    showPreviewFromCache()
                    RainWidget.updateAll(this)
                }
            } catch (e: Exception) {
                prefs.lastError = e.message ?: e.javaClass.simpleName
                main.post {
                    if (isFinishing) return@post
                    preview.text = "获取失败：${prefs.lastError}"
                    RainWidget.updateAll(this)
                }
            }
        }
        RefreshWorker.schedulePeriodic(this)
    }

    private fun savePlace(p: Place) {
        prefs.setPlace(p)
        updateLabels()
        RainWidget.updateAll(this)
        refresh()
    }

    // ---------- 城市搜索 ----------

    private fun search() {
        val q = cityInput.text.toString().trim()
        if (q.isEmpty()) return
        toast("搜索中…")
        io.execute {
            val result = runCatching { Weather.searchCity(q) }
            main.post {
                if (isFinishing) return@post
                result.onFailure { toast("搜索失败：${it.message}") }
                result.onSuccess { list ->
                    if (list.isEmpty()) {
                        toast("没有找到「$q」")
                    } else {
                        AlertDialog.Builder(this)
                            .setTitle("选择城市")
                            .setItems(list.map { it.name }.toTypedArray()) { _, i -> savePlace(list[i]) }
                            .show()
                    }
                }
            }
        }
    }

    // ---------- 定位 ----------

    private fun locate() {
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOCATION)
            return
        }
        val lm = getSystemService(LocationManager::class.java)
        val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (provider == null) {
            toast("定位服务未开启，请打开系统定位，或直接搜索城市")
            return
        }
        val last = try {
            lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
        } catch (e: SecurityException) {
            null
        }

        toast("正在定位…")
        var delivered = false
        val deliver: (Location?) -> Unit = { loc ->
            if (!delivered) {
                delivered = true
                onLocation(loc ?: last)
            }
        }
        // 最多等 15 秒，超时就用最近一次已知位置
        main.postDelayed({ deliver(null) }, 15_000)

        try {
            if (Build.VERSION.SDK_INT >= 30) {
                lm.getCurrentLocation(provider, null, mainExecutor) { deliver(it) }
            } else {
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(provider, object : LocationListener {
                    override fun onLocationChanged(location: Location) = deliver(location)
                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(p: String?, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(p: String) {}
                    override fun onProviderDisabled(p: String) {}
                }, Looper.getMainLooper())
            }
        } catch (e: SecurityException) {
            deliver(null)
        }
    }

    private fun onLocation(loc: Location?) {
        if (isFinishing) return
        if (loc == null) {
            toast("定位失败，请直接搜索城市")
            return
        }
        val lat = loc.latitude
        val lon = loc.longitude
        io.execute {
            val name = try {
                @Suppress("DEPRECATION")
                Geocoder(this, Locale.getDefault()).getFromLocation(lat, lon, 1)?.firstOrNull()?.let {
                    it.subLocality ?: it.locality ?: it.subAdminArea ?: it.adminArea
                }
            } catch (e: Exception) {
                null
            } ?: "当前位置"
            main.post { if (!isFinishing) savePlace(Place(name, lat, lon)) }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) locate()
            else toast("没有定位权限，可以直接搜索城市")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_LOCATION = 1
    }
}
