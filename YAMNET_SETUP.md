# Настройка YAMNet TFLite модели

## Вариант 1: Скачать готовую модель (РЕКОМЕНДУЕТСЯ)

### Способ 1: TensorFlow Hub
1. Откройте: https://tfhub.dev/google/yamnet/1
2. Скачайте файл `yamnet.tflite` (если доступен)
3. Или используйте Python:
```python
import tensorflow_hub as hub
model = hub.load("https://tfhub.dev/google/yamnet/1")
# Экспорт в TFLite (если нужно)
```

### Способ 2: GitHub TensorFlow Models
1. Откройте: https://github.com/tensorflow/models/tree/master/research/audioset/yamnet
2. Найдите файл `yamnet.tflite` в репозитории
3. Скачайте его вручную

### Способ 3: Конвертация SavedModel (требует Python 3.10-3.11)

1. Установите Python 3.10 или 3.11
2. Установите TensorFlow:
```bash
pip install tensorflow>=2.14.0
```
3. Запустите конвертацию:
```bash
python convert_yamnet_to_tflite.py
```

## После получения yamnet.tflite:

1. Скопируйте файл в проект:
```bash
copy yamnet.tflite app\src\main\assets\yamnet.tflite
```

2. Модель будет автоматически загружена при запуске приложения

## Проверка размера модели:

YAMNet TFLite должен быть примерно **14-16 MB**

## Если модель не найдена:

Можно использовать альтернативные источники:
- TensorFlow Lite Model Zoo
- Hugging Face: https://huggingface.co/models?search=yamnet


