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
        d = "asr/giga-am-v3-rnnt-punct" if (ASSETS / "asr/giga-am-v3-rnnt-punct").exists() else "asr/sherpa-onnx-nemo-transducer-punct-giga-am-v3-russian-2025-12-16"
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
             else "asr/parakeet-tdt-110m-en" if (ASSETS / "asr/parakeet-tdt-110m-en").exists()
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
    "campplus_vox": "3dspeaker_speech_campplus_sv_en_voxceleb_16k",
    "titanet_large": "nemo_en_titanet_large",
    "eres2net_large": "3dspeaker_speech_eres2net_large_sv_zh-cn_3dspeaker_16k",
}


@dataclass
class Config:
    asr: str = "giga3_rnnt"
    asr_en: str = "parakeet110"
    language: str = "ru"            # ru | en | auto
    emb: str = "prod"               # prod = assets/diarization/embedding.onnx (CAM++ zh_en)
    seg: str = "prod"               # prod = assets/diarization/segmentation.onnx (pyannote 3.0) | reverb | путь
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
    smooth_run: float = 1.0         # куски другого спикера короче — внутри предложения отдаются основному (0 = выкл)
    bc_max: float = 1.5             # поддакивания короче — отдельными репликами (0 = выкл)
    sent_min: float = 1.0           # предложения от этой длины сверяются по голосу (0 = выкл)
    sent_margin: float = 0.1        # перевес косинуса, с которого предложение переходит к другому спикеру
    sent_major: float = 0.1         # переходят только к спикерам с такой долей речи
    sent_cluster_min: float = 1.5   # предложения от этой длины кластеризуются → центроиды спикеров
    sent_cluster_max: int = 300     # не больше стольких самых длинных предложений в кластеризации
    sent_cluster_balance: float = 0.5  # кластер набрал меньше такой доли предложений своей метки → центроиды по сегментам
    sent_fragment_sim: float = 0.6  # мелкий спикер с голосом ближе этого к основному — обрывок, его предложения переносим
    merge: str = "words"            # words | segments
    recluster_sim: float = 0.0      # 0 = выкл.; склейка спикеров с косинусом эмбеддингов > порога
    min_spk_sec: float = 6.0        # спикеры с речью меньше (но не больше 15% всей речи) — к самому похожему
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
    chunks = [(max(0, s - pad), min(len(pcm), e + pad)) for s, e in out]
    # На сплошном шуме (гул голосов, ТВ) Silero не видит пауз и max_speech_duration не срабатывает:
    # VAD отдаёт всю запись одним сегментом, а ASR на кусках > 30 с выдаёт мусор. Режем сами.
    return [c for s, e in chunks for c in split_long(pcm, s, e, cfg.vad_max_speech)]


def split_long(pcm, s: int, e: int, max_len: float) -> list[tuple[int, int]]:
    """Делит [s, e) на куски не длиннее max_len по самым тихим 100-мс окнам во второй половине куска."""
    m, win, hop = int(max_len * SR), int(0.1 * SR), int(0.01 * SR)
    out = []
    while e - s > m:
        lo, hi = s + m // 2, s + m - win
        cs = np.concatenate([[0.0], np.cumsum(pcm[lo:hi + win].astype(np.float64) ** 2)])
        idx = np.arange(0, hi - lo, hop)
        cut = lo + int(idx[np.argmin(cs[idx + win] - cs[idx])]) + win // 2
        out.append((s, cut))
        s = cut
    out.append((s, e))
    return out


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


def _seg_model(cfg: Config) -> str:
    if cfg.seg == "reverb":
        return str(CACHE / "seg/sherpa-onnx-reverb-diarization-v1/model.onnx")
    if cfg.seg != "prod":
        return cfg.seg
    if (ASSETS / "diarization/segmentation.onnx").exists():
        return str(ASSETS / "diarization/segmentation.onnx")
    return str(CACHE / "seg/sherpa-onnx-pyannote-segmentation-3-0/model.onnx")


