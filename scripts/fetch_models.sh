#!/usr/bin/env bash
# Скачивает веса моделей в android/app/src/main/assets/models/ (в git их нет).
# Повторный запуск пропускает уже скачанное. Пути внутри assets задаёт data/models/ModelLocator.kt — меняем их синхронно.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ASSETS="$ROOT/android/app/src/main/assets/models"
TEST_ASSETS="$ROOT/android/app/src/androidTest/assets"
CACHE="${MODELS_CACHE:-$ROOT/.models-cache}"
REL="https://github.com/k2-fsa/sherpa-onnx/releases/download"

mkdir -p "$ASSETS"/{vad,asr,lid,diarization,llm} "$TEST_ASSETS" "$CACHE"

fetch() { # url → файл в кэше
  local url="$1" out="$CACHE/$(basename "$1")"
  if [[ ! -s "$out" ]]; then
    echo "↓ $(basename "$url")" >&2
    curl -fsSL --retry 3 -o "$out.part" "$url" && mv "$out.part" "$out"
  fi
  echo "$out"
}

# --- VAD
[[ -s "$ASSETS/vad/silero_vad.onnx" ]] || cp "$(fetch "$REL/asr-models/silero_vad.onnx")" "$ASSETS/vad/silero_vad.onnx"

unpack() { # архив → временная папка (печатает путь к распакованной модели)
  local name="$1" url="$2" tmp; tmp="$(mktemp -d)"
  tar -xjf "$(fetch "$url")" -C "$tmp"
  echo "$tmp/$name"
}

# Модели выбраны по замерам на стенде (bench/README.md). Пути — ModelLocator.Paths.

# --- ASR ru: GigaAM v3 RNNT с пунктуацией и регистром
ASR_RU="$ASSETS/asr/giga-am-v3-rnnt-punct"
if [[ ! -s "$ASR_RU/encoder.int8.onnx" ]]; then
  src="$(unpack sherpa-onnx-nemo-transducer-punct-giga-am-v3-russian-2025-12-16     "$REL/asr-models/sherpa-onnx-nemo-transducer-punct-giga-am-v3-russian-2025-12-16.tar.bz2")"
  mkdir -p "$ASR_RU"
  cp "$src"/{encoder.int8.onnx,decoder.onnx,joiner.onnx,tokens.txt} "$ASR_RU/"
  # Пример речи из архива — для инструментального smoke-теста.
  first_wav="$(ls "$src"/test_wavs/*.wav 2>/dev/null | head -1 || true)"
  [[ -n "$first_wav" ]] && cp "$first_wav" "$TEST_ASSETS/speech_ru.wav"
  rm -rf "$(dirname "$src")"
fi

# --- ASR en: Parakeet TDT 110M int8
ASR_EN="$ASSETS/asr/parakeet-tdt-110m-en"
if [[ ! -s "$ASR_EN/encoder.int8.onnx" ]]; then
  src="$(unpack sherpa-onnx-nemo-parakeet_tdt_transducer_110m-en-36000-int8     "$REL/asr-models/sherpa-onnx-nemo-parakeet_tdt_transducer_110m-en-36000-int8.tar.bz2")"
  mkdir -p "$ASR_EN"
  cp "$src"/{encoder.int8.onnx,decoder.int8.onnx,joiner.int8.onnx,tokens.txt} "$ASR_EN/"
  rm -rf "$(dirname "$src")"
fi

# --- Определение языка: whisper tiny (только int8)
LID="$ASSETS/lid/whisper-tiny"
if [[ ! -s "$LID/tiny-decoder.int8.onnx" ]]; then
  src="$(unpack sherpa-onnx-whisper-tiny "$REL/asr-models/sherpa-onnx-whisper-tiny.tar.bz2")"
  mkdir -p "$LID"
  cp "$src"/{tiny-encoder.int8.onnx,tiny-decoder.int8.onnx} "$LID/"
  rm -rf "$(dirname "$src")"
fi

# --- Диаризация: сегментация pyannote 3.0 (fp32: int8 заметно хуже по DER) + эмбеддинги 3D-Speaker CAM++
if [[ ! -s "$ASSETS/diarization/segmentation.onnx" ]] || (( $(stat -c %s "$ASSETS/diarization/segmentation.onnx") < 4000000 )); then
  src="$(unpack sherpa-onnx-pyannote-segmentation-3-0     "$REL/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2")"
  cp "$src/model.onnx" "$ASSETS/diarization/segmentation.onnx"
  rm -rf "$(dirname "$src")"
fi
[[ -s "$ASSETS/diarization/embedding.onnx" ]] || cp   "$(fetch "$REL/speaker-recongition-models/3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx")"   "$ASSETS/diarization/embedding.onnx"

# --- LLM: Qwen3.5-0.8B Q4_K_M (название и резюме разговора)
LLM="$ASSETS/llm/qwen3.5-0.8b-q4_k_m.gguf"
[[ -s "$LLM" ]] || cp "$(fetch "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q4_K_M.gguf")" "$LLM"

# Старые раскладки, если остались от прошлых версий скрипта
rm -rf "$ASSETS/asr/giga-am-v3-ctc-punct" "$ASSETS/asr/ru" "$ASSETS/asr/en" "$ASSETS/lid"/*.onnx   "$ASSETS/diarization/segmentation.int8.onnx"

echo "Готово:"
du -sh "$ASSETS"/* 2>/dev/null
