cmake_minimum_required(VERSION 3.18.1)
project("whisper_jni")

# Установка стандарта C++17
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Добавляем путь для поиска JNI
find_library(log-lib log)
find_library(android-lib android)

# Подключение whisper.cpp через git submodule
# ШАГ 1: Добавить whisper.cpp как submodule:
#   git submodule add https://github.com/ggerganov/whisper.cpp.git third_party/whisper.cpp
# 
# ШАГ 2: Раскомментировать следующие строки после добавления submodule:

# Путь к whisper.cpp относительно этого CMakeLists.txt
# app/src/main/cpp/CMakeLists.txt → third_party/whisper.cpp = ../../../third_party/whisper.cpp
set(WHISPER_CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../third_party/whisper.cpp)

# Проверяем, что whisper.cpp существует
if(EXISTS ${WHISPER_CPP_DIR}/CMakeLists.txt)
    # Добавляем whisper.cpp как подпроект
    add_subdirectory(${WHISPER_CPP_DIR} whisper_cpp)
    # Добавляем путь к заголовочным файлам whisper.cpp
    include_directories(${WHISPER_CPP_DIR})
    message(STATUS "✅ whisper.cpp found at ${WHISPER_CPP_DIR}")
else()
    message(WARNING "⚠️ whisper.cpp not found at ${WHISPER_CPP_DIR}")
    message(WARNING "⚠️ Run: git submodule update --init --recursive")
endif()

# Создаём нативную библиотеку
add_library(whisper_jni SHARED
    whisper_jni.cpp
)

# Подключаем библиотеки
target_link_libraries(whisper_jni
    ${log-lib}
    ${android-lib}
    # whisper  # Библиотека whisper.cpp (имя цели из их CMakeLists.txt) - временно отключена, чтобы сборка не требовала libwhisper
)

# Указываем, что библиотека экспортирует символы
target_compile_definitions(whisper_jni PRIVATE JNIEXPORT=__attribute__\(visibility\(\"default\"\)\))

