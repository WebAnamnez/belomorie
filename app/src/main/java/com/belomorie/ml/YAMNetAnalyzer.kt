package com.belomorie.ml

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * YAMNet Analyzer для анализа звуков и создания Sound Profile
 * Анализирует 30-секундные аудио фрагменты и создаёт:
 * - Sound Profile: полный профиль звуков (top-5 с долей времени и confidence)
 * - Не определяет Place напрямую (это делает PlaceAggregator)
 */
class YAMNetAnalyzer(private val context: Context) {
    
    companion object {
        private const val TAG = "YAMNetAnalyzer"
        private const val SAMPLE_RATE = 16000 // YAMNet требует 16kHz
        private const val WAVEFORM_LENGTH = 15600 // ~1 секунда при 16kHz
        private const val NUM_CLASSES = 521 // YAMNet имеет 521 класс
        private const val SILENCE_THRESHOLD = 0.3f // Порог для определения тишины
        
        // Группировка классов YAMNet в категории звуков для Sound Profile
        private val MUSIC_CLASSES = setOf(
            "Music", "Musical instrument", "Pop music", "Hip hop music", "Rock music",
            "Soul music", "Swing music", "Folk music", "Classical music", "Electronic music",
            "House music", "Electronic dance music", "Ambient music", "Trance music",
            "Salsa music", "New-age music", "Vocal music", "Gospel music", "Dance music",
            "Background music", "Theme music", "Jingle (music)", "Soundtrack music",
            "Video game music", "Christmas music", "Wedding music", "Happy music",
            "Sad music", "Tender music", "Exciting music", "Angry music", "Scary music",
            "Guitar", "Electric guitar", "Bass guitar", "Acoustic guitar", "Piano",
            "Electric piano", "Organ", "Synthesizer", "Drum kit", "Drum", "Orchestra",
            "Singing", "Choir", "Rapping", "Humming"
        )
        
        private val SPEECH_CLASSES = setOf(
            "Speech", "Child speech, kid speaking", "Conversation", "Narration, monologue",
            "Babbling", "Speech synthesizer", "Shout", "Bellow", "Whoop", "Yell",
            "Children shouting", "Screaming", "Whispering", "Chatter",
            "Hubbub, speech noise, speech babble"
        )
        
        private val KEYBOARD_CLASSES = setOf(
            "Computer keyboard", "Typing", "Typewriter"
        )
        
        private val CROWD_CLASSES = setOf(
            "Crowd", "Crowd of people", "Chatter", "Hubbub, speech noise, speech babble",
            "Cheering", "Applause", "Children playing"
        )
        
        // Класс Silence есть в YAMNet (индекс 494)
        private const val SILENCE_CLASS_INDEX = 494
    }
    
    private var interpreter: Interpreter? = null
    private var isInitialized = false
    private val classNames = mutableMapOf<Int, String>()
    
