//! Unit tests for the communicate Signal crate.
//!
//! Covers gaps left by `tests/signal_edges.rs` and the inline `wa_binary` suite:
//! X3DH establishment, record framing, out-of-order sender keys, wire version
//! checks, binary node edges and crypto primitive edges. Host-only, no JNI.

use crate::crypto;
use crate::group;
use crate::session;
use crate::wa_binary::{decode_node, encode_node, Node};
use crate::wire::{
    PreKeySignalMessage, SenderKeyDistributionMessage, SenderKeyMessage, SignalMessage,
};

fn fresh_keypair() -> ([u8; 32], [u8; 32]) {
    crypto::generate_key_pair(&mut crate::OsRng::default())
}

fn round_trip(node: &Node) -> Node {
    let encoded = encode_node(node);
    assert_eq!(encoded[0], 0, "frame flag byte must lead the encoding");
    decode_node(&encoded[1..]).expect("decode")
}

#[test]
fn x3dh_session_establishes_and_exchanges_messages() {
    let mut rng = crate::OsRng::default();
    let (alice_ik_priv, alice_ik_pub) = fresh_keypair();
    let (bob_ik_priv, bob_ik_pub) = fresh_keypair();
    let (bob_spk_priv, bob_spk_pub) = fresh_keypair();
    let (bob_opk_priv, bob_opk_pub) = fresh_keypair();
    let random = [0x33u8; 64];
    let sig = crypto::sign(&bob_ik_priv, &crypto::serialize_public(&bob_spk_pub), &random);
    let bundle = session::PreKeyBundle {
        registration_id: 7,
        pre_key_id: Some(11),
        pre_key_public: Some(bob_opk_pub),
        signed_pre_key_id: 3,
        signed_pre_key_public: bob_spk_pub,
        signed_pre_key_signature: sig.to_vec(),
        identity_key: bob_ik_pub,
    };
    let mut alice =
        session::process_pre_key_bundle(&mut rng, &bundle, &alice_ik_priv, &alice_ik_pub, 9)
            .expect("alice processes bundle");
    assert!(alice.pending_pre_key.is_some());
    let enc = session::encrypt(&mut alice, b"hello bob").expect("alice encrypts");
    assert!(enc.is_pre_key);
    let pk = PreKeySignalMessage::parse(&enc.body).expect("parse pkmsg");
    assert_eq!(pk.pre_key_id, Some(11));
    assert_eq!(pk.registration_id, 9);
    let mut bob = session::process_pre_key_message(
        &pk,
        &bob_ik_priv,
        &bob_ik_pub,
        &bob_spk_priv,
        Some(&bob_opk_priv),
    )
    .expect("bob processes pkmsg");
    let inner = SignalMessage::parse(&pk.message).expect("parse inner");
    let plain = session::decrypt(&mut rng, &mut bob, &inner).expect("bob decrypts");
    assert_eq!(plain, b"hello bob");
    let enc2 = session::encrypt(&mut bob, b"hello alice").expect("bob encrypts");
    assert!(!enc2.is_pre_key);
    let msg2 = SignalMessage::parse(&enc2.body).expect("parse msg2");
    let plain2 = session::decrypt(&mut rng, &mut alice, &msg2).expect("alice decrypts");
    assert_eq!(plain2, b"hello alice");
    let blob = alice.serialize();
    let restored = session::SessionState::deserialize(&blob).expect("record round trip");
    assert_eq!(restored.root_key, alice.root_key);
    assert_eq!(restored.remote_registration_id, alice.remote_registration_id);
}

