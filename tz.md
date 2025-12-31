```markdown
# Техническое задание: Belomorie Life Tracking System (v2.4) **ОБНОВЛЕНО 31.12.2025**

**Изменения v2.4:**
- ✅ **РУССКИЙ ЯЗЫК** добавлен везде
- ✅ **Две метки:** Place (место) + Sound (звук) **раздельно**
- ✅ **Полный профиль звуков** (не top-1, а top-N с долями времени и вероятностями)
- ✅ **Логика:** Музыка НЕ перетягивает место автоматически

---

## 🎯 Обзор проекта [БЕЗ ИЗМЕНЕНИЙ]

**Belomorie** — приватный дневник жизни, который автоматически анализирует окружение и разговоры **НА РУССКОМ ЯЗЫКЕ**.

---

## 📱 Платформа [ОБНОВЛЕНО]
```
**Язык: РУССКИЙ 🇷🇺** (основной и единственный требуемый)
- Whisper-large-v3-ru (транскрипция)
- Wav2Vec2-xlsr-53-russian-emotion (эмоции)
```

---

## 🔄 Цикл работы (каждые 30 секунд) [ОБНОВЛЕНО]

```
┌─────────────────────────────────────────────┐
│ ТЕЛЕФОН (всё происходит локально!)          │
├─────────────────────────────────────────────┤
│ 1. 🔴 Запись: микрофон слушает 30s         │
│    (PCM 16kHz, MONO = 960KB raw)            │
│                                              │
│ 2. 🎵 YAMNet(30s) → ПОЛНЫЙ ПРОФИЛЬ звуков: │
│    {                                         │
│      "music": {"duration": 30%, "conf": 0.4},│
│      "speech": {"duration": 40%, "conf": 0.7},│
│      "silence": {"duration": 20%, "conf": 0.9}│
│    }                                         │
│                                              │
│ 3. 🏢 AGGREGATOR → PLACE (место):           │
│    - Логика: сглаживание по истории + rules │
│    - Результат: "Home" (92%)                 │
│                                              │
│ 4. 🗣️ Whisper-large-v3-ru → ТЕКСТ:         │
│    - РУССКИЙ 🇷🇺                           │
│    - "Обсуждаем проект с Ивановым"          │
│                                              │
│ 5. 😊 Wav2Vec2-ru → ЭМОЦИИ:                 │
│    - РУССКИЙ 🇷🇺                           │
│    - "спокоен" (78%)                         │
│                                              │
│ 6. 🔒 Шифрование текста (AES-256-GCM)       │
│ 7. ❌ УДАЛИТЬ аудио                          │
│ 8. 📤 JSON на сервер                         │
└─────────────────────────────────────────────┘
```

---

## 📊 Метрики [ПОЛНОСТЬЮ ПЕРЕПИСАНО]

### **1. PLACE (МЕСТО где ты был)**
```
**Источник:** YAMNet профиль + Aggregator rules + история
**Значения:** home/office/street/restaurant/transport/unknown
**Логика:** 
- Сглаживание по 5-10 минутам истории
- Музыка НЕ перетягивает место автоматически
- Порог уверенности <60% → "unknown"
- Калибровка пользователя (опционально)

