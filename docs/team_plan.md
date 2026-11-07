# План работы команды

## Роли

| Кто | Зона | Что сдаёт |
| :--- | :--- | :--- |
| **Михаил** | Android-приложение, UI/UX | Каркас проекта, экраны, плеер, экспорт, Room, Foreground Service |
| **oatis123** | Пайплайн | Python-стенд на ноутбуке (выбор моделей, параметры, WER/DER), затем Kotlin-реализация `TranscriptionPipeline` на sherpa-onnx |

После первой интеграции работаем вместе: правим точность, скорость и UX.

---

## Контракт между приложением и пайплайном

Это единственная точка соприкосновения. Меняем её только по договорённости.

```kotlin
// package ...pipeline.api

data class Word(val text: String, val startMs: Long, val endMs: Long)

data class Utterance(
    val speakerId: String,      // "speaker_1", "speaker_2", ...
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val words: List<Word> = emptyList(),  // для караоке-подсветки, опционально
)

enum class Stage { DECODE, VAD, DIARIZATION, ASR, MERGE, LLM }

sealed interface PipelineEvent {
    data class Progress(val stage: Stage, val fraction: Float, val message: String? = null) : PipelineEvent
    data class Partial(val utterances: List<Utterance>) : PipelineEvent   // промежуточный результат, опционально
    data class Done(val utterances: List<Utterance>, val stats: Stats) : PipelineEvent
    data class Failed(val error: Throwable) : PipelineEvent
}

data class Stats(
    val audioMs: Long,
    val processingMs: Long,
    val peakRamMb: Long?,
    val stageTimesMs: Map<Stage, Long>,
) { val rtf get() = processingMs.toDouble() / audioMs }

enum class Profile { FAST, ACCURATE }

data class PipelineConfig(
    val profile: Profile = Profile.FAST,
    val language: String = "ru",        // "ru" | "en" | "auto"
    val numSpeakers: Int? = null,        // null = определить автоматически
    val useLlm: Boolean = true,
)

interface TranscriptionPipeline {
    /** pcm: моно 16 кГц float [-1, 1]. Декодирование файла делает приложение. */
    fun run(pcm: FloatArray, config: PipelineConfig): Flow<PipelineEvent>
}
```

- **Михаил** сразу пишет `FakePipeline`: задержки, прогресс по стадиям, захардкоженный диалог. На нём строится весь UI.
- **oatis123** реализует `SherpaPipeline : TranscriptionPipeline`. Подмена реализации делается одной строкой в DI.
- Декодер (MediaCodec → 16 кГц моно float) в зоне Михаила, потому что это Android-специфика.

### Формат экспорта (строго по ТЗ)

```json
{"transcript":[{"speaker_id":"speaker_1","start_ms":1250,"end_ms":4380,"text":"Добрый день!"}]}
```

Python-стенд должен писать **тот же формат**, чтобы одним скриптом оценивать и выход с ноутбука, и выход с телефона.

### Модели

- Лежат в `app/src/main/assets/models/<назначение>/...`. Сжатие отключаем через `androidResources.noCompress`.
- Файлы моделей не коммитим в git. Скачиваем их скриптом `scripts/fetch_models.sh`, который пишет oatis123.

---

## Задачи

### Михаил: приложение
1. Каркас: Compose + M3, навигация, без разрешения INTERNET, `FakePipeline`.
2. Импорт через SAF и «Открыть с помощью», декодер в 16 кГц моно.
3. Экран обработки: прогресс по стадиям, RTF, RAM. Foreground Service.
4. Экран транскрипта: карточки реплик и плеер Media3 (подсветка текущей реплики, тап перематывает).
5. Экспорт JSON (SAF + автосохранение в Downloads), опционально TXT/SRT.
6. Room: история сессий, ручные правки текста и спикера.
7. Запись с микрофона.

### oatis123: пайплайн
1. Python-стенд на sherpa-onnx: прогон папки с аудио → JSON → `eval.py` (WER через jiwer с нормализацией, DER через pyannote.metrics).
2. ASR: сравнить GigaAM (v2/v3), Parakeet-TDT-v3 и Whisper small на наших записях. Смотрим WER и RTF.
3. Диаризация: сегментация pyannote-3.0 + эмбеддинги 3D-Speaker. Подобрать порог кластеризации.
4. Склейка: слова из ASR распределяем по спикерам через пересечение с сегментами диаризации и собираем реплики.
5. LLM (Qwen3.5-0.8B Q4_K_M, llama.cpp): название и резюме разговора. Запускается после сохранения и экспорта транскрипта, слова не трогает.
6. Перенос в Kotlin: `SherpaPipeline`, загрузка моделей из assets, выгрузка каждой модели после её стадии.

### Общие
- Тестовый набор: 5–10 своих диалогов на 1–3 минуты с перебиваниями, один «телефонный», один на английском. К каждому ручной эталон в JSON.
- README: инструкция, архитектура, модели, ограничения, бенчмарки. Видео-демо, презентация.

## Целевое железо

- **Дедлайн:** 08.11.2026, 09:30 МСК (13:30 НСК).
- **Стенд жюри:** базовый эмулятор Android Studio, ~4 ГБ RAM. Архитектура не подтверждена: x86_64 на обычном ПК, arm64 на Mac. В чате говорили про ARM.
- **Поэтому:** собираем APK с `abiFilters += listOf("arm64-v8a", "x86_64")`. По умолчанию инференс на CPU (NNAPI/GPU в эмуляторе нет), ускорение включается отдельной опцией. Цель по пиковой RAM: не больше ~1.5–2 ГБ. Модели загружаем по очереди и выгружаем после каждой стадии.
- **Наше тестирование:** Pixel 8 (8 ГБ) + x86_64-эмулятор с 4 ГБ на ноутбуке. Эмулятор прогоняем регулярно, а не только в конце.

## Тайм-план (время НСК)

| Время | Михаил | oatis123 |
| :--- | :--- | :--- |
| 13:30–15:30 | Каркас проекта, контракт, FakePipeline | Python-стенд, eval.py, скачивание моделей |
| 15:30–20:00 | Импорт, декодер, экран обработки, транскрипт | Выбор ASR и диаризации по замерам, склейка |
| 20:00–23:00 | Плеер, экспорт, Foreground Service | `SherpaPipeline` на Kotlin |
| **~23:00** | **Первая интеграция: Pixel 8 + x86-эмулятор** | |
| 23:00–04:00 | Room, правки, запись с микрофона, полировка | LLM, профили FAST/ACCURATE, тюнинг |
| 04:00–08:00 | Отсыпаемся по очереди, фиксы | |
| **08:00** | **Feature freeze** | |
| 08:00–12:30 | Release APK, видео, README | Бенчмарки, ограничения, архитектура |
| 12:30–13:30 | Буфер, сдача | |

## Точки синхронизации

1. **Контракт зафиксирован** (сейчас): оба начинают параллельно.
2. **Первая интеграция**: `SherpaPipeline` с минимальным набором (VAD + ASR + диаризация) запускается в приложении на реальном телефоне. Это главный риск, поэтому делаем как можно раньше, даже на «плохих» моделях.
3. **Feature freeze**: дальше только фиксы, бенчмарки, README, видео.
