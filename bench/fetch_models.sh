#!/usr/bin/env bash
# Скачивает веса моделей. Модели в git не коммитятся.
#   ./fetch_models.sh candidates — кандидаты для сравнения в bench/.cache/models/
# Боевой набор (в assets приложения) качает scripts/fetch_models.sh.
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

echo "боевой набор: scripts/fetch_models.sh; кандидаты: $0 candidates" >&2
exit 1
