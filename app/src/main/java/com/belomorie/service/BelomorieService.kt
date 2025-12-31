package com.belomorie.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioRecord
import android.media.AudioFormat
import android.media.MediaRecorder
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.belomorie.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.belomorie.ml.YAMNetAnalyzer
import com.belomorie.ml.PlaceAggregator
import com.belomorie.database.BelomorieDatabase
import com.belomorie.database.TrackingEntity
import org.json.JSONObject
import java.io.File

class BelomorieService : Service() {
    companion object {
        const val CHANNEL_ID = "BelomorieChannel"
        const val NOTIFICATION_ID = 1
        private const val RECORDING_DURATION_MS = 30_000L // 30 секунд
        private const val LOG_INTERVAL_MS = 60_000L // 1 минута
        
        // Параметры для AudioRecord (YAMNet требует 16kHz MONO)
        const val SAMPLE_RATE = 16000 // 16kHz для YAMNet
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        val BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT) * 2
    }
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var totalBytesRecorded = 0L
    private var recordingStartTime = 0L
    private var lastLogTime = 0L
    private var yamNetAnalyzer: YAMNetAnalyzer? = null
    private var placeAggregator: PlaceAggregator? = null
    private var database: BelomorieDatabase? = null
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("Belomorie", "🚀 Service started!")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        
        // Инициализация YAMNet
        yamNetAnalyzer = YAMNetAnalyzer(this).apply {
            initialize()
        }
        
        // Инициализация Place Aggregator
        placeAggregator = PlaceAggregator()
        
        // Инициализация базы данных
        database = BelomorieDatabase.getDatabase(this)
        
        startRecordingLoop()
        return START_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopRecording()
        yamNetAnalyzer?.close()
        serviceScope.cancel()
        Log.d("Belomorie", "🛑 Service stopped")
    }
    
    private fun startRecordingLoop() {
        serviceScope.launch {
            recordingStartTime = System.currentTimeMillis()
            lastLogTime = recordingStartTime
            
            while (true) {
                try {
                    recordAudio()
                    delay(RECORDING_DURATION_MS)
                    checkAndLogProgress()
                } catch (e: Exception) {
                    Log.e("Belomorie", "❌ Ошибка записи: ${e.message}", e)
                    delay(1000) // Пауза перед повтором
                }
            }
        }
    }
    
    private suspend fun recordAudio() {
        val outputFile = File(getExternalFilesDir(null), "temp_recording_${System.currentTimeMillis()}.pcm")
        
        try {
            // Создаем AudioRecord для записи PCM 16kHz MONO
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                BUFFER_SIZE
            )
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("AudioRecord не инициализирован")
            }
            
            audioRecord?.startRecording()
            isRecording = true
            
            Log.d("Belomorie", "🎤 Начало записи PCM: ${outputFile.name}")
            
            // Записываем данные в файл
            val fileOutputStream = FileOutputStream(outputFile)
            val buffer = ByteArray(BUFFER_SIZE)
            val startTime = System.currentTimeMillis()
            var totalBytesRead = 0L
            
            // Записываем 30 секунд
            while (System.currentTimeMillis() - startTime < RECORDING_DURATION_MS && isRecording) {
                val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                when {
                    bytesRead > 0 -> {
                        fileOutputStream.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead
                    }
                    bytesRead == AudioRecord.ERROR_INVALID_OPERATION -> {
                        Log.e("Belomorie", "❌ AudioRecord ERROR_INVALID_OPERATION")
                        break
                    }
                    bytesRead == AudioRecord.ERROR_BAD_VALUE -> {
                        Log.e("Belomorie", "❌ AudioRecord ERROR_BAD_VALUE")
                        break
                    }
                    bytesRead < 0 -> {
                        Log.w("Belomorie", "⚠️ AudioRecord read error: $bytesRead")
                        // Продолжаем, но с небольшой задержкой
                        delay(50)
                    }
                }
                // Небольшая задержка для снижения нагрузки
                delay(10)
            }
            
            fileOutputStream.close()
            
            audioRecord?.apply {
                stop()
                release()
            }
            audioRecord = null
            isRecording = false
            
            // Анализ звуков и места перед удалением
            val fileSize = outputFile.length()
            totalBytesRecorded += fileSize
            
            // 1. Анализ Sound Profile через YAMNet
            val soundProfileResult = yamNetAnalyzer?.analyzeSoundProfile(outputFile)
            
            // 2. Определение Place через Place Aggregator
            val placeResult = if (soundProfileResult != null) {
                placeAggregator?.determinePlace(soundProfileResult)
            } else {
                null
            }
            
            if (soundProfileResult != null && placeResult != null) {
                Log.d("Belomorie", "🎵 Sound Profile: ${soundProfileResult.sounds.keys.joinToString()}")
                Log.d("Belomorie", "🏢 Place: ${placeResult.label} (${String.format("%.0f", placeResult.confidence * 100)}%)")
                
                // Сохраняем результат в базу данных
                saveTrackingToDatabase(soundProfileResult, placeResult)
            }
            
            // Удаляем файл сразу после анализа
            if (outputFile.exists()) {
                outputFile.delete()
                Log.d("Belomorie", "🗑️ Файл удален: ${outputFile.name} (${formatBytes(fileSize)})")
            }
            
        } catch (e: Exception) {
            audioRecord?.apply {
                if (state == AudioRecord.STATE_INITIALIZED) {
                    stop()
                }
                release()
            }
            audioRecord = null
            isRecording = false
            if (outputFile.exists()) {
                outputFile.delete()
            }
            throw e
        }
    }
    
    private fun stopRecording() {
        if (isRecording) {
            try {
                audioRecord?.apply {
                    if (state == AudioRecord.STATE_INITIALIZED) {
                        stop()
                    }
                    release()
                }
            } catch (e: Exception) {
                Log.e("Belomorie", "Ошибка остановки записи: ${e.message}")
            }
            audioRecord = null
            isRecording = false
        }
    }
    
    private fun checkAndLogProgress() {
        val currentTime = System.currentTimeMillis()
        val timeSinceLastLog = currentTime - lastLogTime
        
        if (timeSinceLastLog >= LOG_INTERVAL_MS) {
            val totalMB = totalBytesRecorded / (1024.0 * 1024.0)
            val minutesRunning = (currentTime - recordingStartTime) / 60_000.0
            
            Log.d("Belomorie", "📊 Записано ${String.format("%.2f", totalMB)}MB за ${String.format("%.1f", minutesRunning)} минут")
            
            // Обновляем уведомление
            updateNotification("Записано ${String.format("%.2f", totalMB)}MB")
            
            lastLogTime = currentTime
        }
    }
    
    private fun updateNotification(text: String) {
        val notification = createNotification(text)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Belomorie Service", 
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(customText: String = "Анализ окружения"): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Belomorie")
            .setContentText(customText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
    
    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
        }
    }
    
    /**
     * Сохранение результата анализа в базу данных
     */
    private fun saveTrackingToDatabase(
        soundProfile: com.belomorie.ml.SoundProfileResult,
        place: com.belomorie.ml.PlaceResult
    ) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                // Формируем Sound Profile в формате JSON
                val soundProfileJson = JSONObject()
                soundProfile.sounds.forEach { (category, entry) ->
                    val entryJson = JSONObject().apply {
                        put("duration_percent", entry.durationPercent)
                        put("confidence", entry.confidence)
                    }
                    soundProfileJson.put(category, entryJson)
                }
                
                // Формируем Place в формате JSON
                val placeJson = JSONObject().apply {
                    put("label", place.label)
                    put("confidence", place.confidence)
                }
                
                val jsonData = JSONObject().apply {
                    // ✅ НОВОЕ: Две метки раздельно
                    put("place", placeJson)
                    put("sound_profile", soundProfileJson)
                    
                    // Пока нет эмоций и транскрипции (будет на следующих этапах)
                    put("emotion", JSONObject.NULL)
                    put("emotion_confidence", JSONObject.NULL)
                    put("transcription", JSONObject.NULL)
                }
                
                val tracking = TrackingEntity(
                    json_data = jsonData.toString(),
                    status = "pending"
                )
                
                database?.trackingDao()?.insertTracking(tracking)
                Log.d("Belomorie", "💾 Результат сохранен в БД: ${tracking.id}")
            } catch (e: Exception) {
                Log.e("Belomorie", "❌ Ошибка сохранения в БД: ${e.message}", e)
            }
        }
    }
}

