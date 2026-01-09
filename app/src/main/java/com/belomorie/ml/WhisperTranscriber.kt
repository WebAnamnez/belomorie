package com.belomorie.ml

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Whisper Transcriber для распознавания русской речи
 * Использует whisper.cpp (GGUF формат: ggml-small-q5_1.bin) для транскрипции аудио
 * 
 * Модель: ggml-small-q5_1.bin (181 MB, Q5_1 квантование)
 * Формат: GGUF (ранее GGML)
 * Языки: Multilingual (включая русский)
 */
class WhisperTranscriber(private val context: Context) {
    
    companion object {
        private const val TAG = "WhisperTranscriber"
        private const val SAMPLE_RATE = 16000 // Whisper требует 16kHz
        private const val MODEL_ASSET_PATH = "models/ggml-small-q5_1.bin"
        
        // Загружаем нативную библиотеку whisper.cpp
        init {
            try {
                System.loadLibrary("whisper_jni")
                Log.d(TAG, "✅ Native library 'whisper_jni' loaded")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "❌ Failed to load native library 'whisper_jni': ${e.message}")
                Log.e(TAG, "⚠️ Make sure libwhisper_jni.so is compiled and included in the APK")
            }
        }
        
        // Нативные методы для работы с whisper.cpp
        @JvmStatic
        private external fun nativeInit(modelPath: String): Long
        
        @JvmStatic
        private external fun nativeTranscribe(
            ctxPtr: Long,
            audioData: ShortArray,
            sampleRate: Int,
            language: String
        ): String
        
        @JvmStatic
        private external fun nativeFree(ctxPtr: Long)
        
        @JvmStatic
        private external fun nativeIsInitialized(ctxPtr: Long): Boolean
    }
    
    private var whisperContext: Long = 0
    private var modelFile: File? = null
    private var isInitialized = false
    
    /**
     * Инициализация модели Whisper
     * Копирует модель из assets во внутреннее хранилище и инициализирует whisper.cpp
     */
    fun initialize(): Boolean {
        return try {
            if (isInitialized) {
                Log.d(TAG, "Whisper already initialized")
                return true
            }
            
            // Копируем модель из assets во внутреннее хранилище
            modelFile = copyModelFromAssets()
            if (modelFile == null || !modelFile!!.exists()) {
                Log.e(TAG, "❌ Failed to copy model from assets")
                return false
            }
            
            Log.d(TAG, "📦 Model file: ${modelFile!!.absolutePath} (${formatBytes(modelFile!!.length())})")
            
            // Инициализируем whisper.cpp через JNI
            whisperContext = nativeInit(modelFile!!.absolutePath)
            if (whisperContext == 0L) {
                Log.e(TAG, "❌ Failed to initialize whisper.cpp context")
                return false
            }
            
            if (!nativeIsInitialized(whisperContext)) {
                Log.e(TAG, "❌ Whisper context not properly initialized")
                return false
            }
            
            isInitialized = true
            Log.d(TAG, "✅ Whisper initialized successfully")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "❌ Native library not available: ${e.message}")
            Log.e(TAG, "⚠️ Make sure libwhisper_jni.so is compiled and included")
            false
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize Whisper: ${e.message}", e)
            false
        }
    }
    
    /**
     * Копирует модель из assets во внутреннее хранилище приложения
     */
    private fun copyModelFromAssets(): File? {
        return try {
            val modelFile = File(context.filesDir, "ggml-small-q5_1.bin")
            
            // Если модель уже скопирована, возвращаем существующий файл
            if (modelFile.exists() && modelFile.length() > 0) {
                Log.d(TAG, "📦 Model already exists in cache: ${formatBytes(modelFile.length())}")
                return modelFile
            }
            
            // Копируем модель из assets
            Log.d(TAG, "📥 Copying model from assets...")
            context.assets.open(MODEL_ASSET_PATH).use { input ->
                FileOutputStream(modelFile).use { output ->
                    input.copyTo(output)
                }
            }
            
            Log.d(TAG, "✅ Model copied successfully: ${formatBytes(modelFile.length())}")
            modelFile
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error copying model from assets: ${e.message}", e)
            null
        }
    }
    
    /**
     * Транскрипция аудио файла в текст
     * @param audioFile PCM файл (16kHz MONO, 16-bit)
     * @return Транскрибированный текст, или null при ошибке
     */
    fun transcribe(audioFile: File): TranscriptionResult? {
        if (!isInitialized || whisperContext == 0L) {
            Log.w(TAG, "⚠️ Whisper not initialized")
            return null
        }
        
        return try {
            // Проверяем, что файл существует
            if (!audioFile.exists() || audioFile.length() == 0L) {
                Log.w(TAG, "⚠️ Empty or missing audio file")
                return null
            }
            
            // Читаем PCM данные (16-bit signed integers, little-endian)
            val audioBytes = audioFile.readBytes()
            val byteBuffer = ByteBuffer.wrap(audioBytes).order(ByteOrder.LITTLE_ENDIAN)
            val audioShorts = ShortArray(audioBytes.size / 2)
            byteBuffer.asShortBuffer().get(audioShorts)
            
            Log.d(TAG, "🎤 Transcribing ${audioShorts.size} samples (${String.format("%.2f", audioShorts.size / SAMPLE_RATE.toDouble())}s)...")
            
            // Вызываем нативный метод транскрипции
            val transcriptionText = nativeTranscribe(whisperContext, audioShorts, SAMPLE_RATE, "ru")
            
            if (transcriptionText.isBlank()) {
                Log.d(TAG, "🗣️ No speech detected")
                return TranscriptionResult(
                    text = "",
                    language = "ru",
                    confidence = 0.0f
                )
            }
            
            val result = TranscriptionResult(
                text = transcriptionText.trim(),
                language = "ru",
                confidence = 1.0f // whisper.cpp не возвращает confidence, используем 1.0
            )
            
            Log.d(TAG, "✅ Transcription: ${result.text.take(100)}${if (result.text.length > 100) "..." else ""}")
            result
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "❌ Native library not available: ${e.message}")
            null
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error transcribing audio: ${e.message}", e)
            null
        }
    }
    
    /**
     * Освобождение ресурсов
     */
    fun close() {
        if (whisperContext != 0L) {
            try {
                nativeFree(whisperContext)
                whisperContext = 0L
                Log.d(TAG, "🧹 Whisper context freed")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error freeing whisper context: ${e.message}", e)
            }
        }
        isInitialized = false
    }
    
    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
        }
    }
}

/**
 * Результат транскрипции
 */
data class TranscriptionResult(
    val text: String,           // Транскрибированный текст
    val language: String,       // Определённый язык ("ru")
    val confidence: Float       // Уверенность (0.0 - 1.0)
)

