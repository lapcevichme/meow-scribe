"""Эталонная реализация пайплайна на Python (sherpa-onnx). Логика 1:1 с SherpaPipeline на Kotlin.

VAD → ASR по речевым чанкам (слова с таймстемпами) → диаризация → склейка слов со спикерами.
"""
from __future__ import annotations

import time
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np
import sherpa_onnx

SR = 16000
HERE = Path(__file__).parent
CACHE = HERE / ".cache/models"
ASSETS = HERE.parent / "android/app/src/main/assets/models"


def _m(*parts) -> str:
    """Ищет файл модели сначала в assets приложения, потом в кэше кандидатов."""
    for root in (ASSETS, CACHE):
        p = root.joinpath(*parts)
        if p.exists():
            return str(p)
    raise FileNotFoundError("/".join(parts))


# ---------------------------------------------------------------- ASR models
def asr_factory(name: str, threads: int):
    if name == "giga3_ctc":
        d = "asr/sherpa-onnx-nemo-ctc-punct-giga-am-v3-russian-2025-12-16"
        return sherpa_onnx.OfflineRecognizer.from_nemo_ctc(
            model=_m(d, "model.int8.onnx"), tokens=_m(d, "tokens.txt"), num_threads=threads)
    if name == "giga3_rnnt":
        d = "asr/ru" if (ASSETS / "asr/ru").exists() else "asr/sherpa-onnx-nemo-transducer-punct-giga-am-v3-russian-2025-12-16"
        return sherpa_onnx.OfflineRecognizer.from_transducer(
            encoder=_m(d, "encoder.int8.onnx"), decoder=_m(d, "decoder.onnx"), joiner=_m(d, "joiner.onnx"),
            tokens=_m(d, "tokens.txt"), model_type="nemo_transducer", num_threads=threads)
    if name == "giga2_ctc":
        d = "asr/sherpa-onnx-nemo-ctc-giga-am-v2-russian-2025-04-19"
        return sherpa_onnx.OfflineRecognizer.from_nemo_ctc(
            model=_m(d, "model.int8.onnx"), tokens=_m(d, "tokens.txt"), num_threads=threads)
    if name == "zipformer_ru":
        d = "asr/sherpa-onnx-zipformer-ru-int8-2025-04-20"
        return sherpa_onnx.OfflineRecognizer.from_transducer(
            encoder=_m(d, "encoder.int8.onnx"), decoder=_m(d, "decoder.onnx"), joiner=_m(d, "joiner.int8.onnx"),
            tokens=_m(d, "tokens.txt"), num_threads=threads)
    if name == "fastconf_ml":
        d = "asr/sherpa-onnx-nemo-fast-conformer-transducer-be-de-en-es-fr-hr-it-pl-ru-uk-20k-int8"
        return sherpa_onnx.OfflineRecognizer.from_transducer(
            encoder=_m(d, "encoder.int8.onnx"), decoder=_m(d, "decoder.int8.onnx"), joiner=_m(d, "joiner.int8.onnx"),
            tokens=_m(d, "tokens.txt"), model_type="nemo_transducer", num_threads=threads)
    if name in ("parakeet3", "parakeet110"):
        d = ("asr/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8" if name == "parakeet3"
             else "asr/en" if (ASSETS / "asr/en").exists()
             else "asr/sherpa-onnx-nemo-parakeet_tdt_transducer_110m-en-36000-int8")
        return sherpa_onnx.OfflineRecognizer.from_transducer(
            encoder=_m(d, "encoder.int8.onnx"), decoder=_m(d, "decoder.int8.onnx"),
            joiner=_m(d, "joiner.int8.onnx"), tokens=_m(d, "tokens.txt"),
            model_type="nemo_transducer", num_threads=threads)
    raise ValueError(name)


EMB = {
    "eres2net": "3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k",
    "campplus": "3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced",
    "eres2netv2": "3dspeaker_speech_eres2netv2_sv_zh-cn_16k-common",
    "resnet34": "wespeaker_en_voxceleb_resnet34_LM",
    "titanet": "nemo_en_titanet_small",
    "campplus_en": "wespeaker_en_voxceleb_CAM++",
}


@dataclass
class Config:
    asr: str = "giga3_rnnt"
    asr_en: str = "parakeet110"
    language: str = "ru"            # ru | en | auto
    emb: str = "prod"               # prod = assets/diarization/embedding.onnx (CAM++ zh_en)
    num_speakers: int | None = None
    cluster_threshold: float = 0.8
    min_on: float = 0.3
    min_off: float = 0.5
    win_shift: float = 0.1          # шаг окна pyannote (доля 10-с окна)
    vad_min_silence: float = 0.3
    vad_max_speech: float = 20.0
    pad: float = 0.15
    split_gap: float = 3.0
    sent_gap: float = 0.4           # пауза после конца предложения → новая реплика
    merge: str = "words"            # words | segments
    threads: int = 4


