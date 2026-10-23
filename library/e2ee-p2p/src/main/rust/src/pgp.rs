//! OpenPGP (RFC 9580) for SecureMail, via rPGP.
//!
//! Suite: Ed25519 signing primary + X25519 encryption subkey (the same suite
//! the app previously generated with BouncyCastle), AES-256 session keys,
//! SHA-512 digests. Keys cross the JNI boundary as ASCII armor; ciphertext
//! crosses as raw PGP/MIME bytes (armored only for SMTP federation, which the
//! app does with [`to_armored_message`]).
//!
//! The relay server never sees plaintext or private keys — it routes opaque
//! blobs by recipient address, exactly like the FindFamily relay routes opaque
//! ciphertext by userid.

use pgp::armor::Dearmor;
use pgp::composed::{
    ArmorOptions, Deserializable, EncryptionCaps, KeyType, Message, MessageBuilder,
    SecretKeyParamsBuilder, SignedPublicKey, SignedSecretKey, SubkeyParamsBuilder,
};
use pgp::crypto::hash::HashAlgorithm;
use pgp::crypto::sym::SymmetricKeyAlgorithm;
use pgp::types::{KeyDetails, Password};
use rand08::rngs::OsRng;

/// Generate an Ed25519 primary + X25519 encryption subkey for `user_id`.
/// Returns (armoredSecret, armoredPublic, fingerprintHex).
pub fn pgp_generate(user_id: &str, passphrase: &str) -> Option<(String, String, String)> {
    let mut rng = OsRng;
    let mut params = SecretKeyParamsBuilder::default();
    params
        .key_type(KeyType::Ed25519)
        .can_certify(false)
        .can_sign(true)
        .primary_user_id(user_id.to_string())
        .passphrase(Some(passphrase.to_string()))
        .preferred_symmetric_algorithms(smallvec::smallvec![
            SymmetricKeyAlgorithm::AES256,
        ])
        .preferred_hash_algorithms(smallvec::smallvec![HashAlgorithm::Sha512])
        .preferred_compression_algorithms(smallvec::smallvec![])
        .subkeys(vec![SubkeyParamsBuilder::default()
            .key_type(KeyType::X25519)
            .can_encrypt(EncryptionCaps::All)
            .passphrase(Some(passphrase.to_string()))
            .build()
            .ok()?]);
    let secret = params.build().ok()?.generate(&mut rng).ok()?;
    let public = secret.to_public_key();
    let fingerprint = hex_upper(&secret.fingerprint().as_bytes());
    Some((
        secret.to_armored_string(ArmorOptions::default()).ok()?,
        public.to_armored_string(ArmorOptions::default()).ok()?,
        fingerprint,
    ))
}

/// Fingerprint (uppercase hex, no spaces) of an armored public key.
pub fn pgp_fingerprint(armored_public: &str) -> Option<String> {
    let (key, _) = SignedPublicKey::from_string(armored_public).ok()?;
    Some(hex_upper(&key.fingerprint().as_bytes()))
}

/// Normalize a public key (binary WKD record or armor) to canonical armor.
/// Returns None when the input is not a parseable OpenPGP public key.
pub fn pgp_normalize_key(input: &[u8]) -> Option<String> {
    use pgp::composed::Deserializable as _;
    let key = if is_probably_armor(input) {
        let text = std::str::from_utf8(input).ok()?;
        let (key, _) = SignedPublicKey::from_string(text).ok()?;
        key
    } else {
        SignedPublicKey::from_bytes(input).ok()?
    };
    key.to_armored_string(ArmorOptions::default()).ok()
}

fn is_probably_armor(input: &[u8]) -> bool {
    input.starts_with(b"-----BEGIN PGP")
}

/// Encrypt `plaintext` to one recipient's armored public key. Returns raw
/// (unarmored) PGP bytes for the relay wire.
pub fn pgp_encrypt(armored_public: &str, plaintext: &[u8]) -> Option<Vec<u8>> {
    let mut rng = OsRng;
    let (public, _) = SignedPublicKey::from_string(armored_public).ok()?;
    // Encrypt to the first subkey (our keys always carry the X25519
    // encryption subkey; a foreign key without one cannot be encrypted to).
    // Matches the documented `encrypt_to_key(&rng, &public_subkeys[0])` shape.
    let subkey = public.public_subkeys.first()?;
    let mut builder = MessageBuilder::from_bytes("message.txt", plaintext.to_vec())
        .seipd_v1(&mut rng, SymmetricKeyAlgorithm::AES256);
    builder.encrypt_to_key(&mut rng, subkey).ok()?;
    builder.to_vec(&mut rng).ok()
}

