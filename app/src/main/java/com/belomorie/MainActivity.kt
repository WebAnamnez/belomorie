package com.belomorie

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.belomorie.service.BelomorieService
import com.belomorie.database.BelomorieDatabase
import com.belomorie.database.TrackingEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    
    private lateinit var serviceButton: Button
    private lateinit var statusText: TextView
    private lateinit var statusIndicator: View
    private lateinit var resultsText: TextView
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        serviceButton = findViewById(R.id.serviceButton)
        statusText = findViewById(R.id.statusText)
        statusIndicator = findViewById(R.id.statusIndicator)
        resultsText = findViewById(R.id.resultsText)
        
        serviceButton.setOnClickListener { 
            if (isServiceRunning()) {
                stopService()
            } else {
                startService()
            }
        }
        
        // Обновляем статус при создании активности
        updateServiceStatus()
        // Подписываемся на автоматическое обновление записей
        observeRecentTrackings()
    }
    
    override fun onResume() {
        super.onResume()
        // Обновляем статус при возврате на экран
        updateServiceStatus()
    }
    
    private fun startService() {
        if (checkPermissions()) {
            val intent = Intent(this, BelomorieService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            // Обновляем UI после небольшой задержки
            serviceButton.postDelayed({ updateServiceStatus() }, 500)
        } else {
            requestPermissions()
        }
    }
    
    private fun stopService() {
        val intent = Intent(this, BelomorieService::class.java)
        stopService(intent)
        updateServiceStatus()
    }
    
    private fun isServiceRunning(): Boolean {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val services = activityManager.getRunningServices(Integer.MAX_VALUE)
        
        for (service in services) {
            if (BelomorieService::class.java.name == service.service.className) {
                return true
            }
        }
        return false
    }

    /**
     * Подписка на автоматическое обновление последних записей из БД
     */
    private fun observeRecentTrackings() {
        lifecycleScope.launch {
            try {
                val db = BelomorieDatabase.getDatabase(applicationContext)
                db.trackingDao().getRecentTrackings(limit = 100) // Последние 100 записей
                    .flowOn(Dispatchers.IO)
                    .collect { trackings ->
                        val text = if (trackings.isEmpty()) {
                            "Пока нет записей в БД.\nЗапустите сервис и подождите 30 секунд."
                        } else {
                            formatTrackingsAsLog(trackings)
                        }
                        resultsText.text = text
                    }
            } catch (e: Exception) {
                resultsText.text = "Ошибка загрузки из БД: ${e.message}"
            }
        }
    }

    /**
     * Форматирование списка TrackingEntity в лог-подобный формат с временными метками и иконками
     */
    private fun formatTrackingsAsLog(trackings: List<TrackingEntity>): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
        val pid = android.os.Process.myPid()
        val tid = android.os.Process.myTid()
        
        return buildString {
            trackings.forEach { tracking ->
                val createdAt = Date(tracking.created_at)
                val timestamp = dateFormat.format(createdAt)
                
                val json = try {
                    JSONObject(tracking.json_data)
                } catch (e: Exception) {
                    null
                }
                
                // Place (место) - как в логах
                json?.optJSONObject("place")?.let {
                    val label = it.optString("label", "?")
                    val conf = it.optDouble("confidence", Double.NaN)
                    val confStr = if (conf.isNaN()) "?" else String.format(Locale.getDefault(), "%.0f", conf * 100)
                    appendLine("$timestamp $pid-$tid PlaceAggregator com.belomorie")
                    appendLine("D 🏠 Place: $label ($confStr%)")
                }
                
                // Sound Profile (звуковой профиль) - как в логах с деталями
                json?.optJSONObject("sound_profile")?.let { sp ->
                    val keys = sp.keys().asSequence().toList()
                    if (keys.isNotEmpty()) {
                        appendLine("$timestamp $pid-$tid YAMNetAnalyzer com.belomorie")
                        // Показываем список звуков с кратким форматом
                        val sounds = keys.joinToString(", ")
                        appendLine("D ♫ Sound Profile: $sounds")
                        // Детальная информация для каждого звука
                        keys.forEach { key ->
                            val soundObj = sp.optJSONObject(key)
                            val dur = soundObj?.optDouble("duration_percent", Double.NaN) ?: Double.NaN
                            val conf = soundObj?.optDouble("confidence", Double.NaN) ?: Double.NaN
                            if (!dur.isNaN() && !conf.isNaN()) {
                                val durStr = String.format(Locale.getDefault(), "%.0f%%", dur)
                                val confStr = String.format(Locale.getDefault(), "%.2f", conf)
                                appendLine("D   └─ $key: $durStr времени, качество=$confStr")
                            }
                        }
                    }
                }
                
                // Transcription (транскрипция) - как в логах
                val hasTranscription = json?.optJSONObject("transcription")?.let {
                    val text = it.optString("text", "")
                    if (text.isNotBlank()) {
                        appendLine("$timestamp $pid-$tid WhisperTranscriber com.belomorie")
                        val preview = text.take(80)
                        appendLine("D 🗣️ Transcription: $preview${if (text.length > 80) "..." else ""}")
                        true
                    } else {
                        false
                    }
                } ?: false
                
                // Если транскрипции нет - показываем предупреждение
                if (!hasTranscription) {
                    appendLine("$timestamp $pid-$tid WhisperTranscriber com.belomorie")
                    appendLine("W ⚠️ Whisper not initialized")
                }
                
                // Статус сохранения - как в логах
                appendLine("$timestamp $pid-$tid Belomorie com.belomorie")
                appendLine("D 💾 Результат сохранен в БД: ${tracking.id}")
                
                // Разделитель между записями
                if (tracking != trackings.last()) {
                    appendLine()
                }
            }
        }
    }
    
    private fun updateServiceStatus() {
        val isRunning = isServiceRunning()
        
        if (isRunning) {
            statusText.text = "Статус: Запущен"
            statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            statusIndicator.setBackgroundResource(android.R.drawable.presence_online)
            serviceButton.text = "⏹ ОСТАНОВИТЬ СЕРВИС"
        } else {
            statusText.text = "Статус: Остановлен"
            statusText.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
            statusIndicator.setBackgroundResource(android.R.drawable.presence_offline)
            serviceButton.text = "🚀 ЗАПУСТИТЬ СЕРВИС"
        }
    }
    
    private fun checkPermissions(): Boolean {
        val permission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        return permission == PackageManager.PERMISSION_GRANTED
    }
    
    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1001)
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.isNotEmpty() && 
            grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startService()
        }
    }
}

