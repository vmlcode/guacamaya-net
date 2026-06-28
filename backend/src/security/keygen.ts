import { randomBytes } from "node:crypto";
import * as ed from "@noble/ed25519";
import { bytesToHex } from "@noble/hashes/utils.js";

const priv = ed.utils.randomPrivateKey();
const pub = await ed.getPublicKeyAsync(priv);

console.log("# Guacamaya Net — generated keys (add to backend/.env)\n");
console.log(`BACKEND_PRIVATE_KEY_HEX=${bytesToHex(priv)}`);
console.log(`BACKEND_PUBLIC_KEY_HEX=${bytesToHex(pub)}`);
console.log(`GUACAMAYA_ADMIN_KEY=${randomBytes(32).toString("hex")}`);
