//! OpenPGP round-trip tests: generate → armor → encrypt → decrypt.
//! No external vectors: keys are randomized per run (keygen uses OS entropy),
//! so these prove self-consistency of the rPGP pipeline the app ships.

use super::pgp::*;

#[test]
fn generate_emits_armor_and_fingerprint() {
    let (secret, public, fp) = pgp_generate("alice <alice@maemail.org>", "correct horse")
        .expect("keygen");
    assert!(secret.contains("-----BEGIN PGP PRIVATE KEY BLOCK-----"), "secret armor");
    assert!(public.contains("-----BEGIN PGP PUBLIC KEY BLOCK-----"), "public armor");
    assert_eq!(fp.len(), 40, "v4 fingerprint is 20 bytes hex");
    assert!(fp.bytes().all(|b| b.is_ascii_hexdigit()), "fingerprint hex");
    assert_eq!(fp, fp.to_uppercase(), "fingerprint uppercase");
}

#[test]
fn fingerprint_is_stable_and_address_independent() {
    let (_, public_a, fp_a) = pgp_generate("alice <alice@maemail.org>", "pw12345678")
        .expect("keygen a");
    assert_eq!(Some(fp_a.clone()), pgp_fingerprint(&public_a));
    let (_, public_b, fp_b) = pgp_generate("bob <bob@maemail.org>", "pw12345678")
        .expect("keygen b");
    assert_ne!(fp_a, fp_b, "distinct keys have distinct fingerprints");
    assert!(pgp_fingerprint(&public_b).is_some());
}

#[test]
fn encrypt_decrypt_roundtrip() {
    let (secret, public, _) = pgp_generate("alice <alice@maemail.org>", "pw12345678")
        .expect("keygen");
    let plaintext = b"Hello from SecureMail";
    let raw = pgp_encrypt(&public, plaintext).expect("encrypt");
    assert!(!raw.is_empty(), "ciphertext non-empty");
    assert_ne!(raw.as_slice(), plaintext.as_slice(), "ciphertext differs");
    let back = pgp_decrypt(&secret, "pw12345678", &raw).expect("decrypt");
    assert_eq!(back, plaintext, "round-trip plaintext");
}

#[test]
fn wrong_passphrase_fails_decrypt() {
    let (secret, public, _) = pgp_generate("alice <alice@maemail.org>", "pw12345678")
        .expect("keygen");
    let raw = pgp_encrypt(&public, b"secret").expect("encrypt");
    assert!(
        pgp_decrypt(&secret, "wrong passphrase", &raw).is_none(),
        "wrong passphrase must fail"
    );
}

#[test]
fn malformed_inputs_fail_cleanly() {
    assert!(pgp_fingerprint("not armor at all").is_none());
    assert!(pgp_encrypt("not armor at all", b"x").is_none());
    assert!(pgp_decrypt("not armor at all", "pw", b"x").is_none());
}

#[test]
fn armor_message_roundtrip() {
    let (_, public, _) = pgp_generate("alice <alice@maemail.org>", "pw12345678")
        .expect("keygen");
    let raw = pgp_encrypt(&public, b"federation body").expect("encrypt");
    let armored = to_armored_message(&raw).expect("armor");
    assert!(
        armored.starts_with("-----BEGIN PGP MESSAGE-----"),
        "message armor header"
    );
    assert!(armored.contains("-----END PGP MESSAGE-----"), "message armor footer");
    let back = dearmor_any(&armored).expect("dearmor");
    assert_eq!(back, raw, "armor round-trips raw bytes");
}

#[test]
fn normalize_accepts_binary_and_armor() {
    use pgp::composed::{Deserializable, SignedPublicKey};
    let (_, public, fp) = pgp_generate("alice <alice@maemail.org>", "pw12345678")
        .expect("keygen");
    // Armor in → canonical armor out with the same fingerprint.
    let normalized = pgp_normalize_key(public.as_bytes()).expect("normalize armor");
    assert!(normalized.contains("-----BEGIN PGP PUBLIC KEY BLOCK-----"));
    let (reparsed, _) = SignedPublicKey::from_string(&normalized).expect("reparse");
    assert_eq!(fingerprint_of(&reparsed), fp);
    // Binary (WKD-style record) in → armor out.
    let binary = dearmor_any(&public).expect("dearmor for binary form");
    assert!(!binary.starts_with(b"-----BEGIN"));
    let normalized_bin = pgp_normalize_key(&binary).expect("normalize binary");
    assert!(normalized_bin.contains("-----BEGIN PGP PUBLIC KEY BLOCK-----"));
    // Garbage fails cleanly.
    assert!(pgp_normalize_key(b"not a key").is_none());
    assert!(pgp_normalize_key(b"-----BEGIN PGP MESSAGE-----\n\nAAAA\n=ABCD\n-----END PGP MESSAGE-----\n").is_none());
}

fn fingerprint_of(_key: &pgp::composed::SignedPublicKey) -> String {
    use pgp::types::KeyDetails;
    let fp = _key.fingerprint();
    let bytes = fp.as_bytes();
    const HEX: &[u8; 16] = b"0123456789ABCDEF";
    let mut out = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        out.push(HEX[(b >> 4) as usize] as char);
        out.push(HEX[(b & 0x0f) as usize] as char);
    }
    out
}
