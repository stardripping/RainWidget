package com.example.rainwidget

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("rain_widget", Context.MODE_PRIVATE)

    val hasLocation: Boolean get() = sp.contains("lat") && sp.contains("lon")
    val lat: Double get() = sp.getString("lat", "0")!!.toDouble()
    val lon: Double get() = sp.getString("lon", "0")!!.toDouble()
    val placeName: String get() = sp.getString("place", "") ?: ""

    fun setPlace(p: Place) {
        sp.edit()
            .putString("lat", p.lat.toString())
            .putString("lon", p.lon.toString())
            .putString("place", p.name)
            .remove("last_json")
            .remove("last_fetch")
            .remove("last_error")
            .apply()
    }

    /** 降水概率阈值（%），达到即视为有雨。 */
    var threshold: Int
        get() = sp.getInt("threshold", 50)
        set(v) = sp.edit().putInt("threshold", v).apply()

    /** 无雨时小组件是否保持空白。 */
    var hideWhenDry: Boolean
        get() = sp.getBoolean("hide_when_dry", false)
        set(v) = sp.edit().putBoolean("hide_when_dry", v).apply()

    var lastJson: String?
        get() = sp.getString("last_json", null)
        set(v) = sp.edit().putString("last_json", v).apply()

    var lastFetch: Long
        get() = sp.getLong("last_fetch", 0L)
        set(v) = sp.edit().putLong("last_fetch", v).apply()

    var lastError: String?
        get() = sp.getString("last_error", null)
        set(v) = sp.edit().putString("last_error", v).apply()
}
