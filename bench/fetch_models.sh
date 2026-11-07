#!/usr/bin/env bash
# Скачивает веса моделей. Модели в git не коммитятся.
#   ./fetch_models.sh            — боевой набор в android/app/src/main/assets/models/
#   ./fetch_models.sh candidates — кандидаты для сравнения в bench/.cache/models/
set -euo pipefail
cd "$(dirname "$0")"
ROOT="$(cd .. && pwd)"
REL=https://github.com/k2-fsa/sherpa-onnx/releases/download

fetch_tar() { # url dst_dir
  local url=$1 dst=$2 name; name=$(basename "$url" .tar.bz2)
  [ -d "$dst/$name" ] && { echo "skip $name"; return; }
  mkdir -p "$dst"; echo "get  $name"
  curl -fsSL "$url" | tar -xj -C "$dst"
}
fetch_file() { # url dst_file
  [ -f "$2" ] && { echo "skip $(basename "$2")"; return; }
  mkdir -p "$(dirname "$2")"; echo "get  $(basename "$2")"
  curl -fsSL -o "$2.part" "$1" && mv "$2.part" "$2"
}

if [ "${1:-}" = candidates ]; then
  C="$ROOT/bench/.cache/models"
  fetch_file "$REL/asr-models/silero_vad.onnx" "$C/silero_vad.onnx"
  for m in nemo-ctc-punct-giga-am-v3-russian-2025-12-16 nemo-transducer-punct-giga-am-v3-russian-2025-12-16 \
           nemo-ctc-giga-am-v2-russian-2025-04-19 nemo-parakeet-tdt-0.6b-v3-int8 \
           nemo-parakeet_tdt_transducer_110m-en-36000-int8 whisper-tiny; do
    fetch_tar "$REL/asr-models/sherpa-onnx-$m.tar.bz2" "$C/asr"
  done
  fetch_tar "$REL/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2" "$C/seg"
  for e in 3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k 3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced \
           3dspeaker_speech_eres2netv2_sv_zh-cn_16k-common wespeaker_en_voxceleb_resnet34_LM nemo_en_titanet_small \
           wespeaker_en_voxceleb_CAM++; do
    fetch_file "$REL/speaker-recongition-models/$e.onnx" "$C/emb/$e.onnx"
  done
  exit 0
fi

# ---------------------------------------------------------------- боевой набор
# Раскладка должна совпадать с data/pipeline/sherpa/SherpaModels.kt и data/llm/LlmSummarizer.kt
A="$ROOT/android/app/src/main/assets/models"
T="$ROOT/bench/.cache/dl"
mkdir -p "$A"/{vad,asr/ru,asr/en,lid,diarization,llm} "$T" "$ROOT/android/app/libs"

# sherpa-onnx AAR (JNI + Kotlin API)
fetch_file "https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar"   "$ROOT/android/app/libs/sherpa-onnx-2.0.1.aar"

fetch_file "$REL/asr-models/silero_vad.onnx" "$A/vad/silero_vad.onnx"

# ASR ru: GigaAM v3 RNNT с пунктуацией
G=sherpa-onnx-nemo-transducer-punct-giga-am-v3-russian-2025-12-16
fetch_tar "$REL/asr-models/$G.tar.bz2" "$T"
cp -u "$T/$G"/{encoder.int8.onnx,decoder.onnx,joiner.onnx,tokens.txt} "$A/asr/ru/"

# ASR en: Parakeet TDT 110M int8
P=sherpa-onnx-nemo-parakeet_tdt_transducer_110m-en-36000-int8
fetch_tar "$REL/asr-models/$P.tar.bz2" "$T"
cp -u "$T/$P"/{encoder.int8.onnx,decoder.int8.onnx,joiner.int8.onnx,tokens.txt} "$A/asr/en/"

# LID: whisper tiny (только int8)
W=sherpa-onnx-whisper-tiny
fetch_tar "$REL/asr-models/$W.tar.bz2" "$T"
cp -u "$T/$W"/{tiny-encoder.int8.onnx,tiny-decoder.int8.onnx} "$A/lid/"

# Диаризация: pyannote segmentation 3.0 (int8) + эмбеддинги спикеров
S=sherpa-onnx-pyannote-segmentation-3-0
fetch_tar "$REL/speaker-segmentation-models/$S.tar.bz2" "$T"
cp -u "$T/$S/model.onnx" "$A/diarization/segmentation.onnx"
EMB=3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced
fetch_file "$REL/speaker-recongition-models/$EMB.onnx" "$A/diarization/embedding.onnx"

# LLM: Qwen3.5-0.8B Q4_K_M (llama.cpp)
fetch_file "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q4_K_M.gguf"   "$A/llm/qwen3.5-0.8b-q4_k_m.gguf"

du -sh "$A"/* 2>/dev/null
