# bench — стенд ML-пайплайна

Python-реализация того же пайплайна, что `SherpaPipeline` на Kotlin (логика 1:1), плюс тестовый набор и метрики.

```bash
python -m venv .venv && .venv/Scripts/pip install sherpa-onnx jiwer soundfile numpy pyannote.metrics huggingface_hub
bash ../scripts/fetch_models.sh   # боевой набор → android/app/src/main/assets/models/
bash fetch_models.sh candidates   # кандидаты для сравнения → .cache/models/
python make_dialogs.py --lang ru --n 8                      # синтетические диалоги из Common Voice
python make_dialogs.py --lang en --n 3 --turns 10 --min-clips 3
python run.py --data data/ru --name prod --set language=auto   # прогон + eval
python sweep_diar.py --data data/ru --emb campplus eres2net --thr 0.7 0.8
```

| Файл | Что делает |
| :--- | :--- |
| `make_dialogs.py` | Диалоги из клипов Common Voice: 2–3 спикера, быстрые смены реплик, перебивания с наложением до 0.7 с, последний файл «телефонный» (8 кГц, 300–3400 Гц, шум). Эталон в формате экспорта |
| `pipeline.py` | VAD → LID → диаризация → ASR → склейка, все параметры в `Config` |
| `run.py` | Прогон папки → JSON + `*.meta.json` (RTF, время стадий) → eval |
| `eval.py` | WER (`wer` без пунктуации, `wer_p` с пунктуацией), DER (collar 0.25 / 0), ошибка таймстемпов реплик |
| `sweep_diar.py` | Перебор эмбеддингов и порогов диаризации с кэшем слов ASR |

## Результаты (ноутбук, 4 потока CPU)

Набор: 8 русских диалогов (39–87 с, 1 «телефонный») и 3 английских.

**Боевая конфигурация, профиль ACCURATE:**

| | WER | WER с пунктуацией | DER | RTF |
| :--- | ---: | ---: | ---: | ---: |
| ru | 3.6% | 7.0% | 6.9% | 0.13 |
| en | 14.5% | 17.4% | 7.2% | 0.12 |

Профиль FAST (`win_shift=0.25`): ru DER 8.9%, RTF 0.086, WER тот же.

**ASR, ru** (диаризация одинаковая):

| Модель | WER | WER с пункт. | RTF |
| :--- | ---: | ---: | ---: |
| **GigaAM v3 RNNT punct** | **3.6%** | **7.0%** | 0.11 |
| GigaAM v3 CTC punct | 3.6% | 8.7% | 0.10 |
| Zipformer-ru int8 | 8.8% | 23.6% (нет пунктуации) | 0.11 |
| NeMo FastConformer multilingual int8 | 11.0% | 16.7% | 0.11 |
| Parakeet TDT 0.6B v3 | 15.0% | 17.4% | 0.14 |

**ASR, en:** Parakeet TDT 110M — WER 14.5%, RTF 0.105; Parakeet TDT 0.6B v3 — 23.0%, RTF 0.147.

**Эмбеддинги спикеров** (DER, collar 0.25, ru / en, лучший порог):

| Модель | ru | en |
| :--- | ---: | ---: |
| **3D-Speaker CAM++ zh_en advanced (26 МБ)** | **7.8–8.3%** | **7.6%** |
| 3D-Speaker ERes2NetV2 | 8.3% | 16.2% |
| 3D-Speaker ERes2Net base | 11.9% | 10.8% |
| WeSpeaker ResNet34 | 50% | 48% |

CAM++ почти не зависит от порога (0.7–0.85). У ERes2Net при пороге 0.5 (по умолчанию) DER 31%: он находит лишних спикеров.

Сегментация pyannote: int8-версия поднимает DER с 6.9% до 11.3%, поэтому в сборке fp32 (6 МБ).

**LLM** (резюме, llama.cpp CPU, ноутбук): Qwen3.5-0.8B Q4_K_M — 370 ток/с на промпте и 44 ток/с на генерации, формат держит, если начать ответ за модель с «Название:». Gemma-3-1B формат ломает. Qwen3.5-2B пишет лучше, но весит 1.2 ГБ и вдвое медленнее.

## На устройстве (эмулятор x86_64, 4 ГБ RAM, 4 ядра)

ru_03 (72 с): результат совпадает с Python один в один (WER 4.8%, DER 4.7%).
FAST: RTF 0.35 (VAD 1.3 с, диаризация 8.9 с, ASR 14.8 с), пиковый RSS 276 МБ. Сводка LLM — ещё ~15 с после готового транскрипта.

## Ограничения

- Наложение голосов: каждое слово получает одного спикера. Слова в зоне перекрытия уходят тому, у кого больше пересечение по времени.
- «Телефонные» записи: диаризация хуже (DER до 12–20%), короткие реплики вроде «Да!» иногда сливаются с соседними.
- Синтетика собрана из чтения Common Voice, это не живой разговор. Абсолютные цифры на тестах жюри будут другими, но сравнение моделей между собой честное.
