import { describe, test, expect } from "bun:test";
import * as ed from "@noble/ed25519";
import {
  canonicalResolveBytes,
  getResolveId,
  hashImage,
  signWitness,
  verifyResolveEnvelope,
  verifyWitnessSignature,
  witnessMessageBytes,
} from "./resolve.js";
import type { ResolveEnvelope, ResolveWitness } from "./types.js";

async function makeWitness(
  envelope: ResolveEnvelope,
  overrides: Partial<ResolveWitness> = {}
): Promise<{ witness: ResolveWitness; priv: Uint8Array }> {
  const priv = ed.utils.randomPrivateKey();
  const pub = await ed.getPublicKeyAsync(priv);
  const pubHex = Buffer.from(pub).toString("hex");
  const witness: ResolveWitness = {
    deviceId: `device-${pubHex}`,
    pubkey: pubHex,
    lat: 10.49,
    lon: -66.87,
    ts: Date.now(),
    imageHash: hashImage(new TextEncoder().encode("proof-bytes")),
    ...overrides,
  };
  witness.sig = await signWitness(envelope, witness, priv);
  return { witness, priv };
}

function baseEnvelope(): ResolveEnvelope {
  return {
    targetSosId: "a".repeat(64),
    targetSosAuthor: `device-${"b".repeat(64)}`,
    witnesses: [],
    submittedAt: 1_700_000_000_000,
    note: "test",
  };
}

describe("resolve — canonical bytes", () => {
  test("canonical bytes are byte-stable regardless of object property order", () => {
    const env = baseEnvelope();
    const a = canonicalResolveBytes(env);
    const b = canonicalResolveBytes({
      note: env.note,
      submittedAt: env.submittedAt,
      targetSosAuthor: env.targetSosAuthor,
      targetSosId: env.targetSosId,
      witnesses: env.witnesses,
    });
    expect(Buffer.from(a).toString("hex")).toBe(Buffer.from(b).toString("hex"));
  });

  test("receipt id is stable for same target + same witness set, regardless of order", async () => {
    const env = baseEnvelope();
    const { witness: w1 } = await makeWitness(env);
    const { witness: w2 } = await makeWitness(env);
    env.witnesses = [w1, w2];

    const envReordered: ResolveEnvelope = { ...env, witnesses: [w2, w1] };

    expect(getResolveId(env)).toBe(getResolveId(envReordered));
  });

  test("receipt id changes when witness set changes", async () => {
    const env = baseEnvelope();
    const { witness: w1 } = await makeWitness(env);
    env.witnesses = [w1];
    const id1 = getResolveId(env);

    const { witness: w2 } = await makeWitness(env);
    env.witnesses = [w1, w2];
    const id2 = getResolveId(env);

    expect(id1).not.toBe(id2);
  });
});

describe("resolve — witness signature", () => {
  test("round-trip sign/verify succeeds", async () => {
    const env = baseEnvelope();
    const { witness } = await makeWitness(env);
    expect(await verifyWitnessSignature(env, witness)).toBe(true);
  });

  test("tampered lat invalidates signature", async () => {
    const env = baseEnvelope();
    const { witness } = await makeWitness(env);
    const tampered: ResolveWitness = { ...witness, lat: witness.lat + 1 };
    expect(await verifyWitnessSignature(env, tampered)).toBe(false);
  });

  test("tampered imageHash invalidates signature", async () => {
    const env = baseEnvelope();
    const { witness } = await makeWitness(env);
    const tampered: ResolveWitness = { ...witness, imageHash: "0".repeat(64) };
    expect(await verifyWitnessSignature(env, tampered)).toBe(false);
  });

  test("witness bytes include macObservationHashes", async () => {
    const env = baseEnvelope();
    const { witness: w1 } = await makeWitness(env, { macObservationHashes: ["deadbeef"] });
    const { witness: w2 } = await makeWitness(env, { macObservationHashes: ["cafebabe"] });
    env.witnesses = [w1];

    // Same envelope, different MAC list → different witness bytes
    const bytes1 = witnessMessageBytes(env, w1);
    const bytes2 = witnessMessageBytes(env, w2);
    expect(Buffer.from(bytes1).toString("hex")).not.toBe(Buffer.from(bytes2).toString("hex"));
  });
});

describe("resolve — envelope verify", () => {
  test("2-of-3 with one bad signature returns validWitnesses=2", async () => {
    const env = baseEnvelope();
    const { witness: w1 } = await makeWitness(env);
    const { witness: w2 } = await makeWitness(env);
    const { witness: w3Bad } = await makeWitness(env);
    // all-zero sig is valid format (128 hex) but won't verify under Ed25519
    const broken: ResolveWitness = { ...w3Bad, sig: "0".repeat(128) };

    env.witnesses = [w1, w2, broken];
    const result = await verifyResolveEnvelope(env);
    expect(result.ok).toBe(false);
    expect(result.validWitnesses).toBe(2);
  });

  test("all-valid envelope returns ok=true and counts every witness", async () => {
    const env = baseEnvelope();
    const { witness: w1 } = await makeWitness(env);
    const { witness: w2 } = await makeWitness(env);
    env.witnesses = [w1, w2];

    const result = await verifyResolveEnvelope(env);
    expect(result.ok).toBe(true);
    expect(result.validWitnesses).toBe(2);
  });
});
