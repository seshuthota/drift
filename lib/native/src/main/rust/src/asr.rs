// Whisper speech-to-text behind the JNI surface in lib.rs.
//
// The engine is intentionally tiny: load once, transcribe 16 kHz mono PCM16, drop. Any whisper.cpp feature we
// later want (VAD, timestamps, translation) belongs here so the Kotlin side stays a thin transport.

use std::path::Path;
use whisper_rs::{FullParams, SamplingStrategy, WhisperContext, WhisperContextParameters};

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
    /// multilingual model detect it.
    pub fn transcribe(&self, pcm: &[i16], language: Option<&str>) -> Result<String, String> {
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
            .clamp(1, 6);
        let mut params = FullParams::new(SamplingStrategy::Greedy { best_of: 1 });
        params.set_n_threads(threads);
        params.set_translate(false);
        params.set_no_context(true);
        params.set_suppress_blank(true);
        params.set_print_special(false);
        params.set_print_progress(false);
        params.set_print_realtime(false);
        params.set_print_timestamps(false);
        if let Some(language) = language {
            params.set_language(Some(language));
        }
        state
            .full(params, &samples)
            .map_err(|e| format!("whisper_full failed: {e}"))?;
        let mut text = String::new();
        for segment in state.as_iter() {
            text.push_str(
                &segment
                    .to_str_lossy()
                    .map_err(|e| format!("cannot decode segment text: {e}"))?,
            );
        }
        Ok(text.trim().to_string())
    }
}
