# 🛠️ Инструкция для Android Studio после добавления whisper.cpp submodule

## ✅ Шаг 1: Синхронизация проекта

1. **Откройте Android Studio**
2. **Синхронизируйте проект с Gradle:**
   - Нажмите на уведомление **"Sync Now"** вверху экрана (если появилось)
   - Или: **File → Sync Project with Gradle Files**
   - Или нажмите иконку 🐘 (Gradle Sync) в панели инструментов

**Что должно произойти:**
- Gradle загрузит зависимости
- CMake начнёт конфигурировать нативные библиотеки
- Могут появиться предупреждения (это нормально)

---

## ✅ Шаг 2: Проверка установки NDK

1. **Проверьте, установлен ли NDK:**
   - **File → Settings** (или `Ctrl+Alt+S`)
   - **Appearance & Behavior → System Settings → Android SDK**
   - Откройте вкладку **"SDK Tools"**
   - Найдите **"NDK (Side by side)"**
   - Убедитесь, что стоит галочка и установлена версия **27.0.12077987** (или новее)

2. **Если NDK не установлен:**
   - Поставьте галочку **"NDK (Side by side)"**
   - Нажмите **"Apply"** → **"OK"**
   - Дождитесь установки

---

## ✅ Шаг 3: Проверка CMake

1. **Проверьте, что CMake установлен:**
   - В том же окне **SDK Tools**
   - Найдите **"CMake"**
   - Убедитесь, что установлена версия **3.22.1** или новее

2. **Если CMake не установлен:**
   - Поставьте галочку **"CMake"**
   - Нажмите **"Apply"** → **"OK"**

---

## ✅ Шаг 4: Первая сборка (Build)

1. **Попробуйте собрать проект:**
   - **Build → Make Project** (или `Ctrl+F9`)
   - Или **Build → Rebuild Project**

2. **Что смотреть в Build Output:**
   - Если всё хорошо → сборка завершится успешно
   - Если ошибки → смотрите раздел "Возможные ошибки" ниже

---

## ⚠️ Возможные ошибки и решения

### Ошибка 1: "whisper" library not found

**Симптомы:**
```
CMake Error: Target 'whisper_jni' links to target 'whisper' but the target was not found.
```

**Решение:**
Имя библиотеки может отличаться. Нужно проверить, какое имя использует whisper.cpp. 

Попробуйте в `app/src/main/cpp/CMakeLists.txt` заменить:
```cmake
whisper  # Попробуйте другие варианты:
```
На один из вариантов:
- `whisper-static`
- `whisper-cpp`
- Или проверьте в `third_party/whisper.cpp/CMakeLists.txt`, какое имя там используется

---

### Ошибка 2: NDK version mismatch

**Симптомы:**
```
NDK version mismatch. Expected: 27.0.12077987, found: ...
```

**Решение:**
1. Установите нужную версию NDK через SDK Manager
2. Или измените `ndkVersion` в `app/build.gradle.kts` на установленную версию

---

### Ошибка 3: CMake version too old

**Симптомы:**
```
CMake 3.18.1 or higher is required.
```

**Решение:**
1. Установите CMake 3.22.1 или новее через SDK Manager
2. Или измените версию в `app/src/main/cpp/CMakeLists.txt`

---

### Ошибка 4: whisper.cpp не найден

**Симптомы:**
```
CMake Warning: whisper.cpp not found at ...
```

**Решение:**
Выполните в терминале (в корне проекта):
```bash
git submodule update --init --recursive
```

---

### Ошибка 5: UnsatisfiedLinkError при запуске

**Симптомы:**
При запуске приложения:
```
java.lang.UnsatisfiedLinkError: dlopen failed: library "libwhisper_jni.so" not found
```

**Решение:**
Это нормально на данном этапе! Библиотека пока не скомпилирована, т.к. в `whisper_jni.cpp` ещё заглушки. 

После реализации методов в `whisper_jni.cpp` и успешной компиляции эта ошибка исчезнет.

---

## 📋 Чек-лист проверки

Отметьте выполненные пункты:

- [ ] Проект синхронизирован с Gradle (Sync Project)
- [ ] NDK установлен (версия 27.0.12077987 или новее)
- [ ] CMake установлен (версия 3.22.1 или новее)
- [ ] Submodule whisper.cpp присутствует в `third_party/whisper.cpp`
- [ ] Файл `.gitmodules` существует
- [ ] Проект собирается без ошибок (или с ожидаемыми предупреждениями)

---

## 🔍 Как проверить, что всё работает

### 1. Проверка структуры проекта

В **Project** панели (слева) должны быть видны:
```
app/
├── src/
│   └── main/
│       ├── cpp/
│       │   ├── CMakeLists.txt
│       │   └── whisper_jni.cpp
│       └── ...
third_party/
└── whisper.cpp/
    ├── CMakeLists.txt
    ├── whisper.h
    └── ...
```

### 2. Проверка логов сборки

В **Build** панели (внизу) ищите строки:
```
✅ whisper.cpp found at ...
```

### 3. Проверка нативных библиотек

После сборки проверьте:
```
app/build/intermediates/cmake/.../obj/
```
Там должны быть `.so` файлы для разных архитектур (arm64-v8a, armeabi-v7a, x86_64, x86).

---

## 📝 Следующие шаги после успешной сборки

1. **Реализовать методы в `whisper_jni.cpp`**
   - Раскомментировать код
   - Подключить заголовочные файлы whisper.cpp

2. **Протестировать компиляцию**
   - Убедиться, что библиотека `libwhisper_jni.so` создаётся

3. **Протестировать инициализацию**
   - Запустить приложение
   - Проверить логи при инициализации WhisperTranscriber

---

## 🆘 Если что-то не работает

1. **Очистите проект:**
   - **Build → Clean Project**
   - **Build → Rebuild Project**

2. **Инвалидируйте кэши:**
   - **File → Invalidate Caches / Restart...**
   - Выберите **"Invalidate and Restart"**

3. **Проверьте логи:**
   - **Build → Build Output** (внизу экрана)
   - Скопируйте полный текст ошибки

4. **Проверьте терминал:**
   ```bash
   cd C:\Users\Const\Desktop\Belomorie
   git submodule status
   ```
   Должна быть строка с `third_party/whisper.cpp`



