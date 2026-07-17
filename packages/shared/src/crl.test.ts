import { describe, expect, test } from "bun:test";
import * as ed from "@noble/ed25519";
import { bytesToHex } from "@noble/hashes/utils.js";
import { issueCertificateRevocationList, issueRescuerCertificate, isCertificateRevoked, verifyCertificateRevocationList } from "./control.js";

describe("rescuer CRL", () => {
  test("signed snapshots identify revoked certificates and reject tampering", async () => {
    const backend = ed.utils.randomPrivateKey();
    const backendPub = bytesToHex(await ed.getPublicKeyAsync(backend));
    const rescuer = bytesToHex(await ed.getPublicKeyAsync(ed.utils.randomPrivateKey()));
    const now = 1_700_000_000_000;
    const cert = await issueRescuerCertificate(rescuer, now + 60_000, backend, now);
    const crl = await issueCertificateRevocationList([rescuer], 4, now + 60_000, backend, now);
    expect(await verifyCertificateRevocationList(crl, backendPub, now)).toBe(true);
    expect(isCertificateRevoked(cert, crl)).toBe(true);
    expect(await verifyCertificateRevocationList({ ...crl, sequence: 3 }, backendPub, now)).toBe(false);
  });
});