/// Decrypt raw PGP bytes with an armored secret key + passphrase. Returns the
/// literal payload. Signature verification is a separate pass (see below).
pub fn pgp_decrypt(
    armored_secret: &str,
    passphrase: &str,
    ciphertext: &[u8],
) -> Option<Vec<u8>> {
    let (secret, _) = SignedSecretKey::from_string(armored_secret).ok()?;
    let msg = Message::from_bytes(ciphertext).ok()?;
    let mut decrypted = msg
        .decrypt(&Password::from(passphrase), &secret)
        .ok()?;
    decrypted.as_data_vec().ok()
}

/// Armor raw PGP bytes as a `PGP MESSAGE` block (for SMTP federation).
pub fn to_armored_message(raw: &[u8]) -> Option<String> {
    use base64::Engine;
    // RFC 9580 §6 armor framing: header, base64 body at 64-col, CRC24 footer.
    let mut out = String::from("-----BEGIN PGP MESSAGE-----\n\n");
    let b64 = base64::engine::general_purpose::STANDARD.encode(raw);
    for chunk in b64.as_bytes().chunks(64) {
        out.push_str(std::str::from_utf8(chunk).ok()?);
        out.push('\n');
    }
    out.push('=');
    out.push_str(&base64::engine::general_purpose::STANDARD.encode(crc24(raw)));
    out.push_str("\n-----END PGP MESSAGE-----\n");
    Some(out)
}

/// OpenPGP CRC24 (RFC 9580 §6.1): polynomial 0x1864CFB, init 0xB704CE.
fn crc24(data: &[u8]) -> [u8; 3] {
    let mut crc: u32 = 0xB704CE;
    for &b in data {
        crc ^= (b as u32) << 16;
        for _ in 0..8 {
            crc <<= 1;
            if crc & 0x1000000 != 0 {
                crc ^= 0x1864CFB;
            }
        }
    }
    [(crc >> 16) as u8, (crc >> 8) as u8, crc as u8]
}

/// Dearmor any ASCII-armored input to raw bytes (key or message).
pub fn dearmor_any(armored: &str) -> Option<Vec<u8>> {
    use std::io::Read;
    let mut dearmor = Dearmor::new(armored.as_bytes());
    let mut bytes = Vec::new();
    dearmor.read_to_end(&mut bytes).ok()?;
    Some(bytes)
}

fn hex_upper(bytes: &[u8]) -> String {
    const HEX: &[u8; 16] = b"0123456789ABCDEF";
    let mut out = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        out.push(HEX[(b >> 4) as usize] as char);
        out.push(HEX[(b & 0x0f) as usize] as char);
    }
    out
}

// ---------------------------------------------------------------------------
// JNI surface for `com.vayunmathur.e2ee.PgpNative`.
// Armor crosses as UTF-8 bytes; ciphertext crosses as raw bytes.
// ---------------------------------------------------------------------------

use jni::objects::{JByteArray, JClass};
use jni::sys::{jbyteArray, jobjectArray};
use jni::{Env, EnvUnowned};

fn jbytes_in(env: &Env, arr: &JByteArray) -> Option<Vec<u8>> {
    env.convert_byte_array(arr).ok()
}

