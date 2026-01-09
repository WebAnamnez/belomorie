package com.belomorie.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Entity для хранения результатов анализа окружения
 * Соответствует структуре trackings_local из ТЗ v2.4
 * 
 * JSON структура в json_data:
 * {
 *   "place": {"label": "home", "confidence": 0.92},
 *   "sound_profile": {
 *     "music": {"duration_percent": 30, "confidence": 0.4},
 *     "speech": {"duration_percent": 40, "confidence": 0.7},
 *     ...
 *   },
 *   "transcription": {
 *     "text": "Обсуждаем проект с Ивановым",
 *     "language": "ru",
 *     "confidence": 0.85
 *   },
 *   "emotion": {...}
 * }
 * 
 * Примечание: Пока транскрипция хранится в plaintext в json_data.
 * Позже будет добавлено шифрование (AES-256-GCM) и отдельная таблица.
 */
@Entity(tableName = "trackings_local")
data class TrackingEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    
    val created_at: Long = System.currentTimeMillis(),
    
    val status: String = "pending", // "pending" или "sent"
    
    val sent_at: Long? = null, // Когда успешно отправили на сервер
    
    val retry_count: Int = 0, // Сколько раз пытались отправить
    
    val json_data: String, // Метаданные в JSON (place, sound_profile, emotion, etc.)
    
    val encrypted_transcription: String? = null // Зашифрованный текст (пока null, будет на Этапе 2)
)


