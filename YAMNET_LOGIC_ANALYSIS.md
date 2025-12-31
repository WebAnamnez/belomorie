# Анализ логики работы с YAMNet и звуком

## ✅ Что правильно в текущей логике

### 1. **Разделение Place и Sound Profile**
- ✅ **Правильно**: Две независимые метки дают больше гибкости
- ✅ **Плюс**: Можно анализировать звуки отдельно от места
- ✅ **Использование**: В будущем можно строить паттерны типа "дома слушаю музыку 30% времени"

### 2. **Полный профиль звуков (top-5)**
- ✅ **Правильно**: Не только доминирующий звук, а полная картина
- ✅ **Плюс**: Видно комбинации (музыка + речь + тишина одновременно)
- ✅ **Использование**: Анализ активности, настроения, контекста

### 3. **Музыка не перетягивает место**
- ✅ **Правильно**: Музыка может быть везде (дом, офис, транспорт)
- ✅ **Плюс**: Более точное определение места через другие звуки

---

## ⚠️ Проблемы и несоответствия

### 1. **Частота анализа: "каждые 100мс" vs реальность YAMNet**

**Проблема:**
- В ТЗ указано: "YAMNet top-5 за 30 секунд (каждые 100мс)"
- Реальность: YAMNet обрабатывает сегменты по **15600 сэмплов = ~1 секунда** при 16kHz
- 100мс = 1600 сэмплов — это слишком мало для YAMNet

**Решение:**
```
Вариант А (рекомендуется): Анализ каждые 1 секунду
- 30 секунд = 30 фреймов
- Каждый фрейм = 1 секунда аудио
- Агрегируем результаты по всем 30 фреймам

Вариант Б: Скользящее окно
- Окно 1 секунда, шаг 100мс
- Перекрытие 900мс
- Более плавные переходы, но больше вычислений
```

### 2. **Агрегация результатов: среднее vs доля времени**

**Текущая реализация (неправильно):**
```kotlin
// Среднее значение по всем сегментам
allScores[index] = (allScores[index] ?: 0f) + score
// ...
allScores[key] = value / segmentCount
```

**Проблема:** Это даёт среднюю вероятность, а не долю времени!

**Правильная логика:**
```kotlin
// Для каждого класса считаем:
// 1. В скольких фреймах он был в top-5
// 2. Средняя confidence в этих фреймах
// 3. duration_percent = (количество_фреймов / общее_количество) * 100
```

### 3. **Place Aggregator: нет реализации**

**Проблема:** В ТЗ описана логика, но нет реализации:
- Сглаживание по истории (5-10 минут)
- Правила для определения места
- Порог уверенности <60% → "unknown"

**Нужно создать:**
- `PlaceAggregator` класс
- Хранение истории последних 10-20 фрагментов
- Взвешенное голосование с учётом времени

### 4. **Маппинг классов YAMNet → Sound Profile**

**Проблема:** Текущий код маппит классы на Place, но не на Sound Profile

**Нужно:**
- Группировка классов YAMNet в категории звуков:
  - `music` → "Music", "Musical instrument", "Singing"
  - `speech` → "Speech", "Conversation", "Chatter"
  - `silence` → Низкий уровень всех звуков
  - `keyboard` → "Computer keyboard", "Typing"
  - `crowd` → "Crowd", "Crowd of people", "Hubbub"
  - и т.д.

---

## 🚀 Рекомендации по улучшению

### 1. **Улучшенная агрегация Sound Profile**

```kotlin
data class SoundProfileEntry(
    val duration_percent: Float,  // Доля времени (0-100)
    val confidence: Float          // Средняя уверенность (0-1)
)

data class SoundProfile(
    val sounds: Map<String, SoundProfileEntry>
    // Пример: "music" -> SoundProfileEntry(30.0f, 0.4f)
)

fun aggregateSoundProfile(frameScores: List<Map<Int, Float>>): SoundProfile {
    // 1. Группируем классы YAMNet в категории звуков
    // 2. Для каждой категории:
    //    - Считаем в скольких фреймах она была в top-5
    //    - Считаем среднюю confidence
    // 3. Сортируем по duration_percent, берём top-5
}
```

### 2. **Place Aggregator с историей**