fn jbytes_out(env: &mut Env, data: &[u8]) -> jbyteArray {
    match env.byte_array_from_slice(data) {
        Ok(a) => a.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

fn jstring_in(env: &mut Env, arr: &JByteArray) -> Option<String> {
    let bytes = jbytes_in(env, arr)?;
    String::from_utf8(bytes).ok()
}

fn jstring_out(env: &mut Env, s: &str) -> jbyteArray {
    jbytes_out(env, s.as_bytes())
}

/// Return a Java `byte[][]` of exactly three elements (secret, public,
/// fingerprint), or null.
fn triple_out(env: &mut Env, a: &str, b: &str, c: &str) -> jobjectArray {
    let arr = match env.new_object_type_array::<JByteArray>(3, JByteArray::null()) {
        Ok(a) => a,
        Err(_) => return std::ptr::null_mut(),
    };
    let items = [a.as_bytes(), b.as_bytes(), c.as_bytes()];
    for (i, data) in items.iter().enumerate() {
        let element = match env.byte_array_from_slice(data) {
            Ok(x) => x,
            Err(_) => return std::ptr::null_mut(),
        };
        if arr.set_element(env, i, &element).is_err() {
            return std::ptr::null_mut();
        }
    }
    arr.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_e2ee_PgpNative_nativePgpGenerate<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    user_id: JByteArray<'l>,
    passphrase: JByteArray<'l>,
) -> jobjectArray {
    env.with_env(|env| -> jni::errors::Result<jobjectArray> {
        let (uid, pw) = match (jstring_in(env, &user_id), jstring_in(env, &passphrase)) {
            (Some(a), Some(b)) => (a, b),
            _ => return Ok(std::ptr::null_mut()),
        };
        match pgp_generate(&uid, &pw) {
            Some((secret, public, fp)) => Ok(triple_out(env, &secret, &public, &fp)),
            None => Ok(std::ptr::null_mut()),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_e2ee_PgpNative_nativePgpFingerprint<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    armored_public: JByteArray<'l>,
) -> jbyteArray {
    env.with_env(|env| -> jni::errors::Result<jbyteArray> {
        let armor = match jstring_in(env, &armored_public) {
            Some(a) => a,
            None => return Ok(std::ptr::null_mut()),
        };
        match pgp_fingerprint(&armor) {
            Some(fp) => Ok(jstring_out(env, &fp)),
            None => Ok(std::ptr::null_mut()),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_e2ee_PgpNative_nativePgpNormalizeKey<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    input: JByteArray<'l>,
) -> jbyteArray {
    env.with_env(|env| -> jni::errors::Result<jbyteArray> {
        let bytes = match jbytes_in(env, &input) {
            Some(b) => b,
            None => return Ok(std::ptr::null_mut()),
        };
        match pgp_normalize_key(&bytes) {
            Some(armor) => Ok(jstring_out(env, &armor)),
            None => Ok(std::ptr::null_mut()),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_e2ee_PgpNative_nativePgpEncrypt<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    armored_public: JByteArray<'l>,
    plaintext: JByteArray<'l>,
) -> jbyteArray {
    env.with_env(|env| -> jni::errors::Result<jbyteArray> {
        let (armor, plain) = match (
            jstring_in(env, &armored_public),
            jbytes_in(env, &plaintext),
        ) {
            (Some(a), Some(b)) => (a, b),
            _ => return Ok(std::ptr::null_mut()),
        };
        match pgp_encrypt(&armor, &plain) {
            Some(raw) => Ok(jbytes_out(env, &raw)),
            None => Ok(std::ptr::null_mut()),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_e2ee_PgpNative_nativePgpDecrypt<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    armored_secret: JByteArray<'l>,
    passphrase: JByteArray<'l>,
    ciphertext: JByteArray<'l>,
) -> jbyteArray {
    env.with_env(|env| -> jni::errors::Result<jbyteArray> {
        let (armor, pw, ct) = match (
            jstring_in(env, &armored_secret),
            jstring_in(env, &passphrase),
            jbytes_in(env, &ciphertext),
        ) {
            (Some(a), Some(b), Some(c)) => (a, b, c),
            _ => return Ok(std::ptr::null_mut()),
        };
        match pgp_decrypt(&armor, &pw, &ct) {
            Some(plain) => Ok(jbytes_out(env, &plain)),
            None => Ok(std::ptr::null_mut()),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_e2ee_PgpNative_nativePgpArmorMessage<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    raw: JByteArray<'l>,
) -> jbyteArray {
    env.with_env(|env| -> jni::errors::Result<jbyteArray> {
        let bytes = match jbytes_in(env, &raw) {
            Some(b) => b,
            None => return Ok(std::ptr::null_mut()),
        };
        match to_armored_message(&bytes) {
            Some(armor) => Ok(jstring_out(env, &armor)),
            None => Ok(std::ptr::null_mut()),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}
