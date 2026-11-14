// Desktop harness for the Whisper engine: `cargo run --release --example transcribe -- <model.bin> <audio.wav>`.
// Android never runs this; it exists so the decoder can be validated on a host before a device build.

use drift_native_rust::asr::WhisperEngine;
use std::path::Path;

fn main() {
    let mut args = std::env::args().skip(1);
    let model = args.next().unwrap_or_else(|| {
        eprintln!("usage: transcribe <model.bin> <audio.wav> [language]");
        std::process::exit(2);
    });
    let audio = args.next().unwrap_or_else(|| {
        eprintln!("usage: transcribe <model.bin> <audio.wav> [language]");
        std::process::exit(2);
    });
    let language = args.next();
    let pcm = match read_wav(&audio) {
        Ok(pcm) => pcm,
        Err(message) => {
            eprintln!("{message}");
            std::process::exit(1);
        }
    };
    let engine = match WhisperEngine::load(Path::new(&model)) {
        Ok(engine) => engine,
        Err(message) => {
            eprintln!("{message}");
            std::process::exit(1);
        }
    };
    match engine.transcribe(&pcm, language.as_deref()) {
        Ok(text) => println!("{text}"),
        Err(message) => {
            eprintln!("{message}");
            std::process::exit(1);
        }
    }
}

fn read_wav(path: &str) -> Result<Vec<i16>, String> {
    let mut reader = hound::WavReader::open(path).map_err(|e| format!("cannot open {path}: {e}"))?;
    let spec = reader.spec();
    if spec.channels != 1 {
        return Err(format!("expected mono audio, got {} channels", spec.channels));
    }
    if spec.sample_rate != 16_000 {
        return Err(format!("expected 16000 Hz audio, got {} Hz", spec.sample_rate));
    }
    match spec.sample_format {
        hound::SampleFormat::Int if spec.bits_per_sample == 16 => reader
            .samples::<i16>()
            .collect::<Result<Vec<_>, _>>()
            .map_err(|e| format!("cannot read samples: {e}")),
        other => Err(format!("expected 16-bit PCM, got {other:?}/{} bits", spec.bits_per_sample)),
    }
}