#[test]
fn session_record_round_trip_and_rejects_corruption() {
    let state = session::SessionState {
        root_key: [0x11u8; 32],
        local_identity: [0x22u8; 32],
        remote_identity: [0x33u8; 32],
        remote_registration_id: 123,
        sender_ratchet_private: [0x44u8; 32],
        sender_ratchet_public: [0x55u8; 32],
        sender_chain: Some(session::ChainKey { key: [0x66u8; 32], index: 7 }),
        previous_counter: 3,
        receiver_chains: vec![session::ReceiverChain {
            ratchet_key: [0x77u8; 32],
            chain_key: session::ChainKey { key: [0x88u8; 32], index: 5 },
            skipped: vec![session::SkippedKey {
                index: 2,
                cipher_key: [0x99u8; 32],
                mac_key: [0xaau8; 32],
                iv: [0xbbu8; 16],
            }],
        }],
        pending_pre_key: Some(session::PendingPreKey {
            pre_key_id: Some(42),
            signed_pre_key_id: 9,
            base_key: [0xccu8; 32],
            local_registration_id: 77,
        }),
    };
    let blob = state.serialize();
    let back = session::SessionState::deserialize(&blob).expect("record round trip");
    assert_eq!(back.root_key, state.root_key);
    assert_eq!(back.sender_chain.as_ref().expect("chain").index, 7);
    assert_eq!(back.receiver_chains.len(), 1);
    assert_eq!(back.receiver_chains[0].skipped.len(), 1);
    assert_eq!(back.pending_pre_key.as_ref().expect("ppk").signed_pre_key_id, 9);
    assert!(session::SessionState::deserialize(&[]).is_err());
    let mut bad_version = blob.clone();
    bad_version[0] = 99;
    assert!(session::SessionState::deserialize(&bad_version).is_err());
    assert!(session::SessionState::deserialize(&blob[..blob.len() / 2]).is_err());
    let mut trailing = blob.clone();
    trailing.push(0xff);
    assert!(session::SessionState::deserialize(&trailing).is_err());
}

#[test]
fn sender_key_out_of_order_decrypt_uses_skipped_keys() {
    let mut rng = crate::OsRng::default();
    let (own, skdm) = group::create(&mut rng);
    let mut sender = own.clone();
    let msg_first = group::encrypt(&mut rng, &mut sender, b"first").expect("encrypt first");
    let msg_second = group::encrypt(&mut rng, &mut sender, b"second").expect("encrypt second");
    let mut peer = group::process(&skdm).expect("process");
    let got_second = group::decrypt(&mut peer, &msg_second).expect("decrypt second first");
    assert_eq!(got_second, b"second");
    let got_first = group::decrypt(&mut peer, &msg_first).expect("decrypt skipped first");
    assert_eq!(got_first, b"first");
    assert!(group::decrypt(&mut peer, &msg_first).is_err(), "replay must fail");
    assert!(group::encrypt(&mut rng, &mut peer, b"no private").is_err());
    let bad = SenderKeyDistributionMessage {
        key_id: skdm.key_id,
        iteration: 0,
        chain_key: vec![1u8, 2u8, 3u8],
        signing_key: skdm.signing_key,
    };
    assert!(group::process(&bad).is_err());
    let blob = own.serialize();
    let restored = group::SenderKeyState::deserialize(&blob).expect("sender record");
    assert_eq!(restored.key_id, own.key_id);
    assert!(group::SenderKeyState::deserialize(&[]).is_err());
}

