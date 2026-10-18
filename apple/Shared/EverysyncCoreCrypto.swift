// EverySync Apple shells — CryptoKit AES-GCM bridge (fills the Rust AES gap).
//
// The shared Rust crate (`e2ee_pqc`) has NO AES entry points — AES-GCM lives
// in Kotlin/Swift on every target (JCE on Android/JVM, CryptoKit here).
// Do NOT add AES to Rust.
//
// Layout contract (byte-identical to the JCE `AesGcm` actual + `Crypto.kt`):
//   encrypt output = [12B random nonce] + [ciphertext] + [16B tag]
//   (CryptoKit `AES.GCM.SealedBox.combined` layout is
//   [nonce][ciphertext][tag] — the SAME bytes. Pass `combined` through
//   verbatim; no re-framing.)
// Key size: 256-bit (32B), from `AesGcm.newContentKey` (Kotlin side, backed
// by `SecRandomCopyBytes` per `IOS_ACTUALS_NOTES.md`).
//
// Wiring: the Kotlin `AesGcm` actual calls these C functions via cinterop
// (see `../XCFramework/e2eePqc.def` — same module as the 10 PQC fns).
// Swift never calls Rust directly — only via the shared XCFramework, and the
// XCFramework never calls Swift except through this C bridge.
//
// MAC-CHECK (needs xcodebuild on a Mac):
// - `CryptoKit` import + `AES.GCM` availability (iOS 13+; deployment target
//   must be >= 13 — set in the Xcode project on the Mac).
// - `@_cdecl` symbol names match the cinterop header exactly.

import CryptoKit
import Foundation

/// Encrypts `plaintext` under 256-bit `key`. Returns
/// `[12B nonce][ciphertext][16B tag]`, or nil on any failure (caller treats
/// nil as a crypto failure, never as empty plaintext).
@_cdecl("everysync_aes_gcm_encrypt")
public func everysyncAESGCMEncrypt(
    keyPtr: UnsafePointer<UInt8>?, keyLen: Int,
    ptPtr: UnsafePointer<UInt8>?, ptLen: Int,
    outPtr: UnsafeMutablePointer<UnsafeMutablePointer<UInt8>?>?,
    outLen: UnsafeMutablePointer<Int>?
) -> Int32 {
    guard let keyPtr, let ptPtr, let outPtr, let outLen,
          keyLen == 32, ptLen >= 0 else { return 0 }
    do {
        let key = SymmetricKey(data: Data(bytes: keyPtr, count: keyLen))
        let sealed = try AES.GCM.seal(Data(bytes: ptPtr, count: ptLen), using: key)
        guard let combined = sealed.combined else { return 0 }
        let bytes = [UInt8](combined)
        let out = UnsafeMutablePointer<UInt8>.allocate(capacity: bytes.count)
        out.initialize(from: bytes, count: bytes.count)
        outPtr.pointee = out
        outLen.pointee = bytes.count
        return 1
    } catch {
        return 0
    }
}

/// Decrypts `combined` ([nonce][ciphertext][tag]) under 256-bit `key`.
/// Returns plaintext bytes, or nil on any failure (wrong key, tampered tag,
/// malformed input). Callers (relay envelope pulls) count undecryptables and
/// keep the cycle — they never fold a crypto failure as an empty log.
@_cdecl("everysync_aes_gcm_decrypt")
public func everysyncAESGCMDecrypt(
    keyPtr: UnsafePointer<UInt8>?, keyLen: Int,
    ctPtr: UnsafePointer<UInt8>?, ctLen: Int,
    outPtr: UnsafeMutablePointer<UnsafeMutablePointer<UInt8>?>?,
    outLen: UnsafeMutablePointer<Int>?
) -> Int32 {
    guard let keyPtr, let ctPtr, let outPtr, let outLen,
          keyLen == 32, ctLen > 12 + 16 else { return 0 }
    do {
        let key = SymmetricKey(data: Data(bytes: keyPtr, count: keyLen))
        let box = try AES.GCM.SealedBox(combined: Data(bytes: ctPtr, count: ctLen))
        let plain = try AES.GCM.open(box, using: key)
        let bytes = [UInt8](plain)
        let out = UnsafeMutablePointer<UInt8>.allocate(capacity: bytes.count)
        out.initialize(from: bytes, count: bytes.count)
        outPtr.pointee = out
        outLen.pointee = bytes.count
        return 1
    } catch {
        return 0
    }
}

/// Frees a buffer returned by the two functions above. The Kotlin cinterop
/// actual MUST copy the bytes first, then call this (same discipline as
/// Rust `pqc_free`: every `*mut u8 + len` out-param buffer is caller-freed).
@_cdecl("everysync_crypto_free")
public func everysyncCryptoFree(ptr: UnsafeMutablePointer<UInt8>?, len: Int) {
    guard let ptr, len > 0 else { return }
    ptr.deinitialize(count: len)
    ptr.deallocate()
}
