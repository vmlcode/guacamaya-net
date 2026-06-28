# Guacamaya Net — Crypto

The link layer is intentionally open (public SOS, anyone in range may receive and relay). Authenticity is relocated to the application layer via Ed25519 signatures.

---

## Algorithm

**Ed25519** (RFC 8032).

- Public key: 32 B.
- Signature: 64 B.
- Signing is deterministic (no RNG dependency after key generation).

### Library

Android `java.security.KeyPairGenerator("Ed25519")` is only available from API 33+. Guacamaya Net's `minSdk = 26` (Wi-Fi Aware requirement), so the platform implementation cannot be used.

**Dependency**: BouncyCastle `org.bouncycastle:bcprov-jdk18on:1.78.1`.

- `org.bouncycastle.crypto.signers.Ed25519Signer`
- `org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator`
- `org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters` / `Ed25519PublicKeyParameters`

---

## Identity

One keypair per install, generated lazily on first launch.

```kotlin
val gen = Ed25519KeyPairGenerator().apply {
    init(Ed25519KeyGenerationParameters(SecureRandom()))
}
val kp = gen.generateKeyPair()
val priv = (kp.private as Ed25519PrivateKeyParameters).encoded   // 32 B seed
val pub  = (kp.public  as Ed25519PublicKeyParameters).encoded    // 32 B
```

**Persistence**:

- The 32 B private seed is encrypted with AES-GCM using a key stored in the Android Keystore (`AndroidKeyStoreAES`, `setUserAuthenticationRequired(false)`).
- Ciphertext lives in app-private `SharedPreferences` under key `identity_seed`.
- The public key is the durable node identity. `node_id` (4 B carried in every frame) = first 4 B of `SHA-256(pubkey)`.

```kotlin
val seed = Identity.loadOrGenerate(context)
val nodeId = MessageDigest.getInstance("SHA-256").digest(seed.publicKey).copyOfRange(0, 4)
```

---

## Signing

**Scope**: bytes 0..21 of the application payload — the full 22 B including CRC16 (indices 0 through 21 inclusive).

```kotlin
fun sign(priv: ByteArray, payload22: ByteArray): ByteArray {
    require(payload22.size == 22)
    val signer = Ed25519Signer().apply {
        init(true, Ed25519PrivateKeyParameters(priv, 0))
        update(payload22, 0, 22)
    }
    return signer.generateSignature()   // 64 B
}
```

---

## Verification

```kotlin
fun verify(pub: ByteArray, payload22: ByteArray, sig: ByteArray): Boolean {
    if (payload22.size != 22 || sig.size != 64) return false
    val verifier = Ed25519Signer().apply {
        init(false, Ed25519PublicKeyParameters(pub, 0))
        update(payload22, 0, 22)
    }
    return verifier.verifySignature(sig)
}
```

**Reject cascade** (cheapest first — see `mesh/FloodRouter.kt`):

1. Length checks (above).
2. `SHA-256(pubkey)[0..4] == payload.node_id`. Without this an attacker could swap the pubkey freely.
3. CRC16 over bytes 0..19 must equal bytes 20..21.
4. `|ts_unix - System.currentTimeMillis()/1000| ≤ 300 s`.
5. `hop_ttl > 0` after decrement.
6. `verify(pub, payload22, sig)` returns true.

Only after all six pass is the frame admitted to the message store and queued for rebroadcast.

---

## Threat model

| Threat | Mitigation |
|---|---|
| Passive eavesdropper | None needed — payload is intentionally public. |
| Active forger modifying coordinates / type / text | Ed25519 signature breaks; receiver drops. |
| Replay of an old frame | `ts_unix` skew check (±300 s). |
| Flooding / amplification | Per-`msg_id` dedupe cache + `hop_ttl` decrement. |
| Node impersonation (forging `node_id`) | Infeasible without the origin's private key (Ed25519 EUF-CMA). |
| Sybil (many fake identities) | Out of scope for v1. Future work: social trust graph or PoW-bounded identity. |

---

## Known limitations (v1)

- **No encryption.** The payload is broadcast in the clear. This is by design (public SOS) but means a passive observer can read coordinates.
- **No revocation.** If a private key leaks, the corresponding `node_id` remains valid until peers update their trust lists. Future work: short-lived rotating keys + a revocation broadcast.
- **Truncated node_id (4 B).** Chosen for wire compactness; collision probability is negligible at mesh scales (< 10⁵ nodes) but `node_id` should not be treated as a globally unique identifier — always re-derive the full pubkey when available.
