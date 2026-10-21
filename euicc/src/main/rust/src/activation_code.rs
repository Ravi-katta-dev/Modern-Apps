//! SGP.22 activation-code parsing (`LPA:1$smdp$matchingId[$oid][$flag]`).
//!
//! Split from `download.rs` (Rust file-length gate: 500 lines). Pure parsing
//! with no JNI or network — safe to unit-test on the host.
//!
//! Validation mirrors lpac's `download.c:applet_main`: the format field must be
//! `"1"`, the matching id is `alnum|-` only, and a confirmation-required flag
//! without a separately supplied code is a local error — fail fast instead of
//! sending a malformed order to the SM-DP+.

/// A parsed SGP.22 activation code (`LPA:1$smdp$matchingId[$oid][$flag]`).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ActivationCode {
    /// SM-DP+ FQDN (no scheme).
    pub smdp: String,
    /// Matching ID for this download.
    pub matching_id: String,
    /// Whether the SM-DP+ requires a confirmation code.
    pub confirmation_code_required: bool,
}

/// Parses `LPA:1$smdp.example.com$MATCHINGID[$OID][$1]` (the `LPA:` prefix and
/// trailing fields are optional).
pub fn parse_activation_code(code: &str) -> Result<ActivationCode, String> {
    let body = code.trim().strip_prefix("LPA:").unwrap_or(code.trim());
    let parts: Vec<&str> = body.split('$').collect();
    if parts.len() < 3 {
        return Err("Activation code must be LPA:1$smdp$matchingId".to_string());
    }
    if parts[0].trim() != "1" {
        return Err(format!(
            "Unsupported activation code format '{}' (expected '1')",
            parts[0].trim()
        ));
    }
    let smdp = parts[1].trim();
    let matching_id = parts[2].trim();
    if smdp.is_empty() {
        return Err("Activation code is missing the SM-DP+ address".to_string());
    }
    if matching_id.is_empty() {
        return Err("Activation code is missing the matching ID".to_string());
    }
    if !matching_id
        .bytes()
        .all(|b| b.is_ascii_alphanumeric() || b == b'-')
    {
        return Err(
            "Matching ID has an invalid format (only letters, digits and '-' are allowed)"
                .to_string(),
        );
    }
    if !is_plausible_smdp(smdp) {
        return Err(format!("SM-DP+ address '{smdp}' does not look like a hostname"));
    }
    let confirmation_code_required = parts.get(4).map(|s| s.trim() == "1").unwrap_or(false);
    Ok(ActivationCode {
        smdp: smdp.to_string(),
        matching_id: matching_id.to_string(),
        confirmation_code_required,
    })
}

/// Minimal FQDN sanity check for the SM-DP+ address (lpac leaves a bad address
/// to fail at TLS; we fail fast with the offending value named).
fn is_plausible_smdp(smdp: &str) -> bool {
    let host = smdp
        .trim()
        .trim_start_matches("https://")
        .trim_start_matches("http://")
        .trim_end_matches('/')
        .split(':')
        .next()
        .unwrap_or("");
    !host.is_empty()
        && host.contains('.')
        && host
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'.')
        && !host.starts_with('.')
        && !host.starts_with('-')
        && !host.ends_with('.')
}

/// `hashCc = SHA256(SHA256(code) || transactionIdBin)`, where `transactionIdBin`
/// is tag `0x80` inside `smdpSigned2` — mirroring lpac's
/// `es10b_prepare_download_r`.
pub fn hash_confirmation_code(code: &str, transaction_id: &[u8]) -> [u8; 32] {
    use sha2::{Digest, Sha256};
    let inner = Sha256::digest(code.as_bytes());
    let mut outer = Sha256::new();
    outer.update(inner);
    outer.update(transaction_id);
    outer.finalize().into()
}

