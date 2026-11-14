// Whisper speech-to-text behind the JNI surface in lib.rs.
//
// The engine is intentionally tiny: load once, transcribe 16 kHz mono PCM16, drop. Any whisper.cpp feature we
// later want (VAD, timestamps, translation) belongs here so the Kotlin side stays a thin transport.

use std::path::Path;
use whisper_rs::{FullParams, SamplingStrategy, WhisperContext, WhisperContextParameters};

const MAX_PROMPT_TOKENS: usize = 128;
// Silence is already removed before audio gets here, so only drop segments Whisper is very sure are not speech;
// Tiny rates a lone short word as likely non-speech.
const NO_SPEECH_THRESHOLD: f32 = 0.8;

pub struct WhisperEngine {
    context: WhisperContext,
}

impl WhisperEngine {
    pub fn load(model_path: &Path) -> Result<Self, String> {
        if !model_path.is_file() {
            return Err(format!("model file not found: {}", model_path.display()));
        }
        let context = WhisperContext::new_with_params(model_path, WhisperContextParameters::default())
            .map_err(|e| format!("cannot load {}: {e}", model_path.display()))?;
        Ok(Self { context })
    }

    /// Transcribes [pcm] (mono, 16 kHz, signed 16-bit). [language] is a Whisper language code, or None to let a
    /// multilingual model detect it. [prompt] is text that came right before this audio (may be empty); it keeps
    /// spelling and casing consistent across pieces of one dictation. [partial] marks a live preview that is
    /// replaced later, decoded as a single segment.
    pub fn transcribe(
        &self,
        pcm: &[i16],
        language: Option<&str>,
        prompt: &str,
        partial: bool,
    ) -> Result<String, String> {
        if pcm.is_empty() {
            return Err("no audio samples".to_string());
        }
        let samples: Vec<f32> = pcm.iter().map(|sample| *sample as f32 / 32768.0).collect();
        let mut state = self
            .context
            .create_state()
            .map_err(|e| format!("cannot create whisper state: {e}"))?;
        let threads = std::thread::available_parallelism()
            .map(|count| count.get() as i32)
            .unwrap_or(2)
            // More threads than big cores only adds sync overhead on big.LITTLE phones.
            .clamp(1, 4);
        let mut params = FullParams::new(SamplingStrategy::Greedy { best_of: 1 });
        params.set_n_threads(threads);
        params.set_translate(false);
        params.set_no_context(true);
        params.set_suppress_blank(true);
        params.set_print_special(false);
        params.set_print_progress(false);
        params.set_print_realtime(false);
        params.set_print_timestamps(false);
        if partial {
            // A shortened audio_ctx would be faster, but Whisper Tiny then hallucinates whole sentences.
            params.set_single_segment(true);
        }
        // whisper.cpp defaults to "en"; without "auto" a multilingual model would force everything into English.
        params.set_language(Some(language.unwrap_or("auto")));
        let prompt_tokens = if prompt.is_empty() {
            Vec::new()
        } else {
            self.context.tokenize(prompt, MAX_PROMPT_TOKENS).unwrap_or_default()
        };
        if !prompt_tokens.is_empty() {
            params.set_tokens(&prompt_tokens);
        }
        state
            .full(params, &samples)
            .map_err(|e| format!("whisper_full failed: {e}"))?;
        let mut text = String::new();
        for segment in state.as_iter() {
            // Silence and noise often decode as a confident-looking phrase ("Thank you."); drop those.
            if segment.no_speech_probability() > NO_SPEECH_THRESHOLD {
                continue;
            }
            text.push_str(
                &segment
                    .to_str_lossy()
                    .map_err(|e| format!("cannot decode segment text: {e}"))?,
            );
        }
        Ok(text.trim().to_string())
    }
}
