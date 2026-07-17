import { supabase, isSupabaseConfigured } from "../db/supabase.js";
import { controlReplayStore } from "./replayStore.js";

const TABLE = "control_replay";
const PRUNE_INTERVAL_MS = 60_000;

let lastPruneMs = 0;

export interface ControlReplayEntry {
  /** 64-byte Ed25519 signature hex (128 chars). */
  signatureHex: string;
  /** 0x01 delivery | 0x02 backend-resolve | 0x03 self-cancel. */
  frameType: number;
  /** sha256(issuer pubkey)[0..3] hex — dedupe namespace. */
  issuerNodeIdHex: string;
  /** u16 CSPRNG control message id. */
  ctrlMsgId: number;
  /** Unix ms the underlying frame expires at. */
  expiresAtMs: number;
}

export const controlReplayRepo = {
  /**
   * Admit a freshly-verified control frame exactly once per signature.
   * Returns true iff this signature is newly recorded (dedupe gate).
   * Mirrors the SOS channel_records upsert-ignoreDedup pattern.
   */
  async admit(entry: ControlReplayEntry, nowMs: number): Promise<boolean> {
    if (!isSupabaseConfigured || !supabase) {
      this._maybePrune(nowMs);
      return controlReplayStore.admit(entry.signatureHex, entry.expiresAtMs, nowMs);
    }

    const { data, error } = await supabase
      .from(TABLE)
      .upsert(
        {
          signature_hex: entry.signatureHex,
          frame_type: entry.frameType,
          issuer_node_id: entry.issuerNodeIdHex,
          ctrl_msg_id: entry.ctrlMsgId,
          expires_at: entry.expiresAtMs,
          received_at: nowMs,
        },
        { onConflict: "signature_hex", ignoreDuplicates: true },
      )
      .select("signature_hex");
    if (error) throw error;

    this._maybePrune(nowMs);
    return (data?.length ?? 0) > 0;
  },

  /** Throttled prune of expired rows — bounded DB write load under churn. */
  _maybePrune(nowMs: number): void {
    if (nowMs - lastPruneMs < PRUNE_INTERVAL_MS) return;
    lastPruneMs = nowMs;
    this.prune(nowMs).catch((err) => {
      console.error("[control-replay] prune failed", err);
    });
  },

  async prune(nowMs: number): Promise<void> {
    if (!isSupabaseConfigured || !supabase) {
      controlReplayStore.prune(nowMs);
      return;
    }
    const { error } = await supabase.from(TABLE).delete().lt("expires_at", nowMs);
    if (error) throw error;
  },

  reset(): void {
    controlReplayStore.reset();
    lastPruneMs = 0;
  },
};