#[test]
fn wire_version_and_missing_field_checks() {
    let (_, public_a) = fresh_keypair();
    let (_, public_b) = fresh_keypair();
    let (_, ratchet) = fresh_keypair();
    let mac_key = [0x11u8; 32];
    let msg = SignalMessage::new(&mac_key, &public_a, &public_b, &ratchet, 3, 1, b"cipher");
    let mut legacy = msg.serialized.clone();
    legacy[0] = (2u8 << 4) | 3u8;
    assert!(SignalMessage::parse(&legacy).is_err());
    let mut unknown = msg.serialized.clone();
    unknown[0] = (4u8 << 4) | 3u8;
    assert!(SignalMessage::parse(&unknown).is_err());
    assert!(SignalMessage::parse(&[]).is_err());
    let (_, base) = fresh_keypair();
    let (_, ident) = fresh_keypair();
    let without_opk = PreKeySignalMessage {
        registration_id: 1,
        pre_key_id: None,
        signed_pre_key_id: 5,
        base_key: base,
        identity_key: ident,
        message: vec![9u8, 9u8],
    };
    let back = PreKeySignalMessage::parse(&without_opk.serialize()).expect("parse without opk");
    assert_eq!(back.pre_key_id, None);
    assert_eq!(back.signed_pre_key_id, 5);
    let skdm = SenderKeyDistributionMessage {
        key_id: 8,
        iteration: 2,
        chain_key: vec![0xabu8; 32],
        signing_key: public_a,
    };
    let skdm_back = SenderKeyDistributionMessage::parse(&skdm.serialize()).expect("parse skdm");
    assert_eq!(skdm_back.key_id, 8);
    assert_eq!(skdm_back.chain_key, vec![0xabu8; 32]);
    assert!(SenderKeyMessage::parse(&[]).is_err());
    assert!(SenderKeyDistributionMessage::parse(&[]).is_err());
}

#[test]
fn wa_binary_double_byte_and_jid_forms_round_trip() {
    let double_byte = Node::new("read-self");
    assert_eq!(round_trip(&double_byte), double_byte);
    let empty_user = Node::new("iq").attr("to", "@s.whatsapp.net");
    assert_eq!(round_trip(&empty_user), empty_user);
    let first = Node::new("iq").attr("b", "2").attr("a", "1");
    let second = Node::new("iq").attr("a", "1").attr("b", "2");
    assert_eq!(encode_node(&first), encode_node(&second), "attribute order is deterministic");
    let mut with_data = Node::new("enc");
    with_data.data = Some(b"hello".to_vec());
    assert_eq!(round_trip(&with_data), with_data);
    assert!(decode_node(&[]).is_err());
}

#[test]
fn crypto_hkdf_and_sign_verify_edges() {
    let out_empty = crypto::hkdf(b"input", &[], b"info", 64);
    let out_zero = crypto::hkdf(b"input", &[0u8; 32], b"info", 64);
    assert_eq!(out_empty, out_zero, "empty salt means all-zero salt");
    assert_eq!(out_empty.len(), 64);
    let out_other = crypto::hkdf(b"input", &[], b"other", 64);
    assert_ne!(out_empty, out_other);
    let keys_one = crypto::MessageKeys::derive(b"seed");
    let keys_two = crypto::MessageKeys::derive(b"seed");
    assert_eq!(keys_one.cipher_key, keys_two.cipher_key);
    let keys_diff = crypto::MessageKeys::derive(b"other seed");
    assert_ne!(keys_one.cipher_key, keys_diff.cipher_key);
    let (private, public) = fresh_keypair();
    let random = [0x33u8; 64];
    let sig = crypto::sign(&private, b"msg", &random);
    assert!(crypto::verify(&public, b"msg", &sig));
    assert!(!crypto::verify(&public, b"other", &sig));
    let mut non_canonical = sig;
    non_canonical[63] |= 0xe0;
    assert!(!crypto::verify(&public, b"msg", &non_canonical));
    let digest = crypto::sha256(b"abc");
    let expected: [u8; 32] = [
        0xba, 0x78, 0x16, 0xbf, 0x8f, 0x01, 0xcf, 0xea, 0x41, 0x41, 0x40, 0xde, 0x5d, 0xae, 0x22, 0x23,
        0xb0, 0x03, 0x61, 0xa3, 0x96, 0x17, 0x7a, 0x9c, 0xb4, 0x10, 0xff, 0x61, 0xf2, 0x00, 0x15, 0xad,
    ];
    assert_eq!(digest, expected);
    let serialized = crypto::serialize_public(&public);
    assert_eq!(serialized.len(), 33);
    assert_eq!(crypto::parse_public(&serialized).expect("parse prefixed"), public);
    assert_eq!(crypto::parse_public(&public).expect("parse bare"), public);
    assert!(crypto::parse_public(&[0u8; 5]).is_err());
}