/// Download inputs beyond the activation code itself.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct DownloadOptions {
    /// Confirmation code for `ccRequiredFlag` profiles (`None` when the profile
    /// does not need one or the code is not known yet).
    pub confirmation_code: Option<String>,
    /// Device TAC (4 bytes). Defaults to `es10b::DEFAULT_TAC` like lpac.
    pub tac: Option<[u8; 4]>,
    /// Device IMEI digits for `ctxParams1` (user-editable, optional).
    pub imei: Option<String>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_full_activation_code() {
        let ac = parse_activation_code("LPA:1$smdp.example.com$MATCH-123").unwrap();
        assert_eq!(ac.smdp, "smdp.example.com");
        assert_eq!(ac.matching_id, "MATCH-123");
        assert!(!ac.confirmation_code_required);
    }

    #[test]
    fn parses_confirmation_code_flag() {
        let ac = parse_activation_code("1$rsp.truphone.com$QR-ABC$$1").unwrap();
        assert_eq!(ac.smdp, "rsp.truphone.com");
        assert_eq!(ac.matching_id, "QR-ABC");
        assert!(ac.confirmation_code_required);
    }

    #[test]
    fn rejects_short_codes() {
        assert!(parse_activation_code("LPA:1$smdp.example.com").is_err());
        assert!(parse_activation_code("garbage").is_err());
        assert!(parse_activation_code("LPA:1$smdp.example.com$").is_err());
    }

    #[test]
    fn rejects_bad_format_version() {
        assert!(parse_activation_code("LPA:2$smdp.example.com$ABC").is_err());
    }

    #[test]
    fn rejects_bad_matching_id_charset() {
        // lpac parity: alnum and '-' only.
        assert!(parse_activation_code("LPA:1$smdp.example.com$ABC_DEF").is_err());
        assert!(parse_activation_code("LPA:1$smdp.example.com$ABC DEF").is_err());
        assert!(parse_activation_code("LPA:1$smdp.example.com$ABC-123").is_ok());
    }

    #[test]
    fn rejects_implausible_smdp() {
        assert!(parse_activation_code("LPA:1$notahost$ABC-123").is_err());
        assert!(parse_activation_code("LPA:1$$ABC-123").is_err());
    }

    #[test]
    fn hash_cc_vector() {
        // hashCc = SHA256(SHA256(code) || transactionId), like lpac's
        // es10b_prepare_download_r. Independent re-computation with sha2 must
        // agree with the helper.
        use sha2::{Digest, Sha256};
        let tx = [0x01u8, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08];
        let inner = Sha256::digest(b"1234");
        let mut outer = Sha256::new();
        outer.update(inner);
        outer.update(tx);
        let expected: [u8; 32] = outer.finalize().into();
        assert_eq!(hash_confirmation_code("1234", &tx), expected);
        // Different codes / transactions differ.
        assert_ne!(
            hash_confirmation_code("1234", &tx),
            hash_confirmation_code("4321", &tx)
        );
        assert_ne!(
            hash_confirmation_code("1234", &tx),
            hash_confirmation_code("1234", &[0x09])
        );
    }

    #[test]
    fn activation_code_edges_and_hash_boundaries() {
        let spaced = parse_activation_code("  LPA:1$smdp.example.com$ABC-123  ").unwrap();
        assert_eq!(spaced.matching_id, "ABC-123");
        assert!(!spaced.confirmation_code_required);
        let bare = parse_activation_code("1$smdp.example.com$ABC-123").unwrap();
        assert_eq!(bare.smdp, "smdp.example.com");
        let flag_off = parse_activation_code("1$smdp.example.com$ABC$$0").unwrap();
        assert!(!flag_off.confirmation_code_required);
        let flag_on = parse_activation_code("1$smdp.example.com$ABC$$1").unwrap();
        assert!(flag_on.confirmation_code_required);
        assert!(parse_activation_code("LPA:1$.bad.host$ABC").is_err());
        assert!(parse_activation_code("LPA:1$bad.host.$ABC").is_err());
        assert!(parse_activation_code("LPA:1$bad_host$ABC").is_err());
        let empty_hash = hash_confirmation_code("", &[]);
        let code_hash = hash_confirmation_code("", &[0x01]);
        assert_ne!(empty_hash, code_hash);
        assert_eq!(empty_hash.len(), 32);
        assert_eq!(hash_confirmation_code("x", &[]).len(), 32);
    }
}
