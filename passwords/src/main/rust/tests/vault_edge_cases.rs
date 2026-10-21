//! Vault edge cases for the KDBX read/write core.
//!
//! Extends `src/tests.rs` without duplicating it: that suite already covers one wrong password,
//! one two-level nesting shape, one malformed blob and the keepassjava2 vector. These tests add
//! a trailing-space password variant, header corruption plus truncation, an empty vault, unicode
//! fields, and sibling-group flattening.

use keepass::db::{fields, Database};
use keepass::DatabaseKey;
use passwords_kdbx::{export_kdbx, import_kdbx};

const PW: &str = "correct horse battery staple";

fn single_entry_json(title: &str, password: &str) -> String {
    format!(r#"[{{"Title":"{title}","Password":"{password}"}}]"#)
}

#[test]
fn wrong_password_with_trailing_space_fails() {
    let bytes = export_kdbx(PW, &single_entry_json("x", "p")).expect("export");
    assert!(import_kdbx(&format!("{PW} "), &bytes).is_none());
    assert!(import_kdbx("", &bytes).is_none());
}

#[test]
fn corrupt_header_and_truncation_are_rejected() {
    let bytes = export_kdbx(PW, &single_entry_json("x", "p")).expect("export");
    let mut bad_magic = bytes.clone();
    bad_magic[0] ^= 0xFF;
    assert!(import_kdbx(PW, &bad_magic).is_none());
    let mut bad_tail = bytes.clone();
    let last = bad_tail.len() - 1;
    bad_tail[last] ^= 0xFF;
    assert!(import_kdbx(PW, &bad_tail).is_none());
    let half = bytes.len() / 2;
    assert!(import_kdbx(PW, &bytes[..half]).is_none());
    assert!(import_kdbx(PW, &[]).is_none());
}

#[test]
fn empty_vault_round_trips_to_no_entries() {
    let bytes = export_kdbx(PW, "[]").expect("export empty");
    let out = import_kdbx(PW, &bytes).expect("import empty");
    assert_eq!(out, "[]");
}

#[test]
fn unicode_fields_survive_round_trip() {
    let json = r#"[{"Title":"café ☕ 日本語 🎉","Password":"pässwörd🔑","UserName":"用户"}]"#;
    let bytes = export_kdbx(PW, json).expect("export");
    let out = import_kdbx(PW, &bytes).expect("import");
    assert!(out.contains("café ☕ 日本語 🎉"), "title survived: {out}");
    assert!(out.contains("pässwörd🔑"), "password survived: {out}");
    assert!(out.contains("用户"), "username survived: {out}");
}

#[test]
fn entries_in_sibling_groups_are_flattened() {
    let mut db = Database::new();
    db.root_mut().add_entry().set_unprotected(fields::TITLE, "Root");
    let a_id = db.root_mut().add_group().edit(|g| g.name = "Alpha".into()).id();
    let b_id = db.root_mut().add_group().edit(|g| g.name = "Beta".into()).id();
    db.group_mut(a_id).unwrap().add_entry().set_unprotected(fields::TITLE, "InAlpha");
    db.group_mut(b_id).unwrap().add_entry().set_unprotected(fields::TITLE, "InBeta");
    let mut bytes = Vec::new();
    db.save(&mut bytes, DatabaseKey::new().with_password(PW)).unwrap();
    let out = import_kdbx(PW, &bytes).expect("import");
    for title in ["Root", "InAlpha", "InBeta"] {
        assert!(out.contains(title), "missing {title}: {out}");
    }
}
