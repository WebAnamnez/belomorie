package com.belomorie.ml

import android.util.Log

/**
 * Place Aggregator - определяет место на основе Sound Profile и истории
 * Использует сглаживание по истории (5-10 минут) и правила
 */
class PlaceAggregator {
    companion object {
        private const val TAG = "PlaceAggregator"
        private const val HISTORY_SIZE = 20 // ~10 минут (20 фрагментов × 30 сек)
        private const val CONFIDENCE_THRESHOLD = 0.6f // Порог уверенности для "unknown"
    }
    
    private val history = mutableListOf<PlaceCandidate>()
    
    /**
     * Кандидат на место из одного фрагмента
     */
    private data class PlaceCandidate(
        val timestamp: Long,
        val soundProfile: SoundProfileResult,
        val rawPlace: String? // Предварительное место из звуков
    )
    
    /**
     * Определение места на основе текущего Sound Profile и истории
     */
    fun determinePlace(
        soundProfile: SoundProfileResult,
        timestamp: Long = System.currentTimeMillis()
    ): PlaceResult {
        // 1. Предварительное место из текущего звука
        val rawPlace = inferPlaceFromSounds(soundProfile)
        
        // 2. Добавляем в историю
        history.add(PlaceCandidate(timestamp, soundProfile, rawPlace))
        
        // 3. Оставляем только последние N фрагментов (5-10 минут)
        if (history.size > HISTORY_SIZE) {
            history.removeAt(0)
        }
        
        // 4. Взвешенное голосование с учётом времени (более свежие = больше вес)
        val placeVotes = mutableMapOf<String, Float>()
        history.forEachIndexed { index, candidate ->
            val weight = 1.0f / (history.size - index) // Более свежие = больше вес
            candidate.rawPlace?.let { place ->
                placeVotes[place] = (placeVotes[place] ?: 0f) + weight
            }
        }
        
        // 5. Выбираем место с максимальным весом
        val bestPlace = placeVotes.maxByOrNull { it.value }
        
        // 6. Нормализуем confidence (сумма весов = 1.0)
        val totalWeight = history.indices.sumOf { (1.0 / (history.size - it)).toDouble() }.toFloat()
        val normalizedConfidence = if (totalWeight > 0f) {
            (bestPlace?.value ?: 0f) / totalWeight
        } else {
            0f
        }
        
        // 7. Проверяем порог уверенности
        val finalPlace = if (normalizedConfidence >= CONFIDENCE_THRESHOLD) {
            bestPlace?.key ?: "unknown"
        } else {
            "unknown"
        }
        
        Log.d(TAG, "🏢 Place: $finalPlace (${String.format("%.0f", normalizedConfidence * 100)}%)")
        
        return PlaceResult(
            label = finalPlace,
            confidence = normalizedConfidence
        )
    }
    
    /**
     * Предварительное определение места из Sound Profile
     * Логика: музыка НЕ перетягивает место автоматически
     */
    private fun inferPlaceFromSounds(soundProfile: SoundProfileResult): String? {
        val sounds = soundProfile.sounds
        
        // Получаем доли времени для каждой категории
        val musicPercent = sounds["music"]?.durationPercent ?: 0f
        val speechPercent = sounds["speech"]?.durationPercent ?: 0f
        val keyboardPercent = sounds["keyboard"]?.durationPercent ?: 0f
        val crowdPercent = sounds["crowd"]?.durationPercent ?: 0f
        val silencePercent = sounds["silence"]?.durationPercent ?: 0f
        
        // Правила определения места (музыка НЕ перетягивает)
        return when {
            // Офис: клавиатура + речь, но не толпа
            keyboardPercent > 10f && speechPercent > 20f && crowdPercent < 15f -> "office"
            
            // Дом: речь + тишина, или клавиатура без толпы
            (speechPercent > 30f && silencePercent > 20f) || 
            (keyboardPercent > 5f && crowdPercent < 10f) -> "home"
            
            // Улица: транспортные звуки (если есть в профиле)
            // Пока нет категории "vehicle" в Sound Profile, пропускаем
            
            // Ресторан: толпа + музыка + речь
            crowdPercent > 20f && musicPercent > 15f && speechPercent > 20f -> "restaurant"
            
            // Транспорт: нужно добавить категорию "vehicle" в Sound Profile
            // Пока пропускаем
            
            // По умолчанию: если много речи и не много музыки/толпы
            speechPercent > 25f && musicPercent < 30f && crowdPercent < 15f -> "home"
            
            // Если много тишины
            silencePercent > 50f -> "home"
            
            else -> null // Неопределённое
        }
    }
    
    /**
     * Очистка истории (например, при смене пользователя)
     */
    fun clearHistory() {
        history.clear()
        Log.d(TAG, "🧹 History cleared")
    }
}

/**
 * Результат определения места
 */
data class PlaceResult(
    val label: String,      // "home", "office", "street", "restaurant", "transport", "unknown"
    val confidence: Float    // 0.0 - 1.0
)

