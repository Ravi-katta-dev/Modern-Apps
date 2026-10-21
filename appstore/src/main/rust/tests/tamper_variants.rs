//! Tamper variants for the Accrescent signify trust anchor.
//!
//! Extends `src/tests.rs` without duplicating it: the existing suite flips byte 3 and the final
//! bytes, checks one truncation shape per input and one oversized shape per input. These tests
//! cover the middle of the message and signature, an all-zero key, an oversized message, and a
//! sweep over garbage lengths proving `verify` fails closed instead of panicking.

use appstore_signify::verify;
use base64::engine::general_purpose::STANDARD;
use base64::Engine as _;

/// The exact bytes that were signed.
const REPODATA: &[u8] = include_bytes!("../testvectors/repodata.1.json");

/// The full `.sig` file: an `untrusted comment:` line plus the base64 blob.
const SIG_FILE: &str = include_str!("../testvectors/repodata.1.json.sig");

/// Must stay equal to `AccrescentRepo.REPODATA_PUBKEY`.
const PUBKEY_B64: &str = "RWT8aZ/NdUmXCPqQ0we7UyCe34q1xRfncBFVK5dI3ok9BkL1bFF3mgh3";

/// `2-byte algo id || 8-byte key id || 32-byte ed25519 public key`.
fn pubkey() -> Vec<u8> {
    STANDARD.decode(PUBKEY_B64).unwrap()[10..42].to_vec()
}

/// `2-byte algo id || 8-byte key id || 64-byte ed25519 signature`.
fn signature() -> Vec<u8> {
    let blob = SIG_FILE
        .lines()
        .map(str::trim)
        .filter(|l| !l.is_empty() && !l.starts_with("untrusted comment:"))
        .last()
        .unwrap();
    STANDARD.decode(blob).unwrap()[10..74].to_vec()
}

#[test]
fn rejects_bit_flip_in_middle_of_message() {
    let mut tampered = REPODATA.to_vec();
    let mid = tampered.len() / 2;
    tampered[mid] ^= 0x40;
    assert!(!verify(&pubkey(), &tampered, &signature()));
}

#[test]
fn rejects_bit_flip_in_middle_of_signature() {
    let mut sig = signature();
    sig[32] ^= 0x40;
    assert!(!verify(&pubkey(), REPODATA, &sig));
}

#[test]
fn rejects_all_zero_public_key() {
    assert!(!verify(&[0u8; 32], REPODATA, &signature()));
}

#[test]
fn rejects_message_with_appended_byte() {
    let mut longer = REPODATA.to_vec();
    longer.push(b'\n');
    assert!(!verify(&pubkey(), &longer, &signature()));
}

#[test]
fn rejects_garbage_lengths_without_panicking() {
    let (pk, sig) = (pubkey(), signature());
    for key_len in [0usize, 1, 31, 33, 64] {
        let key = vec![0xA5u8; key_len];
        assert!(!verify(&key, REPODATA, &sig), "key len {key_len}");
    }
    for sig_len in [0usize, 1, 63, 65, 128] {
        let bad_sig = vec![0x5Au8; sig_len];
        assert!(!verify(&pk, REPODATA, &bad_sig), "sig len {sig_len}");
    }
    for msg_len in [0usize, 1, 16] {
        let msg = vec![0x00u8; msg_len];
        assert!(!verify(&pk, &msg, &sig), "msg len {msg_len}");
    }
    assert!(!verify(&pk, &[], &sig));
}
