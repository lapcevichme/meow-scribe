# meow-scribe — «Нейро-стенографист»

Хакатон Nuclear IT Hack (КСИТ / HEX TEAM). Android-приложение, которое **полностью на устройстве** превращает аудиозапись разговора в текст: распознаёт речь, разделяет реплики по говорящим и проставляет временные метки. Есть экспорт в JSON.

Дедлайн: **08.11.2026, 09:30 МСК (13:30 НСК)**. Команда из двух человек:
- **Михаил:** приложение и UX;
- **oatis123:** ML-пайплайн.

## Git

- **Не добавлять** в коммиты и PR строки `Co-Authored-By: Claude ...`, `🤖 Generated with Claude Code` и любую другую атрибуцию ИИ. Организаторы прямо сказали, что это кринж.
- Коммитить только по просьбе. Сообщения коммитов короткие, в стиле `feat: ...`, `fix: ...`, `docs: ...`.
- Файлы моделей (`*.onnx`, `*.gguf`, `*.bin` и т. п.) в git не коммитить. Они скачиваются скриптом.

## Где что лежит

| Файл | Что внутри |
| :--- | :--- |
| `docs/nuclear_it_hack.md` | Официальный кейс: ТЗ, ограничения, JSON-схема, критерии оценки |
| `docs/telegram_chat.md` | Q&A с организаторами. Уточнения, которых нет в ТЗ |
| `docs/solution_concept.md` | Исходная концепция: пайплайн, дизайн M3 Expressive, UX-фичи. Частично устарела, актуальные решения в `team_plan.md` |
| `docs/team_plan.md` | **Актуальный план**: роли, контракт между приложением и пайплайном, задачи, тайм-план, целевое железо |

Если решения в документах противоречат друг другу, приоритет такой: `team_plan.md` → `telegram_chat.md` → `nuclear_it_hack.md` → `solution_concept.md`.

## Жёсткие ограничения (нарушение = дисквалификация или 0 баллов)

- Вся обработка идёт на устройстве. Облачные API запрещены. Системный `SpeechRecognizer` не использовать.
- Разрешение `INTERNET` под вопросом. Организаторы разрешили догрузку моделей из сети, но прямо на вопрос не ответили. Пока перестраховываемся: в основной сборке разрешения **нет**. Если будем делать догрузку моделей, вынесем её в отдельный build flavor.
- Модели **встроены в APK** (assets, без сжатия). Догрузка из сети допустима только как необязательная фича для UX.
- Обязательна локальная LLM, но её задачу выбираем сами. LLM **не должна менять слова** транскрипта, иначе вырастет WER.
- Экспорт строго по схеме:
  `{"transcript":[{"speaker_id":"speaker_1","start_ms":1250,"end_ms":4380,"text":"..."}]}`
  `speaker_id` и `text` — строки, `start_ms` и `end_ms` — целые числа в миллисекундах.
- Языки: сначала русский, потом английский. Пунктуация входит в WER.

## Целевое железо

- Жюри: базовый эмулятор Android Studio, ~4 ГБ RAM, архитектура не подтверждена. Собираем `arm64-v8a` и `x86_64`, по умолчанию инференс на CPU.
- Пиковая RAM не больше ~1.5–2 ГБ. Модели загружаются по очереди и выгружаются после каждой стадии.
- Тестируем на Pixel 8 и на x86_64-эмуляторе с 4 ГБ.

## Оценка (100 баллов)

WER 40 · диаризация 25 · таймстемпы 10 · RTF 10 (относительно других команд) · работоспособность и UX 10 · защита 5.

## Стек

Kotlin, Jetpack Compose, Material 3 (Expressive), Media3 (ExoPlayer), Room. Инференс: sherpa-onnx (VAD, диаризация, ASR) и llama.cpp (LLM).

Пайплайн отделён от UI интерфейсом `TranscriptionPipeline` (см. `docs/team_plan.md`):
- `FakePipeline` — для разработки UI;
- `SherpaPipeline` — настоящая реализация.

## Структура репозитория

```
meow-scribe/
├── CLAUDE.md
├── docs/        # кейс, план, Q&A
├── android/     # Gradle-проект (открывать в Android Studio именно эту папку)
└── bench/       # Python-стенд на sherpa-onnx, eval-скрипты, fetch_models.sh (oatis123)
```

## Архитектура Android-приложения

Clean architecture **на пакетах** в одном модуле `:app`, MVVM + UiState, DI на Hilt.
Корневой пакет: `com.lapcevichme.meowscribe` (`android/app/src/main/java/com/lapcevichme/meowscribe/`).

```
domain/                      # чистый Kotlin, без android.*
├── model/                   # Utterance, Word, PipelineConfig, Profile, Session …
├── pipeline/                # TranscriptionPipeline, PipelineEvent, Stage, Stats — КОНТРАКТ
├── repository/              # интерфейсы: SessionRepository, AudioSource, TranscriptExporter
├── processing/              # ProcessingQueue — очередь обработки
└── usecase/                 # только где есть логика (ProcessAudio, Export)
data/
├── pipeline/fake/           # FakePipeline — для разработки UI
├── pipeline/sherpa/         # SherpaPipeline: VAD, диаризация, ASR, склейка (oatis123)
├── llm/                     # обёртка llama.cpp (oatis123)
├── audio/                   # декодер MediaCodec → 16 кГц моно float, запись с микрофона
├── db/                      # Room
├── export/                  # JSON (строгая схема), TXT, SRT
├── models/                  # ModelLocator — пути к весам, копирование .gguf из assets
├── processing/              # ProcessingManager — очередь и прогресс
└── repository/              # реализации интерфейсов domain
service/                     # Foreground Service обработки
di/                          # Hilt-модули
presentation/
├── components/              # общие composable: спикеры, форматтеры, топбар
├── theme/  navigation/
└── feature/{home,processing,transcript}/
```

### Владение кодом

- oatis123: `data/pipeline/sherpa/`, `data/llm/`, `bench/`.
- Михаил: всё остальное.
- `domain/pipeline/` и `domain/model/` — общий контракт, менять только по договорённости.

### Соглашения presentation

- `ViewModel` отдаёт `StateFlow<XxxUiState>`. У каждого экрана свой `sealed interface XxxUiState` (`Loading` / `NotFound` / `Content` …), общего LCE-шаблона нет. Плеер и другие независимые части состояния живут в отдельном `StateFlow` рядом.
- Разовые события (снекбар, навигация) идут через `Channel` → `receiveAsFlow()`.
- Обработку держит не ViewModel, а `@Singleton ProcessingManager` (`data/processing/`, интерфейс `domain/processing/ProcessingQueue`): последовательная очередь со `StateFlow` прогресса. Foreground Service держит процесс живым и показывает уведомление, ViewModel только подписывается.

### ML-модели (веса)

- Лежат в `android/app/src/main/assets/models/{vad,asr,diarization,llm}/`. В git их нет, скачиваются скриптом `bench/fetch_models.sh`. Python-стенд берёт веса оттуда же.
- sherpa-onnx читает модели прямо из `AssetManager`. `.gguf` для llama.cpp при первом запуске копируется в `filesDir`.
- Все пути к весам задаются только в `data/models/ModelLocator`.
