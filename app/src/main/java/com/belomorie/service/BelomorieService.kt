package com.belomorie.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.MediaRecorder
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
import java.io.File

class BelomorieService : Service() {
    companion object {
        const val CHANNEL_ID = "BelomorieChannel"
        const val NOTIFICATION_ID = 1
        private const val RECORDING_DURATION_MS = 30_000L // 30 секунд
        private const val LOG_INTERVAL_MS = 60_000L // 1 минута
    }
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private var totalBytesRecorded = 0L
    private var recordingStartTime = 0L
    private var lastLogTime = 0L
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("Belomorie", "🚀 Service started!")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        startRecordingLoop()
        return START_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopRecording()
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
        val outputFile = File(getExternalFilesDir(null), "temp_recording_${System.currentTimeMillis()}.m4a")
        
        try {
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(outputFile.absolutePath)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(128000)
                
                prepare()
                start()
                isRecording = true
                
                Log.d("Belomorie", "🎤 Начало записи: ${outputFile.name}")
            }
            
            // Ждем 30 секунд
            delay(RECORDING_DURATION_MS)
            
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
            isRecording = false
            
            // Удаляем файл сразу после записи
            val fileSize = outputFile.length()
            totalBytesRecorded += fileSize
            
            if (outputFile.exists()) {
                outputFile.delete()
                Log.d("Belomorie", "🗑️ Файл удален: ${outputFile.name} (${formatBytes(fileSize)})")
            }
            
        } catch (e: Exception) {
            mediaRecorder?.release()
            mediaRecorder = null
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
                mediaRecorder?.apply {
                    stop()
                    release()
                }
            } catch (e: Exception) {
                Log.e("Belomorie", "Ошибка остановки записи: ${e.message}")
            }
            mediaRecorder = null
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
}

