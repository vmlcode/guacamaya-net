import * as ed from "@noble/ed25519";
import { bytesToHex, hexToBytes } from "@noble/hashes/utils.js";

// Load private key from environment or fall back to a generated random one in dev.
// The canonical var is BACKEND_PRIVATE_KEY_HEX (see backend/.env.example);
// BACKEND_PRIVATE_KEY is accepted as a legacy alias. Setting it keeps the server's
// identity (and thus its public key) STABLE across reboots — without it a fresh
// random keypair is generated each boot and clients can no longer verify previously
// signed records.
const hexKey = process.env.BACKEND_PRIVATE_KEY_HEX ?? process.env.BACKEND_PRIVATE_KEY;
if (!hexKey) {
  console.warn(
    "[crypto/keys] BACKEND_PRIVATE_KEY_HEX not set — generating an EPHEMERAL keypair. " +
      "The public key will change on every restart. Set it in backend/.env for a stable identity."
  );
}
export const privateKey = hexKey
  ? hexToBytes(hexKey)
  : ed.utils.randomPrivateKey();

// Derive public key bytes and hex string
export const publicKeyBytes = await ed.getPublicKeyAsync(privateKey);
export const publicKeyHex = bytesToHex(publicKeyBytes);
