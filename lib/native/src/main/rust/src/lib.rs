use dummy;

use jni::objects::JClass;
use jni::sys::jint;
use std::os::raw::c_int;
use jni::JNIEnv;

#[no_mangle]
pub extern "system" fn Java_io_github_seshuthota_drift_libnative_TestKt_dummyAdd(
    _env: JNIEnv,
    _class: JClass,
    a: jint,
    b: jint,
) -> jint {
    dummy::addnumbers(a, b)
}

extern "C" {
    fn mallopt(param: c_int, value: c_int) -> c_int;
}

/// bionic's `M_PURGE` (API 28+): release the allocator's free pages to the kernel. Unknown to older bionic, where
/// mallopt simply returns 0.
const M_PURGE: c_int = -101;

#[no_mangle]
pub extern "system" fn Java_io_github_seshuthota_drift_libnative_MemoryKt_nativePurgeMemory(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    unsafe { mallopt(M_PURGE, 0) }
}