```kotlin
class PlaceAggregator {
    private val history = mutableListOf<PlaceCandidate>() // Последние 10-20 фрагментов
    
    data class PlaceCandidate(
        val timestamp: Long,
        val soundProfile: SoundProfile,
        val rawPlace: String? // Предварительное место из звуков
    )
    
    fun determinePlace(
        currentSoundProfile: SoundProfile,
        timestamp: Long
    ): PlaceResult {
        // 1. Предварительное место из текущего звука
        val rawPlace = inferPlaceFromSounds(currentSoundProfile)
        
        // 2. Добавляем в историю
        history.add(PlaceCandidate(timestamp, currentSoundProfile, rawPlace))
        // Оставляем только последние 10-20 (5-10 минут)
        if (history.size > 20) history.removeAt(0)
        
        // 3. Взвешенное голосование с учётом времени
        val placeVotes = mutableMapOf<String, Float>()
        history.forEachIndexed { index, candidate ->
            val weight = 1.0f / (history.size - index) // Более свежие = больше вес
            placeVotes[rawPlace] = (placeVotes[rawPlace] ?: 0f) + weight
        }
        
        // 4. Выбираем место с максимальным весом
        val bestPlace = placeVotes.maxByOrNull { it.value }
        
        // 5. Проверяем порог уверенности
        val confidence = bestPlace?.value ?: 0f
        val normalizedConfidence = confidence / history.sumOf { 1.0f / (history.size - it) }
        
        return PlaceResult(
            label = if (normalizedConfidence >= 0.6f) bestPlace?.key ?: "unknown" else "unknown",
            confidence = normalizedConfidence
        )
    }
}
```

### 3. **Определение тишины (silence)**

**Проблема:** YAMNet не имеет класса "Silence"

**Решение:**
```kotlin
fun detectSilence(frameScores: Map<Int, Float>): Boolean {
    // Тишина = все top-5 классы имеют низкую уверенность
    val top5MaxScore = frameScores.values.sortedDescending().take(5).maxOrNull() ?: 0f
    return top5MaxScore < 0.3f // Порог для тишины
}
```

### 4. **Оптимизация производительности**

**Текущая проблема:** Обработка 30 секунд = 30 вызовов YAMNet

**Улучшение:**
```kotlin
// Батчинг: обрабатываем несколько фреймов за раз
// YAMNet может обрабатывать батчи, если модель поддерживает

// Или: параллельная обработка фреймов
val frameResults = (0 until 30).map { frameIndex ->
    async {
        val frame = audio.slice(frameIndex * 15600 until (frameIndex + 1) * 15600)
        yamNet.predict(frame)
    }
}.awaitAll()
```

### 5. **Калибровка пользователя**

**Идея:** Пользователь может корректировать определения места

```kotlin
class UserCalibration {
    private val corrections = mutableMapOf<String, String>()
    // Пример: "music + speech" в 19:00 обычно = "home"
    
    fun applyCalibration(
        rawPlace: String,
        soundProfile: SoundProfile,
        timeOfDay: Int
    ): String {
        // Применяем правила пользователя
        // Если есть калибровка для этого паттерна → используем её
        return corrections[createPattern(soundProfile, timeOfDay)] ?: rawPlace
    }
}
```

---

## 📊 Использование в будущем

### 1. **Анализ паттернов**
```sql
-- Какие звуки чаще всего дома?
SELECT 
    sound_profile->>'music' as music_percent,
    COUNT(*) as frequency
FROM trackings_metadata
WHERE place_label = 'home'
GROUP BY music_percent
ORDER BY frequency DESC;
```

### 2. **Корреляция звуков и эмоций**
```sql
-- Когда музыка → какая эмоция?
SELECT 
    emotion_label,
    AVG((sound_profile->>'music')::jsonb->>'duration_percent')::float as avg_music
FROM trackings_metadata
WHERE sound_profile->>'music' IS NOT NULL
GROUP BY emotion_label;
```

### 3. **Временные тренды**
```sql
-- В какое время дня больше разговоров?
SELECT 
    EXTRACT(HOUR FROM timestamp) as hour,
    AVG((sound_profile->>'speech')::jsonb->>'duration_percent')::float as avg_speech
FROM trackings_metadata
GROUP BY hour
ORDER BY hour;
```

---

## ✅ Итоговые рекомендации

### Приоритет 1 (критично):
1. ✅ Исправить агрегацию Sound Profile (доля времени, а не среднее)
2. ✅ Реализовать Place Aggregator с историей
3. ✅ Группировка классов YAMNet в категории звуков

### Приоритет 2 (важно):
4. ✅ Определение тишины через порог уверенности
5. ✅ Оптимизация производительности (батчинг/параллелизм)

### Приоритет 3 (желательно):
6. ✅ Калибровка пользователя
7. ✅ Скользящее окно для более плавных переходов

---

## 🔧 Конкретные изменения в коде

### 1. Изменить `YAMNetAnalyzer.analyzeEnvironment()`:
- Вместо `EnvironmentResult` возвращать `SoundProfileResult`
- Агрегировать по фреймам правильно (доля времени)

### 2. Создать `PlaceAggregator`:
- Новый класс для определения места
- Хранение истории в памяти или БД

### 3. Обновить `BelomorieService`:
- Использовать оба результата: Sound Profile + Place
- Сохранять в БД оба поля

### 4. Обновить схему БД:
- Добавить `sound_profile JSONB`
- Добавить `place_label TEXT`, `place_confidence FLOAT`

---

**Вывод:** Логика в ТЗ правильная, но требует доработки реализации. Основные проблемы: неправильная агрегация и отсутствие Place Aggregator.

