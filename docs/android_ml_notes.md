# ML на Android: что уже проверено

Заметки для `SherpaPipeline` и LLM. Всё ниже запускалось на устройстве, а не на стенде.

## Что готово

| Что | Где |
| :--- | :--- |
| sherpa-onnx v2.0.1 (AAR с JitPack, Kotlin API `com.k2fsa.sherpa.onnx.*`) | `android/gradle/libs.versions.toml` → `sherpaOnnx` |
| Пути ко всем весам, список моделей в APK, копирование `.gguf` в `filesDir` | `data/models/ModelLocator.kt` |
| Скачивание весов в assets | `scripts/fetch_models.sh` |
| llama.cpp `b12185`: CMake + JNI + Kotlin API | модуль `android/llama/`, класс `LlamaModel` |
| Smoke-тесты: загрузка, RTF, RSS | `app/src/androidTest/.../ModelsSmokeTest.kt`, `LlamaSmokeTest.kt` |

Базовый набор весов в `fetch_models.sh` — **просто рабочий старт, не выбор**. Меняй модели по результатам стенда, обновляя пути в скрипте и в `ModelLocator.Paths`.

## Замеры

Эмулятор, похожий на стенд жюри: x86_64, 4 ГБ RAM, страницы 16 КБ, 4 ядра. Аудио — 11.3 с речи из `test_wavs` GigaAM. На Pixel 8 ещё не гоняли.

| Модель | Загрузка | Прогон | RTF | RSS |
| :--- | ---: | ---: | ---: | :--- |
| Silero VAD | 14 мс | 96 мс | 0.009 | — |
| GigaAM v3 CTC punct (int8, 215 МБ) | 1.5 с | 3.5 с | 0.31 | 218 → 480 МБ |
| pyannote seg 3.0 + CAM++ (zh_en advanced) | 0.9 с | 1.2 с | 0.11 | — |
| Qwen2.5-0.5B-Instruct Q4_K_M | 0.4 с | 34 ток. за 1.1 с (~31 ток/с) | — | 206 → ~760 МБ |

Распознанный текст GigaAM v3 (уже с пунктуацией и регистром):
> Ничьих, не требуя похвал, счастлив уж я надеждой сладкой, Что дева с трепетом любви посмотрит, может быть украдкой На песни грешные мои. У лукоморья дуб зелёный,

Ответ Qwen 0.5B на просьбу расставить пунктуацию в «привет как дела я сегодня был в магазине…»:
> Привет как дела я сегодня был в магазине и купил молоко, хлеб и сыр. А ты что делал?

## Советы

1. **Попробуй GigaAM v3 с пунктуацией** (`sherpa-onnx-nemo-ctc-punct-giga-am-v3-russian-2025-12-16`, есть и transducer-вариант). Пунктуация входит в WER. Если эта модель ставит знаки не хуже LLM, LLM лучше отдать под сводку: тогда исчезает риск, что она поменяет слова. В sherpa есть ещё отдельная `OfflinePunctuation` (CT-Transformer).
2. **Qwen 0.5B ставит пунктуацию слабо.** В примере выше нет запятой после «Привет» и вопроса после «как дела». Если LLM всё-таки делает пунктуацию, сравни на стенде с пунктуацией GigaAM и обязательно проверяй, что последовательность слов не изменилась (иначе откатывай реплику к исходному тексту).
3. **Длинное аудио, скорее всего, надо резать.** Проверяли только 11 с целиком. Для минутных записей подавать в GigaAM куски по VAD (или по сегментам диаризации), а не весь файл.
4. **Память.** ASR ~480 МБ RSS, LLM ~760 МБ (с mmap и repack весов), вместе больше 1.2 ГБ. Держим по одной модели: загрузил → прогнал → `release()` / `close()`. У sherpa-объектов есть `release()`, у `LlamaModel` — `close()`.
5. **Потоки.** В тестах `numThreads = min(ядра, 4)`. На эмуляторе жюри, скорее всего, 4 ядра.
6. **Диаризация.** `FastClusteringConfig(numClusters = -1, threshold = 0.5f)` — дефолт для проверки, порог надо подбирать на стенде. Если пользователь задал число спикеров (`PipelineConfig.numSpeakers`), передавай его в `numClusters`.
7. **arm64.** llama.cpp собран с `armv8.2-a+dotprod+fp16`: Pixel 8 и Mac на M-чипах это поддерживают, а старые ARM без dotprod упадут с SIGILL. Если окажется проблемой — убрать флаг в `android/llama/src/main/cpp/CMakeLists.txt`.

## Как пользоваться

```kotlin
// sherpa-onnx: модели читаются прямо из assets
val recognizer = OfflineRecognizer(
    locator.assets,
    OfflineRecognizerConfig(
        modelConfig = OfflineModelConfig(
            nemo = OfflineNemoEncDecCtcModelConfig(model = ModelLocator.Paths.ASR_MODEL),
            tokens = ModelLocator.Paths.ASR_TOKENS,
            numThreads = 4,
        ),
    ),
)

// LLM: файл на диске, mmap
val file = locator.llmFile() ?: return  // null, если .gguf не положили в assets/models/llm/
LlamaModel.load(file.path, contextSize = 2048).use { llm ->
    val prompt = llm.chatPrompt(system = "…", user = text)
    val answer = llm.generate(prompt, maxTokens = 256, cancellation = { !coroutineContext.isActive })
}
```

Запуск smoke-тестов — в `CLAUDE.md`, раздел «Smoke-тесты на устройстве».
