// File-transfer JNI entry points (part of `lib.rs` via `include!`).
//
// `ShareNative` file I/O: `nativeOpenFile` / `nativeWriteChunk` /
// `nativeCloseFile` / `nativeDrainReceived`. Split out of `lib.rs` to keep
// each file under the `rustFileLength` gate; shares `lib.rs` scope
// (`with_session`, `bytes_in`, `bytes_out`, `str_in`) textually.

/// int nativeOpenFile(long handle, String fileName, long fileSize) -> 0 ok
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_share_protocol_ShareNative_nativeOpenFile<'l>(
    mut env: EnvUnowned<'l>,
    _cls: JClass<'l>,
    handle: jlong,
    jFileName: JString<'l>,
    fileSize: jlong,
) -> jint {
    env.with_env(|env| -> jni::errors::Result<jint> {
        let name = str_in(env, &jFileName).unwrap_or_default();
        if name.is_empty() {
            return Ok(-1);
        }
        Ok(with_session(handle, -1, |s| s.open_file(&name, fileSize)))
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// int nativeWriteChunk(long handle, byte[] chunk) -> 0 ok
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_share_protocol_ShareNative_nativeWriteChunk<'l>(
    mut env: EnvUnowned<'l>,
    _cls: JClass<'l>,
    handle: jlong,
    jChunk: JByteArray<'l>,
) -> jint {
    env.with_env(|env| -> jni::errors::Result<jint> {
        let bytes = match bytes_in(env, &jChunk) {
            Some(b) => b,
            None => return Ok(-1),
        };
        Ok(with_session(handle, -2, |s| s.write_chunk(&bytes)))
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// int nativeCloseFile(long handle) -> 0 ok
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_share_protocol_ShareNative_nativeCloseFile<'l>(
    _env: EnvUnowned<'l>,
    _cls: JClass<'l>,
    handle: jlong,
) -> jint {
    with_session(handle, -1, Session::close_file)
}

/// byte[] nativeDrainReceived(long handle) -> one received-chunk record, or null.
///
/// Call after every `nativeFeedInbound` until it returns null. The record layout is
/// fixed by `PROTOCOL_CONTRACT.md` §6; each call hands over one FILE `PayloadChunk`
/// and drops it, so the session never accumulates a whole file.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_share_protocol_ShareNative_nativeDrainReceived<'l>(
    mut env: EnvUnowned<'l>,
    _cls: JClass<'l>,
    handle: jlong,
) -> jbyteArray {
    env.with_env(|env| -> jni::errors::Result<jbyteArray> {
        Ok(match with_session(handle, None, Session::drain_received) {
            Some(bytes) => bytes_out(env, &bytes),
            None => std::ptr::null_mut(),
        })
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}