@dataclass
class Word:
    text: str
    start: float
    end: float
    spk: int = -1


@dataclass
class Result:
    utterances: list[dict]
    timings: dict = field(default_factory=dict)
    words: list[Word] = field(default_factory=list)
    segments: list = field(default_factory=list)
    language: str = "ru"


# ---------------------------------------------------------------- stages
def vad_chunks(pcm: np.ndarray, cfg: Config) -> list[tuple[int, int]]:
    c = sherpa_onnx.VadModelConfig()
    c.silero_vad.model = str(ASSETS / "vad/silero_vad.onnx") if (ASSETS / "vad/silero_vad.onnx").exists() else str(CACHE / "silero_vad.onnx")
    c.silero_vad.min_silence_duration = cfg.vad_min_silence
    c.silero_vad.min_speech_duration = 0.1
    c.silero_vad.max_speech_duration = cfg.vad_max_speech
    c.sample_rate = SR
    vad = sherpa_onnx.VoiceActivityDetector(c, buffer_size_in_seconds=int(len(pcm) / SR) + 10)
    w = c.silero_vad.window_size
    out = []

    def drain():
        while not vad.empty():
            out.append((vad.front.start, vad.front.start + len(vad.front.samples)))
            vad.pop()

    for i in range(0, len(pcm) - w + 1, w):
        vad.accept_waveform(pcm[i:i + w])
        drain()
    vad.flush()
    drain()
    pad = int(cfg.pad * SR)
    return [(max(0, s - pad), min(len(pcm), e + pad)) for s, e in out]


def tokens_to_words(tokens, stamps, offset: float, chunk_end: float) -> list[Word]:
    words: list[Word] = []
    starts: list[float] = []  # время первого токена слова
    lasts: list[float] = []   # время последнего токена слова
    pending = False  # был отдельный токен-пробел → следующий токен начинает слово
    for tok, t in zip(tokens, stamps):
        t = offset + t
        if tok.startswith(" ") or tok.startswith("▁") or pending or not words:
            piece = tok.lstrip(" ▁")
            if not piece:
                pending = True
                continue
            pending = False
            # пунктуация, отделённая пробелом, клеится к предыдущему слову
            if words and all(not ch.isalnum() for ch in piece):
                words[-1].text += piece
                lasts[-1] = t
                continue
            words.append(Word(piece, t, t))
            starts.append(t)
            lasts.append(t)
        else:
            words[-1].text += tok
            lasts[-1] = t
    for i, w in enumerate(words):
        nxt = words[i + 1].start if i + 1 < len(words) else chunk_end
        w.end = max(w.start + 0.08, min(nxt - 0.02, lasts[i] + 0.25))
    return words


def run_asr(rec, pcm, chunks, on_progress=None) -> list[Word]:
    words = []
    for i, (s, e) in enumerate(chunks):
        st = rec.create_stream()
        st.accept_waveform(SR, pcm[s:e])
        rec.decode_stream(st)
        r = st.result
        words += tokens_to_words(r.tokens, r.timestamps, s / SR, e / SR)
        if on_progress:
            on_progress((i + 1) / len(chunks))
    return words


def diarize(pcm, cfg: Config):
    c = sherpa_onnx.OfflineSpeakerDiarizationConfig(
        segmentation=sherpa_onnx.OfflineSpeakerSegmentationModelConfig(
            pyannote=sherpa_onnx.OfflineSpeakerSegmentationPyannoteModelConfig(
                window_shift_ratio=cfg.win_shift,
                model=str(ASSETS / "diarization/segmentation.onnx")
                if (ASSETS / "diarization/segmentation.onnx").exists()
                else str(CACHE / "seg/sherpa-onnx-pyannote-segmentation-3-0/model.onnx")),
            num_threads=cfg.threads),
        embedding=sherpa_onnx.SpeakerEmbeddingExtractorConfig(
            model=(str(ASSETS / "diarization/embedding.onnx") if cfg.emb == "prod"
                   else str(CACHE / "emb" / f"{EMB[cfg.emb]}.onnx") if cfg.emb in EMB else cfg.emb),
            num_threads=cfg.threads),
        clustering=sherpa_onnx.FastClusteringConfig(
            num_clusters=cfg.num_speakers or -1, threshold=cfg.cluster_threshold),
        min_duration_on=cfg.min_on,
        min_duration_off=cfg.min_off,
    )
    sd = sherpa_onnx.OfflineSpeakerDiarization(c)
    return [(s.start, s.end, s.speaker) for s in sd.process(pcm).sort_by_start_time()]