**Пример:** 
- YAMNet: music 30% + speech 40%
- История: 4 предыдущих фрагмента = "home"  
- Итог: PLACE=Home (не ресторан!)
```

### **2. SOUND PROFILE (ПОЛНЫЙ профиль звуков)**
```
**Источник:** YAMNet top-5 за 30 секунд (каждые 100мс)
**Формат:** JSON объект с долей времени и вероятностью
**Пример:**
```json
{
  "music": {"duration_percent": 30, "confidence": 0.4},
  "speech": {"duration_percent": 40, "confidence": 0.7},
  "silence": {"duration_percent": 20, "confidence": 0.9},
  "keyboard": {"duration_percent": 5, "confidence": 0.6},
  "crowd": {"duration_percent": 5, "confidence": 0.3}
}
```
**Хранение:** JSONB в PostgreSQL (индексируется)
```

### **3. ТРАНСКРИПЦИЯ (что ты говорил)**
```
**Источник:** Whisper-large-v3-ru 🇷🇺
**Точность:** WER 6.39% (94% слов верно)
**Хранение:** ЗАШИФРОВАНО AES-256-GCM
```

### **4. ЭМОЦИИ (как ты себя чувствовал)**
```
**Источник:** Wav2Vec2-xlsr-53-russian-emotion 🇷🇺
**Значения:** neutral/happy/angry/stressed/sad
**Точность:** 72%
**Хранение:** plaintext
```

---

## 🌐 API Спецификация [ОБНОВЛЕНО]

```json
POST /api/v1/trackings/batch
{
  "device_id": "uuid",
  "chunks": [{
    "timestamp": "2025-12-31T08:30:00Z",
    "duration_ms": 30000,
    
    // ✅ НОВОЕ: ДВЕ МЕТКИ
    "place": {
      "label": "Home",
      "confidence": 0.92
    },
    
    // ✅ НОВОЕ: ПОЛНЫЙ ПРОФИЛЬ ЗВУКОВ
    "sound_profile": {
      "music": {"duration_percent": 30, "confidence": 0.4},
      "speech": {"duration_percent": 40, "confidence": 0.7},
      "silence": {"duration_percent": 20, "confidence": 0.9}
    },
    
    "emotion": {"label": "neutral", "confidence": 0.78},
    "transcription_encrypted": "AES-256-GCM:base64...",
    "transcription_hash": "sha256:abc123..."
  }]
}
```

---

## 💾 База данных [ОБНОВЛЕНО]

```sql
-- Метаданные (plaintext)
CREATE TABLE trackings_metadata (
  id UUID PRIMARY KEY,
  timestamp TIMESTAMPTZ,
  user_id UUID,
  
  -- ✅ НОВОЕ
  place_label TEXT,           -- "Home", "Office"
  place_confidence FLOAT,     -- 0.92
  
  sound_profile JSONB,        -- {"music": {...}, "speech": {...}}
  
  emotion_label TEXT,         -- "neutral"
  emotion_confidence FLOAT,   -- 0.78
  speech_ratio FLOAT          -- % времени говорил
);

-- Шифрованный текст
CREATE TABLE trackings_encrypted (
  metadata_id UUID REFERENCES trackings_metadata,
  transcription_encrypted BYTEA,
  transcription_hash TEXT
);
```

---

## 📊 Dashboard [ОБНОВЛЕНО]

```
📊 Timeline:
19:00–19:30 → 🏠 Home | 🎵 Music(30%)+🗣️Speech(40%)
19:30–20:00 → 🏠 Home | 🗣️Speech(60%)+🤫Silence(30%)

😊 Emotion heatmap: neutral → stressed → happy
🔊 Sound pie: Music 25% | Speech 45% | Silence 20%

📈 Sound trends:
- В какое время дня чаще музыка?
- Когда больше разговоров?
```

---

## 🧮 Объём данных [ОБНОВЛЕНО]

| Метрика | v2.3 (top-1) | v2.4 (профиль) | Изменение |
|---------|--------------|----------------|-----------|
| **JSON размер** | 80 байт | 280 байт | +3.5x |
| **За день** | 230 KB | 800 KB | ✅ OK |
| **За месяц** | 7 MB | 24 MB | ✅ OK |
| **Батарея** | <5%/час | <5%/час | ✅ Без изменений |

---

## 🤖 ML Pipeline [ОБНОВЛЕНО]

### YAMNet → ДВЕ ВЫВОДНЫЕ МЕТКИ

```kotlin
// 1. YAMNet профиль (900 фреймов × 521 класс)
val frame_scores = yamNet.predict(30s_audio)  // 

// 2. SOUND PROFILE (top-5 по времени)
val sound_profile = aggregateTop5(frame_scores)
→ {"music": 30%, "speech": 40%...}

// 3. PLACE AGGREGATOR (сглаживание + правила)
val place_label = placeAggregator(sound_profile, history)
→ "Home" (92%)
```

---

## ✅ Принципиальные договоренности [ОБНОВЛЕНО]

| Пункт | Решение v2.4 |
|-------|--------------|
| **Язык** | ТОЛЬКО РУССКИЙ 🇷🇺 |
| **Метрики** | **Place + Sound Profile** (две метки раздельно) |
| **Sound** | **Полный профиль** top-5 звуков (доля времени + confidence) |
| **Place** | Aggregator поверх профиля + история (музыка НЕ перетягивает) |
| **JSONB** | PostgreSQL JSONB для профилей (индексируется) |
| **Объём** | +3.5x (800KB/день = приемлемо) |

---

**ТЗ v2.4 ФИНАЛЬНОЕ**  
**✅ Все обсуждения YAMNet учтены**  
**✅ Две метки + полный профиль звуков**  
**✅ Русский язык везде**
```

**Скопируй в `belomorie-tz-v2.4.md`** 🚀

**Готово к разработке с новой логикой!**

[1](https://ppl-ai-file-upload.s3.amazonaws.com/web/direct-files/attachments/images/109380942/e745192a-7cbd-48b7-bb94-eb7f57698db9/image.jpg)