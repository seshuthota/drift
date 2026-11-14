pub mod asr;

use asr::WhisperEngine;
use dummy;
use jni::objects::{JClass, JShortArray, JString};
use jni::sys::{jint, jlong, jstring};
use jni::JNIEnv;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::path::Path;

#[no_mangle]
pub extern "system" fn Java_io_github_seshuthota_drift_libnative_TestKt_dummyAdd(
    _env: JNIEnv,
    _class: JClass,
    a: jint,
    b: jint,
) -> jint {
    dummy::addnumbers(a, b)
}

/// `WhisperNative.nativeLoad(modelPath): Long` — returns an opaque engine handle, or throws IllegalStateException.
#[no_mangle]
pub extern "system" fn Java_io_github_seshuthota_drift_ime_asr_WhisperNative_nativeLoad(
    mut env: JNIEnv,
    _class: JClass,
    model_path: JString,
) -> jlong {
    let result = catch_unwind(AssertUnwindSafe(|| -> Result<WhisperEngine, String> {
        let path = string_arg(&mut env, &model_path)?;
        WhisperEngine::load(Path::new(&path))
    }));
    match result {
        Ok(Ok(engine)) => Box::into_raw(Box::new(engine)) as jlong,
        Ok(Err(message)) => {
            throw(&mut env, &message);
            0
        }
        Err(_) => {
            throw(&mut env, "whisper.cpp panicked while loading the model");
            0
        }
    }
}

/// `WhisperNative.nativeTranscribe(handle, pcm, languageTag): String?` — throws IllegalStateException on failure.
#[no_mangle]
pub extern "system" fn Java_io_github_seshuthota_drift_ime_asr_WhisperNative_nativeTranscribe(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    samples: JShortArray,
    language: JString,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| -> Result<String, String> {
        if handle == 0 {
            return Err("whisper model is not loaded".to_string());
        }
        let language = string_arg(&mut env, &language)?;
        let language = if language.is_empty() { None } else { Some(language) };
        let length = env
            .get_array_length(&samples)
            .map_err(|e| format!("cannot read sample count: {e}"))? as usize;
        let mut pcm = vec![0i16; length];
        env.get_short_array_region(&samples, 0, &mut pcm)
            .map_err(|e| format!("cannot read samples: {e}"))?;
        let engine = unsafe { &*(handle as *const WhisperEngine) };
        engine.transcribe(&pcm, language.as_deref())
    }));
    match result {
        Ok(Ok(text)) => match env.new_string(text) {
            Ok(text) => text.into_raw(),
            Err(e) => {
                throw(&mut env, &format!("cannot create result string: {e}"));
                std::ptr::null_mut()
            }
        },
        Ok(Err(message)) => {
            throw(&mut env, &message);
            std::ptr::null_mut()
        }
        Err(_) => {
            throw(&mut env, "whisper.cpp panicked while transcribing");
            std::ptr::null_mut()
        }
    }
}

/// `WhisperNative.nativeFree(handle)` — releases the engine. Safe to call with 0.
#[no_mangle]
pub extern "system" fn Java_io_github_seshuthota_drift_ime_asr_WhisperNative_nativeFree(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    let _ = catch_unwind(AssertUnwindSafe(|| unsafe {
        drop(Box::from_raw(handle as *mut WhisperEngine));
    }));
}

fn string_arg(env: &mut JNIEnv, value: &JString) -> Result<String, String> {
    env.get_string(value)
        .map(|value| value.to_string_lossy().into_owned())
        .map_err(|e| format!("cannot read string argument: {e}"))
}

fn throw(env: &mut JNIEnv, message: &str) {
    let _ = env.throw_new("java/lang/IllegalStateException", message);
}
