package com.lapcevichme.meowscribe.data.pipeline.sherpa

/**
 * Пути к весам внутри assets (sherpa-onnx читает их напрямую через AssetManager).
 * Раскладку создаёт bench/fetch_models.sh — менять синхронно.
 */
internal object SherpaModels {
    private const val ROOT = "models"

    const val VAD = "$ROOT/vad/silero_vad.onnx"

    const val SEGMENTATION = "$ROOT/diarization/segmentation.onnx"
    const val EMBEDDING = "$ROOT/diarization/embedding.onnx"

    const val LID_ENCODER = "$ROOT/lid/tiny-encoder.int8.onnx"
    const val LID_DECODER = "$ROOT/lid/tiny-decoder.int8.onnx"

    /** GigaAM v3 RNNT с пунктуацией и нормализацией (русский). */
    object Ru {
        const val ENCODER = "$ROOT/asr/ru/encoder.int8.onnx"
        const val DECODER = "$ROOT/asr/ru/decoder.onnx"
        const val JOINER = "$ROOT/asr/ru/joiner.onnx"
        const val TOKENS = "$ROOT/asr/ru/tokens.txt"
    }

    /** Parakeet TDT 110M (английский, с пунктуацией и регистром). */
    object En {
        const val ENCODER = "$ROOT/asr/en/encoder.int8.onnx"
        const val DECODER = "$ROOT/asr/en/decoder.int8.onnx"
        const val JOINER = "$ROOT/asr/en/joiner.int8.onnx"
        const val TOKENS = "$ROOT/asr/en/tokens.txt"
    }
}