    /**
     * Инициализация модели YAMNet
     * Загружает TFLite модель из assets
     */
    fun initialize(): Boolean {
        return try {
            // Пробуем загрузить TFLite модель
            val modelBuffer = try {
                loadModelFile("yamnet.tflite")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ yamnet.tflite не найден в assets, используем заглушку")
                null
            }
            
            if (modelBuffer != null) {
                interpreter = Interpreter(modelBuffer)
                loadClassNames()
                isInitialized = true
                Log.d(TAG, "✅ YAMNet initialized (real model)")
            } else {
                // Заглушка для тестирования
                isInitialized = true
                Log.d(TAG, "✅ YAMNet initialized (stub mode)")
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize YAMNet: ${e.message}", e)
            false
        }
    }
    
    /**
     * Анализ аудио файла и создание Sound Profile
     * @param audioFile путь к PCM файлу (16kHz MONO, 30 секунд)
     * @return Sound Profile с top-5 звуками (доля времени + confidence)
     */
    fun analyzeSoundProfile(audioFile: File): SoundProfileResult? {
        if (!isInitialized) {
            Log.w(TAG, "YAMNet not initialized")
            return null
        }
        
        if (interpreter == null) {
            // Режим заглушки, если модель не загружена
            Log.d(TAG, "⚠️ Using stub mode (model not loaded)")
            return getStubSoundProfile()
        }
        
        return try {
            // Загружаем PCM данные из файла
            val pcmData = loadPCMData(audioFile)
            if (pcmData.isEmpty()) {
                Log.w(TAG, "⚠️ Empty PCM data, using stub")
                return getStubSoundProfile()
            }
            
            // Конвертируем int16 в float32 и нормализуем
            val floatData = convertToFloat32(pcmData)
            
            // YAMNet обрабатывает сегменты по 15600 сэмплов (~1 секунда)
            // Для 30 секунд = ~30 фреймов
            val frameResults = mutableListOf<FrameResult>()
            
            for (i in 0 until floatData.size step WAVEFORM_LENGTH) {
                val segmentEnd = minOf(i + WAVEFORM_LENGTH, floatData.size)
                if (segmentEnd - i < WAVEFORM_LENGTH) break // Пропускаем неполные сегменты
                
                val segment = floatData.sliceArray(i until segmentEnd)
                val scores = runInference(segment)
                
                // Получаем top-5 классов для этого фрейма
                val top5 = scores.mapIndexed { index, score -> 
                    ClassScore(getClassName(index), score) 
                }
                    .sortedByDescending { it.score }
                    .take(5)
                
                frameResults.add(FrameResult(top5))
            }
            
            if (frameResults.isEmpty()) {
                Log.w(TAG, "⚠️ No valid frames, using stub")
                return getStubSoundProfile()
            }
            
            // Агрегируем результаты: считаем долю времени для каждой категории
            val soundProfile = aggregateSoundProfile(frameResults)
            
            Log.d(TAG, "🎵 Sound Profile: ${soundProfile.sounds.keys.joinToString()}")
            soundProfile
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error analyzing audio: ${e.message}", e)
            null
        }
    }
    
    /**
     * Агрегация результатов фреймов в Sound Profile
     * Правильная логика: доля времени, а не среднее значение
     * ИСПРАВЛЕНО: каждый фрейм даёт максимум 1 голос для каждой категории
     */
    private fun aggregateSoundProfile(frameResults: List<FrameResult>): SoundProfileResult {
        val totalFrames = frameResults.size
        
        // Для каждой категории звуков считаем:
        // 1. В скольких фреймах она была в top-5 (каждый фрейм = максимум 1 раз)
        // 2. Среднюю confidence в этих фреймах (берём максимальный confidence из всех классов категории в фрейме)
        val categoryStats = mutableMapOf<String, CategoryStats>()
        
        frameResults.forEach { frame ->
            // Проверяем тишину (все top-5 имеют низкую уверенность)
            val maxScore = frame.topClasses.maxOfOrNull { it.score } ?: 0f
            val isSilence = maxScore < SILENCE_THRESHOLD
            
            if (isSilence) {
                val stats = categoryStats.getOrPut("silence") { CategoryStats() }
                stats.frameCount++
                stats.totalConfidence += maxScore.coerceAtMost(0.3f) // Ограничиваем для тишины
            } else {
                // Группируем классы по категориям для этого фрейма
                // Каждая категория может появиться только один раз на фрейм
                val categoriesInFrame = mutableMapOf<String, Float>() // category -> max confidence
                
                frame.topClasses.forEach { classScore ->
                    val category = mapClassToCategory(classScore.className)
                    if (category != null) {
                        // Берём максимальный confidence для категории в этом фрейме
                        val currentMax = categoriesInFrame[category] ?: 0f
                        categoriesInFrame[category] = maxOf(currentMax, classScore.score)
                    }
                }
                
                // Увеличиваем счетчик только один раз для каждой категории в этом фрейме
                categoriesInFrame.forEach { (category, maxConfidence) ->
                    val stats = categoryStats.getOrPut(category) { CategoryStats() }
                    stats.frameCount++
                    stats.totalConfidence += maxConfidence
                }
            }
        }
        
        // Создаём Sound Profile: top-5 по duration_percent
        val soundEntries = categoryStats.mapNotNull { (category, stats) ->
            if (stats.frameCount == 0) return@mapNotNull null // Пропускаем пустые категории
            val durationPercent = (stats.frameCount.toFloat() / totalFrames) * 100f
            val avgConfidence = stats.totalConfidence / stats.frameCount
            category to SoundProfileEntry(durationPercent, avgConfidence)
        }
            .sortedByDescending { it.second.durationPercent }
            .take(5)
            .toMap()
        
        return SoundProfileResult(soundEntries)
    }
    
    /**
     * Маппинг класса YAMNet в категорию звука
     */
    private fun mapClassToCategory(className: String): String? {
        val lowerName = className.lowercase()
        
        return when {
            MUSIC_CLASSES.any { it.lowercase() in lowerName } -> "music"
            SPEECH_CLASSES.any { it.lowercase() in lowerName } -> "speech"
            KEYBOARD_CLASSES.any { it.lowercase() in lowerName } -> "keyboard"
            CROWD_CLASSES.any { it.lowercase() in lowerName } -> "crowd"
            else -> null // Игнорируем остальные классы для упрощения
        }
    }
    
    /**
     * Внутренний класс для статистики категории
     */
    private data class CategoryStats(
        var frameCount: Int = 0,
        var totalConfidence: Float = 0f
    )
    
    /**
     * Результат одного фрейма (1 секунда)
     */
    private data class FrameResult(
        val topClasses: List<ClassScore>
    )
    
    /**
     * Загрузка PCM данных из файла (int16, 16kHz, MONO)
     */
    private fun loadPCMData(file: File): ShortArray {
        val fileInputStream = file.inputStream()
        val bytes = fileInputStream.readBytes()
        fileInputStream.close()
        
        // Конвертируем байты в short array (int16)
        val shorts = ShortArray(bytes.size / 2)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in shorts.indices) {
            shorts[i] = buffer.short
        }
        return shorts
    }
    
