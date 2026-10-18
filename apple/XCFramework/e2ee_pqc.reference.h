/* EverySync — reference C header for the 10 bound PQC functions.
 *
 * Generated from `library/e2ee-p2p/src/main/rust/src/c_bridge.rs` via
 * cbindgen at Rust build time (see BUILD_NOTES.md step 2). This REFERENCE
 * copy pins the exact signatures the Kotlin cinterop actual + the `.def`
 * bind — the live `e2ee_pqc.h` lands next to `e2eePqc.def` on the Mac.
 *
 * Conventions (from c_bridge.rs):
 * - Return 1 on success / 0 on failure, EXCEPT pqc_dsa_verify:
 *   1 = valid, 0 = invalid, -1 = parse error.
 * - Every `out` buffer is Box::into_raw: caller copies, then pqc_free.
 * - Priv/SS bytes must be zeroed with pqc_secure_zero after use.
 * - Lengths are size_t (cbindgen `usize_is_size_t = true`).
 */
#ifndef EVERYSYNC_PQC_REFERENCE_H
#define EVERYSYNC_PQC_REFERENCE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Frees a buffer previously returned through an out-param. */
void pqc_free(uint8_t *ptr, size_t len);

/* Best-effort secure zero of a buffer (private keys / shared secrets). */
void pqc_secure_zero(uint8_t *ptr, size_t len);

/* ML-KEM-768 keypair. Out params filled on success (caller frees via pqc_free). */
int32_t pqc_kem_keygen(uint8_t **pub_out, size_t *pub_len_out,
                       uint8_t **priv_out, size_t *priv_len_out);

/* ML-DSA-65 keypair. Out params filled on success (caller frees). */
int32_t pqc_dsa_keygen(uint8_t **pub_out, size_t *pub_len_out,
                       uint8_t **priv_out, size_t *priv_len_out);

/* Public bundle: len_prefix(kem_pub, dsa_pub) = [4B BE len][kem][dsa]. */
int32_t pqc_bundle_build(const uint8_t *kem_pub, size_t kem_pub_len,
                         const uint8_t *dsa_pub, size_t dsa_pub_len,
                         uint8_t **out, size_t *out_len);

/* Splits a public bundle (caller frees each half via pqc_free). */
int32_t pqc_bundle_split(const uint8_t *bundle, size_t bundle_len,
                         uint8_t **kem_pub_out, size_t *kem_pub_len_out,
                         uint8_t **dsa_pub_out, size_t *dsa_pub_len_out);

/* Encapsulates to a KEM public DER: (ciphertext(1088), sharedSecret(32)). */
int32_t pqc_kem_encaps(const uint8_t *kem_pub, size_t kem_pub_len,
                       uint8_t **ct_out, size_t *ct_len_out,
                       uint8_t **ss_out, size_t *ss_len_out);

/* Decapsulates with a KEM private DER + ciphertext -> sharedSecret(32). */
int32_t pqc_kem_decaps(const uint8_t *kem_priv, size_t kem_priv_len,
                       const uint8_t *ct, size_t ct_len,
                       uint8_t **ss_out, size_t *ss_len_out);

/* Signs with a DSA private DER. */
int32_t pqc_dsa_sign(const uint8_t *dsa_priv, size_t dsa_priv_len,
                     const uint8_t *msg, size_t msg_len,
                     uint8_t **sig_out, size_t *sig_len_out);

/* Verifies with a DSA public DER: 1 valid / 0 invalid / -1 parse error. */
int32_t pqc_dsa_verify(const uint8_t *dsa_pub, size_t dsa_pub_len,
                       const uint8_t *msg, size_t msg_len,
                       const uint8_t *sig, size_t sig_len);

/* CryptoKit AES-GCM bridge (../Shared/EverysyncCoreCrypto.swift).
 * Same Box::into_raw discipline, freed via everysync_crypto_free.
 * Layout: [12B nonce][ciphertext][16B tag] (== SealedBox.combined). */
int32_t everysync_aes_gcm_encrypt(const uint8_t *key, size_t key_len,
                                  const uint8_t *pt, size_t pt_len,
                                  uint8_t **out, size_t *out_len);
int32_t everysync_aes_gcm_decrypt(const uint8_t *key, size_t key_len,
                                  const uint8_t *ct, size_t ct_len,
                                  uint8_t **out, size_t *out_len);
void everysync_crypto_free(uint8_t *ptr, size_t len);

#ifdef __cplusplus
}
#endif

#endif /* EVERYSYNC_PQC_REFERENCE_H */