def assign_speakers(words: list[Word], segs) -> None:
    for w in words:
        best, ov = None, 0.0
        for s, e, k in segs:
            o = min(w.end, e) - max(w.start, s)
            if o > ov:
                best, ov = k, o
        if best is None and segs:  # слово вне сегментов — ближайший сегмент
            mid = (w.start + w.end) / 2
            best = min(segs, key=lambda x: 0 if x[0] <= mid <= x[1] else min(abs(mid - x[0]), abs(mid - x[1])))[2]
        w.spk = best if best is not None else 0


def build_utterances(words: list[Word], split_gap: float, sent_gap: float = 1e9) -> list[dict]:
    utts, cur = [], []
    for w in words:
        gap = w.start - cur[-1].end if cur else 0
        if cur and (w.spk != cur[-1].spk or gap > split_gap
                    or (gap > sent_gap and cur[-1].text.rstrip("»\"')").endswith((".", "!", "?", "…")))):
            utts.append(cur)
            cur = []
        cur.append(w)
    if cur:
        utts.append(cur)
    names: dict[int, str] = {}
    out = []
    for u in utts:
        sid = names.setdefault(u[0].spk, f"speaker_{len(names) + 1}")
        out.append({"speaker_id": sid, "start_ms": round(u[0].start * 1000), "end_ms": round(u[-1].end * 1000),
                    "text": " ".join(w.text for w in u)})
    return out


def merge_segments(segs, gap=0.5):
    out = []
    for s, e, k in segs:
        if out and out[-1][2] == k and s - out[-1][1] < gap:
            out[-1] = (out[-1][0], max(e, out[-1][1]), k)
        else:
            out.append((s, e, k))
    return out


_LID = None


def detect_language(pcm, chunks, threads) -> str:
    global _LID
    if _LID is None:
        d = "lid" if (ASSETS / "lid").exists() else "asr/sherpa-onnx-whisper-tiny"
        _LID = sherpa_onnx.SpokenLanguageIdentification(sherpa_onnx.SpokenLanguageIdentificationConfig(
            whisper=sherpa_onnx.SpokenLanguageIdentificationWhisperConfig(
                encoder=_m(d, "tiny-encoder.int8.onnx"), decoder=_m(d, "tiny-decoder.int8.onnx")),
            num_threads=threads))
    speech = np.concatenate([pcm[s:e] for s, e in chunks])[: 29 * SR] if chunks else pcm[: 29 * SR]
    st = _LID.create_stream()
    st.accept_waveform(SR, speech)
    lang = _LID.compute(st)
    return "en" if lang == "en" else "ru"


_REC: dict = {}


def get_rec(name, threads):
    if name not in _REC:
        _REC.clear()
        _REC[name] = asr_factory(name, threads)
    return _REC[name]


def run(pcm: np.ndarray, cfg: Config) -> Result:
    t = {}
    t0 = time.perf_counter()
    chunks = vad_chunks(pcm, cfg)
    t["vad"] = time.perf_counter() - t0

    lang = cfg.language
    if lang == "auto":
        t0 = time.perf_counter()
        lang = detect_language(pcm, chunks, cfg.threads)
        t["lid"] = time.perf_counter() - t0

    t0 = time.perf_counter()
    segs = diarize(pcm, cfg)
    t["diar"] = time.perf_counter() - t0

    t0 = time.perf_counter()
    rec = get_rec(cfg.asr if lang == "ru" else cfg.asr_en, cfg.threads)
    if cfg.merge == "segments":
        merged = merge_segments(segs)
        pad = int(cfg.pad * SR)
        words = []
        for s, e, k in merged:
            ws = run_asr(rec, pcm, [(max(0, int(s * SR) - pad), min(len(pcm), int(e * SR) + pad))])
            for w in ws:
                w.spk = k
            words += ws
        words.sort(key=lambda w: w.start)
        t["asr"] = time.perf_counter() - t0
        t0 = time.perf_counter()
    else:
        words = run_asr(rec, pcm, chunks)
        t["asr"] = time.perf_counter() - t0
        t0 = time.perf_counter()
        assign_speakers(words, segs)
    utts = build_utterances(words, cfg.split_gap, cfg.sent_gap)
    t["merge"] = time.perf_counter() - t0
    return Result(utts, t, words, segs, lang)
