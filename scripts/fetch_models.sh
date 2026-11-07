#!/usr/bin/env bash
# Скачивает веса моделей в android/app/src/main/assets/models/ (в git их нет).
# Повторный запуск пропускает уже скачанное. Пути внутри assets задаёт data/models/ModelLocator.kt — меняем их синхронно.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ASSETS="$ROOT/android/app/src/main/assets/models"
TEST_ASSETS="$ROOT/android/app/src/androidTest/assets"
CACHE="${MODELS_CACHE:-$ROOT/.models-cache}"
REL="https://github.com/k2-fsa/sherpa-onnx/releases/download"

mkdir -p "$ASSETS"/{vad,asr,diarization,llm} "$TEST_ASSETS" "$CACHE"

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

# --- ASR: GigaAM v3 CTC с пунктуацией и регистром (русский)
ASR_NAME="sherpa-onnx-nemo-ctc-punct-giga-am-v3-russian-2025-12-16"
ASR_DIR="$ASSETS/asr/giga-am-v3-ctc-punct"
if [[ ! -s "$ASR_DIR/model.int8.onnx" ]]; then
  tmp="$(mktemp -d)"
  tar -xjf "$(fetch "$REL/asr-models/$ASR_NAME.tar.bz2")" -C "$tmp"
  mkdir -p "$ASR_DIR"
  cp "$tmp/$ASR_NAME/model.int8.onnx" "$tmp/$ASR_NAME/tokens.txt" "$ASR_DIR/"
  # Пример речи из архива — для инструментального smoke-теста.
  first_wav="$(ls "$tmp/$ASR_NAME"/test_wavs/*.wav 2>/dev/null | head -1 || true)"
  [[ -n "$first_wav" ]] && cp "$first_wav" "$TEST_ASSETS/speech_ru.wav"
  rm -rf "$tmp"
fi

# --- Диаризация: сегментация pyannote 3.0 + эмбеддинги 3D-Speaker CAM++
if [[ ! -s "$ASSETS/diarization/segmentation.onnx" ]]; then
  tmp="$(mktemp -d)"
  tar -xjf "$(fetch "$REL/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2")" -C "$tmp"
  cp "$tmp/sherpa-onnx-pyannote-segmentation-3-0/model.int8.onnx" "$ASSETS/diarization/segmentation.onnx"
  rm -rf "$tmp"
fi
[[ -s "$ASSETS/diarization/embedding.onnx" ]] || cp \
  "$(fetch "$REL/speaker-recongition-models/3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx")" \
  "$ASSETS/diarization/embedding.onnx"

# --- LLM (.gguf) кладётся в $ASSETS/llm/ — модель выбирает oatis123.

echo "Готово:"
du -sh "$ASSETS"/* 2>/dev/null
