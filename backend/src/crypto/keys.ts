import * as ed from "@noble/ed25519";
import { bytesToHex, hexToBytes } from "@noble/hashes/utils.js";

// Load private key from environment or fallback to a generated random one in dev
const hexKey = process.env.BACKEND_PRIVATE_KEY;
export const privateKey = hexKey 
  ? hexToBytes(hexKey) 
  : ed.utils.randomPrivateKey();

// Derive public key bytes and hex string
export const publicKeyBytes = await ed.getPublicKeyAsync(privateKey);
export const publicKeyHex = bytesToHex(publicKeyBytes);