def diarize(pcm, cfg: Config):
    c = sherpa_onnx.OfflineSpeakerDiarizationConfig(
        segmentation=sherpa_onnx.OfflineSpeakerSegmentationModelConfig(
            pyannote=sherpa_onnx.OfflineSpeakerSegmentationPyannoteModelConfig(
                window_shift_ratio=cfg.win_shift,
                model=_seg_model(cfg)),
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
    segs = [(s.start, s.end, s.speaker) for s in sd.process(pcm).sort_by_start_time()]
    if not cfg.num_speakers and (cfg.recluster_sim > 0 or cfg.min_spk_sec > 0):
        segs = recluster(pcm, segs, cfg)
    return segs


_EXT = {}


def _extractor(cfg):
    path = (str(ASSETS / "diarization/embedding.onnx") if cfg.emb == "prod"
            else str(CACHE / "emb" / f"{EMB[cfg.emb]}.onnx") if cfg.emb in EMB else cfg.emb)
    if path not in _EXT:
        _EXT[path] = sherpa_onnx.SpeakerEmbeddingExtractor(
            sherpa_onnx.SpeakerEmbeddingExtractorConfig(model=path, num_threads=cfg.threads))
    return _EXT[path]


def speaker_embeddings(pcm, segs, cfg, max_sec=30.0):
    """Один эмбеддинг на спикера: склейка его самых длинных сегментов (до max_sec)."""
    ext = _extractor(cfg)
    embs, dur = {}, {}
    for k in sorted({x[2] for x in segs}):
        mine = sorted([x for x in segs if x[2] == k], key=lambda x: x[0] - x[1])
        dur[k] = sum(e - s for s, e, _ in mine)
        parts, tot = [], 0.0
        for s, e, _ in mine:
            if tot >= max_sec:
                break
            parts.append(pcm[int(s * SR):int(e * SR)])
            tot += e - s
        st = ext.create_stream()
        st.accept_waveform(SR, np.concatenate(parts))
        st.input_finished()
        v = np.array(ext.compute(st)) if ext.is_ready(st) else None
        if v is not None:
            embs[k] = v / (np.linalg.norm(v) + 1e-9)
    return embs, dur


def recluster(pcm, segs, cfg):
    """Второй проход поверх кластеризации sherpa: эмбеддинги по всей речи спикера надёжнее
    эмбеддингов коротких окон. Похожих спикеров склеиваем, «спикеров» с парой секунд речи
    отдаём самому похожему — на живом разговоре sherpa часто дробит одного человека на несколько."""
    if not segs:
        return segs
    embs, dur = speaker_embeddings(pcm, segs, cfg)
    label = {k: k for k in dur}
    groups = {k: [k] for k in embs}

    def cen(g):
        v = sum(embs[k] * dur[k] for k in groups[g])
        return v / (np.linalg.norm(v) + 1e-9)

    def gdur(g):
        return sum(dur[k] for k in groups[g])

    def merge(a, b):  # b → a
        groups[a] += groups.pop(b)

    while cfg.recluster_sim > 0 and len(groups) > 1:
        ks = list(groups)
        best, pair = -1.0, None
        for i in range(len(ks)):
            for j in range(i + 1, len(ks)):
                sim = float(cen(ks[i]) @ cen(ks[j]))
                if sim > best:
                    best, pair = sim, (ks[i], ks[j])
        if best < cfg.recluster_sim:
            break
        a, b = pair
        merge(a, b) if gdur(a) >= gdur(b) else merge(b, a)
    # на коротких записях порог не больше 15% речи, иначе двое собеседников по 5 с сольются в одного
    min_sec = min(cfg.min_spk_sec, 0.15 * sum(dur.values()))
    while min_sec > 0 and len(groups) > 1:
        small = min(groups, key=gdur)
        if gdur(small) >= min_sec:
            break
        c = cen(small)
        target = max((g for g in groups if g != small), key=lambda g: float(cen(g) @ c))
        merge(target, small)
    for g, ks in groups.items():
        for k in ks:
            label[k] = g
    return [(s, e, label[k]) for s, e, k in segs]


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


def _ends_sentence(text: str) -> bool:
    return text.rstrip("»\"')").endswith((".", "!", "?", "…", "—"))


# Строгие реакции-поддакивания. «ну», «вот», «так», «а» сюда не входят: в русском это обычные
# служебные слова посреди фразы, и на подкасте их перенос рвал реплики. 1:1 с WordMerger.BACKCHANNELS.
BACKCHANNELS = {
    "да", "ага", "угу", "мгм", "ясно", "понятно", "конечно", "ок", "окей", "хорошо", "точно", "верно",
    "именно", "согласен", "согласна", "ого", "вау", "ой", "хм", "мм", "ммм",
    "yeah", "yes", "yep", "yup", "mhm", "mmhmm", "mmhm", "uhhuh", "okay", "ok", "right", "sure", "exactly",
    "oh", "wow", "hmm", "mmm", "alright", "true", "cool",
}
_REPEATED = ("да", "ага", "угу", "yeah", "mm", "okay")


def is_backchannel(text: str) -> bool:
    """Повтор междометия («да-да-да») или междометие из списка со знаком после («Да.», «угу,»)."""
    key = "".join(ch for ch in text.lower().replace("ё", "е") if ch.isalnum())
    if not key:
        return False
    if any(len(key) >= 2 * len(b) and len(key) % len(b) == 0 and key == b * (len(key) // len(b)) for b in _REPEATED):
        return True
    return key in BACKCHANNELS and text.rstrip("»\"')").endswith((".", "!", "?", "…", ","))


def assign_backchannels(words: list[Word], segs) -> None:
    """Реакцию, наполовину накрытую сегментами двух разных спикеров, — спикеру с самым коротким сегментом.
    1:1 с WordMerger.assignBackchannels. Вызывать после assign_speakers."""
    for w in words:
        if not is_backchannel(w.text):
            continue
        half = max(w.end - w.start, 1e-3) * 0.5
        cover = [x for x in segs if min(w.end, x[1]) - max(w.start, x[0]) >= half]
        if len({k for _, _, k in cover}) >= 2:
            w.spk = min(cover, key=lambda x: x[1] - x[0])[2]


def sentences(words: list[Word], pause_gap: float = 1.0) -> list[tuple[int, int]]:
    """[lo, hi) предложений: .!?… или «—» в конце, «—» в начале следующего слова, пауза > pause_gap."""
    out, lo = [], 0
    for i, w in enumerate(words):
        if i == len(words) - 1 or _ends_sentence(w.text) or words[i + 1].text.startswith("—") \
                or words[i + 1].start - w.end > pause_gap:
            out.append((lo, i + 1))
            lo = i + 1
    return out


def main_speaker(words: list[Word], lo: int, hi: int) -> int:
    talk: dict[int, float] = {}
    for w in words[lo:hi]:
        talk[w.spk] = talk.get(w.spk, 0.0) + (w.end - w.start)
    return max(talk, key=talk.get)


def smooth_speakers(words: list[Word], max_run: float, pause_gap: float = 1.0) -> None:
    """Сглаживание меток внутри предложения (поддакивания рвут чужую фразу). 1:1 с WordMerger.smoothSpeakers.

    Куски не-основного спикера короче max_run с отдаются тому, кто говорит в предложении дольше всех.
    max_run <= 0 — выкл.
    """
    if max_run <= 0 or len(words) < 2:
        return
    for lo, hi in sentences(words, pause_gap):
        if hi - lo < 2 or len({w.spk for w in words[lo:hi]}) < 2:
            continue
        main = main_speaker(words, lo, hi)
        i = lo
        while i < hi:
            j = i
            while j + 1 < hi and words[j + 1].spk == words[i].spk:
                j += 1
            if words[i].spk != main and words[j].end - words[i].start < max_run:
                for k in range(i, j + 1):
                    words[k].spk = main
            i = j + 1


def extract_backchannels(words: list[Word], max_sec: float):
    """Кусок другого спикера внутри предложения из 1–2 реакций короче max_sec → отдельная реплика.
    Возвращает (основной поток, вынутые куски). 1:1 с WordMerger.extractBackchannels."""
    if max_sec <= 0:
        return words, []
    drop, extra = set(), []
    for lo, hi in sentences(words):
        if hi - lo < 2:
            continue
        main = main_speaker(words, lo, hi)
        i = lo
        while i < hi:
            j = i
            while j + 1 < hi and words[j + 1].spk == words[i].spk:
                j += 1
            if words[i].spk != main and j - i < 2 and words[j].end - words[i].start < max_sec \
                    and all(is_backchannel(words[k].text) for k in range(i, j + 1)):
                drop.update(range(i, j + 1))
                extra.append(words[i:j + 1])
            i = j + 1
    return [w for n, w in enumerate(words) if n not in drop], extra


def _ahc_complete(x: np.ndarray, k: int) -> list[int]:
    """Иерархическая кластеризация, полная связь, косинусное расстояние, до k кластеров.
    x — нормированные строки. Возвращает представителя кластера для каждой строки. 1:1 с SentenceRelabeler.cluster."""
    n = len(x)
    d = (1.0 - x @ x.T).astype(np.float64)
    np.fill_diagonal(d, np.inf)
    rep = list(range(n))
    active = np.ones(n, dtype=bool)
    for _ in range(max(0, n - k)):
        best, bi, bj = np.inf, -1, -1
        idx = np.flatnonzero(active)
        for a, i in enumerate(idx):
            row = d[i, idx[a + 1:]]
            if len(row) and row.min() < best:
                j = int(np.argmin(row))
                best, bi, bj = row[j], i, idx[a + 1 + j]
        if bi < 0:
            break
        m = np.maximum(d[bi], d[bj])
        d[bi], d[:, bi] = m, m
        d[bi, bi] = np.inf
        active[bj] = False
        rep = [bi if r == bj else r for r in rep]
    return rep


def _cluster_centroids(units, dur, cfg: Config):
    """Центроиды спикеров по кластерам длинных предложений. units: [(lo, hi, emb, sec, label)].
    Кластеров столько, сколько крупных спикеров у диаризации; имена — метки диаризации, жадно по
    наибольшей общей длительности. None — кластеризовать нечего или кластеры не сходятся с диаризацией
    по объёму (обычно выделен редкий третий голос, а два основных слиты — ru_06: DER 18 → 45%)."""
    total = sum(dur.values()) or 1.0
    major = sorted(k for k in dur if dur[k] / total >= cfg.sent_major)
    # предложения мелких спикеров в кластеризацию не берём: их голос размывает кластеры основных
    pool = sorted((u for u in units if u[3] >= cfg.sent_cluster_min and u[4] in major),
                  key=lambda u: -u[3])[:cfg.sent_cluster_max]
    if len(major) < 2 or len(pool) <= len(major):
        return None
    rep = _ahc_complete(np.stack([u[2] for u in pool]), len(major))
    keys = sorted({u[4] for u in pool} | set(major))
    reps = sorted(set(rep))
    ov = {}
    for u, r in zip(pool, rep):
        ov[(r, u[4])] = ov.get((r, u[4]), 0.0) + u[3]
    pairs = sorted(((ov.get((r, k), 0.0), r, k) for r in reps for k in keys), key=lambda t: (-t[0], t[1], t[2]))
    name, used = {}, set()
    for _, r, k in pairs:
        if r not in name and k not in used:
            name[r] = k
            used.add(k)
    cl, lab = {}, {}
    for u, r in zip(pool, rep):
        cl[name[r]] = cl.get(name[r], 0.0) + u[3]
        lab[u[4]] = lab.get(u[4], 0.0) + u[3]
    if min((cl[k] / lab[k] for k in cl if k in lab), default=1.0) < cfg.sent_cluster_balance:
        return None
    acc = {}
    for u, r in zip(pool, rep):
        acc[name[r]] = acc.get(name[r], 0.0) + u[2] * u[3]
    return {k: v / (np.linalg.norm(v) + 1e-9) for k, v in acc.items()}


def relabel_sentences(pcm, words: list[Word], segs, cfg: Config) -> None:
    """Предложение целиком — спикеру с ближайшим по CAM++ центроидом, если перевес ≥ sent_margin.

    На быстром диалоге диаризация склеивает реплики разных людей в один сегмент. Центроиды берём
    из кластеризации самих предложений (каждое сказал один человек — чище, чем окна диаризации),
    если не вышло — по сегментам диаризации. 1:1 с SentenceRelabeler.
    """
    if cfg.sent_min <= 0:
        return
    dur: dict[int, float] = {}
    for s, e, k in segs:
        dur[k] = dur.get(k, 0.0) + e - s
    if len(dur) < 2:
        return
    ranges = [(lo, hi) for lo, hi in sentences(words) if words[hi - 1].end - words[lo].start >= cfg.sent_min]
    if not ranges:
        return
    ext = _extractor(cfg)
    units = []
    for lo, hi in ranges:
        a, b = int(words[lo].start * SR), int(words[hi - 1].end * SR)
        st = ext.create_stream()
        st.accept_waveform(SR, pcm[max(0, a):max(0, b)])
        st.input_finished()
        if not ext.is_ready(st):
            continue
        v = np.array(ext.compute(st))
        units.append((lo, hi, v / (np.linalg.norm(v) + 1e-9), words[hi - 1].end - words[lo].start,
                      main_speaker(words, lo, hi)))
    cen = _cluster_centroids(units, dur, cfg)
    if cen is None:
        cen, _ = speaker_embeddings(pcm, segs, cfg)
    if len(cen) < 2:
        return
    total = sum(dur.values())
    targets = sorted(k for k in cen if dur.get(k, 0.0) / total >= cfg.sent_major) or sorted(cen)
    new = []
    # мелкий спикер без центроида кластера: если его голос похож на кого-то из основных — это обрывок
    # (диаризация отрезала кусок человека), его предложения переносим; иначе живой человек — не трогаем
    acc = {}
    for _, _, v, sec, cur in units:
        if cur not in cen:
            acc[cur] = acc.get(cur, 0.0) + v * sec
    frag = {k for k, v in acc.items()
            if max(float(c @ (v / (np.linalg.norm(v) + 1e-9))) for c in cen.values()) >= cfg.sent_fragment_sim}
    for lo, hi, v, _, cur in units:
        if cur not in cen and cur not in frag:
            continue
        best = max(targets, key=lambda k: float(cen[k] @ v))
        cur_sim = float(cen[cur] @ v) if cur in cen else -1.0
        if best != cur and float(cen[best] @ v) - cur_sim >= cfg.sent_margin:
            new.append((lo, hi, best))
    for lo, hi, k in new:
        for w in words[lo:hi]:
            w.spk = k


def build_utterances(words: list[Word], split_gap: float, sent_gap: float = 1e9, extra=()) -> list[dict]:
    """extra — реплики-поддакивания из extract_backchannels, встают по времени начала. 1:1 с WordMerger."""
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
    if extra:
        utts += [list(x) for x in extra if x]
        utts.sort(key=lambda u: u[0].start)
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
        d = "lid/whisper-tiny" if (ASSETS / "lid/whisper-tiny").exists() else "asr/sherpa-onnx-whisper-tiny"
        _LID = sherpa_onnx.SpokenLanguageIdentification(sherpa_onnx.SpokenLanguageIdentificationConfig(
            whisper=sherpa_onnx.SpokenLanguageIdentificationWhisperConfig(
                encoder=_m(d, "tiny-encoder.int8.onnx"), decoder=_m(d, "tiny-decoder.int8.onnx")),
            num_threads=threads))
    speech = np.concatenate([pcm[s:e] for s, e in chunks]) if chunks else pcm

    def lid(x):
        st = _LID.create_stream()
        st.accept_waveform(SR, x)
        return _LID.compute(st)

    if lid(speech[: 29 * SR]) != "en":
        return "ru"
    # Русский в приоритете, а ошибка «ru → en» губит весь транскрипт: на реверберации whisper-tiny
    # иногда слышит в русском английский. Подтверждаем «en» голосованием по 5 VAD-чанкам,
    # равномерно взятым по записи (≥ 2 с каждый): нужно большинство «en».
    long = [c for c in chunks if c[1] - c[0] >= 2 * SR]
    if len(long) < 3:
        return "en"
    pick = [long[round(k * (len(long) - 1) / 4)] for k in range(5)] if len(long) >= 5 else long
    votes = sum(lid(pcm[s:e]) == "en" for s, e in pick)
    return "en" if votes * 2 > len(pick) else "ru"


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
    extra = []
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
        assign_backchannels(words, segs)
        relabel_sentences(pcm, words, segs, cfg)
        words, extra = extract_backchannels(words, cfg.bc_max)
        smooth_speakers(words, cfg.smooth_run)
    utts = build_utterances(words, cfg.split_gap, cfg.sent_gap, extra)
    t["merge"] = time.perf_counter() - t0
    return Result(utts, t, words, segs, lang)
