package com.vermasrijan.pixelnpu.agent

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Read-only tools that let the agent answer questions about the phone it is running on. */
class DeviceTools(context: Context) : ToolSet {
    private val appContext = context.applicationContext

    @Tool
    @LLMDescription("Returns the phone model, its chipset (SoC) and the Android version.")
    fun getDeviceInfo(): String =
        "Device: ${Build.MANUFACTURER} ${Build.MODEL}. " +
            "SoC: ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}. " +
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})."

    @Tool
    @LLMDescription("Returns the battery charge in percent, whether it is charging, and the battery temperature.")
    fun getBatteryStatus(): String {
        val battery = appContext.getSystemService(BatteryManager::class.java)
        val level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = if (battery.isCharging) "charging" else "not charging"
        val tenthsOfDegree = appContext
            .registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }
        val temperature = tenthsOfDegree?.let { "${it / 10.0} °C" } ?: "unknown"
        return "Battery: $level%, $charging, temperature $temperature."
    }

    @Tool
    @LLMDescription(
        "Returns the phone's thermal throttling status and thermal headroom. " +
            "Headroom 1.0 or more means the phone is about to throttle performance."
    )
    fun getThermalStatus(): String {
        val power = appContext.getSystemService(PowerManager::class.java)
        val status = when (power.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "none"
            PowerManager.THERMAL_STATUS_LIGHT -> "light"
            PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
            else -> "unknown"
        }
        val headroom = power.getThermalHeadroom(10).takeUnless { it.isNaN() }
        return "Thermal status: $status. Thermal headroom (10 s forecast): ${headroom ?: "unavailable"}."
    }

    @Tool
    @LLMDescription("Returns the phone's total and currently available RAM.")
    fun getMemoryInfo(): String {
        val memory = ActivityManager.MemoryInfo()
        appContext.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        val gib = 1024.0 * 1024 * 1024
        return "RAM: %.1f GiB available of %.1f GiB total%s.".format(
            memory.availMem / gib,
            memory.totalMem / gib,
            if (memory.lowMemory) " (low memory)" else "",
        )
    }

    @Tool
    @LLMDescription("Returns the current local date, time and time zone.")
    fun getCurrentDateTime(): String =
        ZonedDateTime.now().format(DateTimeFormatter.RFC_1123_DATE_TIME)
}
