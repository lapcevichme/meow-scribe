package com.lapcevichme.meowscribe.presentation.components

import androidx.annotation.StringRes
import com.lapcevichme.meowscribe.R
import com.lapcevichme.meowscribe.domain.pipeline.Stage

@StringRes
fun Stage.labelRes(): Int = when (this) {
    Stage.DECODE -> R.string.stage_decode
    Stage.VAD -> R.string.stage_vad
    Stage.DIARIZATION -> R.string.stage_diarization
    Stage.ASR -> R.string.stage_asr
    Stage.MERGE -> R.string.stage_merge
    Stage.LLM -> R.string.stage_llm
}