    /**
     * Конвертация int16 в float32 и нормализация в [-1.0, 1.0]
     */
    private fun convertToFloat32(pcmData: ShortArray): FloatArray {
        return FloatArray(pcmData.size) { i ->
            pcmData[i].toInt() / 32768.0f
        }
    }
    
    /**
     * Выполнение инференса YAMNet для одного сегмента
     */
    private fun runInference(waveform: FloatArray): FloatArray {
        val interpreter = interpreter ?: return FloatArray(NUM_CLASSES)
        
        // Подготовка входного буфера (15600 float значений)
        val inputBuffer = ByteBuffer.allocateDirect(waveform.size * 4)
            .order(ByteOrder.nativeOrder())
        waveform.forEach { inputBuffer.putFloat(it) }
        inputBuffer.rewind()
        
        // Подготовка выходного буфера (521 класс)
        val outputBuffer = ByteBuffer.allocateDirect(NUM_CLASSES * 4)
            .order(ByteOrder.nativeOrder())
        
        // Выполнение инференса
        interpreter.run(inputBuffer, outputBuffer)
        
        // Читаем результаты
        outputBuffer.rewind()
        val scores = FloatArray(NUM_CLASSES)
        for (i in scores.indices) {
            scores[i] = outputBuffer.float
        }
        
        return scores
    }
    
    /**
     * Загрузка имен классов из CSV файла
     */
    private fun loadClassNames() {
        try {
            val inputStream: InputStream = context.assets.open("yamnet_class_map.csv")
            inputStream.bufferedReader().useLines { lines ->
                lines.drop(1).forEach { line -> // Пропускаем заголовок
                    val parts = line.split(",")
                    if (parts.size >= 3) {
                        val index = parts[0].toIntOrNull()
                        if (index != null) {
                            // Убираем кавычки из имени
                            val name = parts[2].trim().removeSurrounding("\"")
                            classNames[index] = name
                        }
                    }
                }
            }
            Log.d(TAG, "✅ Loaded ${classNames.size} class names")
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Failed to load class names: ${e.message}")
        }
    }
    
    /**
     * Получение имени класса по индексу
     */
    private fun getClassName(index: Int): String {
        return classNames[index] ?: "Class_$index"
    }
    
    /**
     * Заглушка для тестирования (пока нет конвертации аудио)
     */
    private fun getStubSoundProfile(): SoundProfileResult {
        return SoundProfileResult(
            sounds = mapOf(
                "speech" to SoundProfileEntry(40f, 0.7f),
                "music" to SoundProfileEntry(30f, 0.4f),
                "silence" to SoundProfileEntry(20f, 0.9f),
                "keyboard" to SoundProfileEntry(5f, 0.6f),
                "crowd" to SoundProfileEntry(5f, 0.3f)
            )
        )
    }
    
    /**
     * Загрузка TFLite модели из assets
     */
    private fun loadModelFile(modelPath: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelPath)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }
    
    /**
     * Освобождение ресурсов
     */
    fun close() {
        interpreter?.close()
        interpreter = null
        isInitialized = false
    }
}

/**
 * Результат анализа звуков - Sound Profile
 */
data class SoundProfileResult(
    val sounds: Map<String, SoundProfileEntry> // Категория -> (доля времени, confidence)
)

/**
 * Запись в Sound Profile
 */
data class SoundProfileEntry(
    val durationPercent: Float,  // Доля времени (0-100)
    val confidence: Float         // Средняя уверенность (0-1)
)

/**
 * Класс YAMNet и его уверенность (для внутреннего использования)
 */
private data class ClassScore(
    val className: String,
    val score: Float
)

