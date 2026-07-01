import type { ResolveReceipt, ResolveWitness } from "@guacamaya/shared";
import { supabase, isSupabaseConfigured } from "./supabase.js";
import { resolveStore } from "../resolve/store.js";

const RECEIPTS_TABLE = "resolve_receipts";
const WITNESSES_TABLE = "resolve_witnesses";

/**
 * Durable data-access layer for the resolve flow.
 *
 * Mirrors the channelsRepo pattern: Supabase when configured, in-memory
 * `resolveStore` fallback otherwise. Dedup of witnesses is by the unique
 * constraint `(target_sos_id, device_id)` — same one-witness-per-target gate
 * the in-memory `witnessIndex` enforces.
 */
export const resolvesRepo = {
  async hasWitness(targetSosId: string, deviceId: string): Promise<boolean> {
    if (!isSupabaseConfigured || !supabase) {
      return resolveStore.hasWitness(targetSosId, deviceId);
    }
    const { data, error } = await supabase
      .from(WITNESSES_TABLE)
      .select("device_id")
      .eq("target_sos_id", targetSosId)
      .eq("device_id", deviceId)
      .maybeSingle();
    if (error) throw error;
    return Boolean(data);
  },

  async addWitness(targetSosId: string, witness: ResolveWitness, receiptId: string): Promise<boolean> {
    if (!isSupabaseConfigured || !supabase) {
      return resolveStore.recordWitness(targetSosId, witness);
    }
    const row = {
      receipt_id: receiptId,
      target_sos_id: targetSosId,
      device_id: witness.deviceId,
      pubkey: witness.pubkey,
      lat: witness.lat,
      lon: witness.lon,
      ts: witness.ts,
      image_hash: witness.imageHash,
      mac_observation_hashes: witness.macObservationHashes ?? [],
      sig: witness.sig,
    };
    const { data, error } = await supabase
      .from(WITNESSES_TABLE)
      .upsert(row, { onConflict: "target_sos_id,device_id", ignoreDuplicates: true })
      .select("device_id");
    if (error) throw error;
    return (data?.length ?? 0) > 0;
  },

  async getWitnesses(targetSosId: string): Promise<ResolveWitness[]> {
    if (!isSupabaseConfigured || !supabase) {
      return resolveStore.getWitnesses(targetSosId);
    }
    const { data, error } = await supabase
      .from(WITNESSES_TABLE)
      .select("device_id, pubkey, lat, lon, ts, image_hash, mac_observation_hashes, sig")
      .eq("target_sos_id", targetSosId)
      .order("ts", { ascending: true });
    if (error) throw error;
    return (data ?? []).map((r: any) => ({
      deviceId: r.device_id,
      pubkey: r.pubkey,
      lat: r.lat,
      lon: r.lon,
      ts: r.ts,
      imageHash: r.image_hash,
      macObservationHashes: r.mac_observation_hashes ?? undefined,
      sig: r.sig,
    }));
  },

  async upsertReceipt(receipt: ResolveReceipt): Promise<void> {
    if (!isSupabaseConfigured || !supabase) {
      resolveStore.upsertReceipt(receipt);
      return;
    }
    const row = {
      id: receipt.id,
      target_sos_id: receipt.targetSosId,
      target_sos_author: receipt.targetSosAuthor,
      status: receipt.status,
      quorum_needed: receipt.quorumNeeded,
      quorum_seen: receipt.quorumSeen,
      witness_device_ids: receipt.witnessDeviceIds,
      cooldown_ends_at: receipt.cooldownEndsAt ?? null,
      disputed_reason: receipt.disputedReason ?? null,
    };
    const { error } = await supabase
      .from(RECEIPTS_TABLE)
      .upsert(row, { onConflict: "id", ignoreDuplicates: false });
    if (error) throw error;
  },

  async getReceiptByTarget(targetSosId: string): Promise<ResolveReceipt | null> {
    if (!isSupabaseConfigured || !supabase) {
      return resolveStore.getReceiptByTarget(targetSosId) ?? null;
    }
    const { data, error } = await supabase
      .from(RECEIPTS_TABLE)
      .select("id, target_sos_id, target_sos_author, status, quorum_needed, quorum_seen, witness_device_ids, created_at, cooldown_ends_at, disputed_reason")
      .eq("target_sos_id", targetSosId)
      .order("created_at", { ascending: false })
      .limit(1)
      .maybeSingle();
    if (error) throw error;
    if (!data) return null;
    return fromRow(data);
  },

  async findPendingClearForAuthor(authorDeviceId: string, excludeTargetId: string): Promise<string | null> {
    if (!isSupabaseConfigured || !supabase) {
      return resolveStore.findPendingClearForAuthor(authorDeviceId, excludeTargetId);
    }
    const { data, error } = await supabase
      .from(RECEIPTS_TABLE)
      .select("target_sos_id")
      .eq("target_sos_author", authorDeviceId)
      .eq("status", "pending")
      .neq("target_sos_id", excludeTargetId)
      .order("created_at", { ascending: false })
      .limit(1)
      .maybeSingle();
    if (error) throw error;
    return data?.target_sos_id ?? null;
  },

  async markDisputed(targetSosId: string, reason: string): Promise<void> {
    if (!isSupabaseConfigured || !supabase) {
      resolveStore.autoDispute(targetSosId, reason);
      return;
    }
    const { error } = await supabase
      .from(RECEIPTS_TABLE)
      .update({ status: "disputed", disputed_reason: reason })
      .eq("target_sos_id", targetSosId)
      .eq("status", "pending");
    if (error) throw error;
  },

  async markCleared(targetSosId: string): Promise<void> {
    if (!isSupabaseConfigured || !supabase) {
      resolveStore.promoteToCleared(targetSosId);
      return;
    }
    const { error } = await supabase
      .from(RECEIPTS_TABLE)
      .update({ status: "cleared", cooldown_ends_at: null })
      .eq("target_sos_id", targetSosId)
      .eq("status", "pending");
    if (error) throw error;
  },

  async getExpiredPendingClears(now: number): Promise<ResolveReceipt[]> {
    if (!isSupabaseConfigured || !supabase) {
      return resolveStore.promoteExpiredClears(now);
    }
    const { data, error } = await supabase
      .from(RECEIPTS_TABLE)
      .select("id, target_sos_id, target_sos_author, status, quorum_needed, quorum_seen, witness_device_ids, created_at, cooldown_ends_at, disputed_reason")
      .eq("status", "pending")
      .lte("cooldown_ends_at", now);
    if (error) throw error;
    const out: ResolveReceipt[] = [];
    for (const row of data ?? []) {
      const r = fromRow(row);
      await this.markCleared(r.targetSosId);
      r.status = "cleared";
      r.cooldownEndsAt = undefined;
      out.push(r);
    }
    return out;
  },

  /**
   * Hard-delete receipts past their retention window in terminal states.
   * Witnesses cascade via the FK `on delete cascade` in schema.sql.
   * Keeps the table bounded — see SECURITY-AUDIT.md [M10].
   * Returns the count deleted (best-effort, 0 when Supabase is not configured).
   */
  async deleteTerminalReceiptsOlderThan(olderThanMs: number, statuses: string[] = ["cleared", "rejected"]): Promise<number> {
    if (!isSupabaseConfigured || !supabase) return 0;
    const cutoffIso = new Date(Date.now() - olderThanMs).toISOString();
    const { count, error } = await supabase
      .from(RECEIPTS_TABLE)
      .delete({ count: "exact" })
      .in("status", statuses)
      .lt("created_at", cutoffIso);
    if (error) throw error;
    return count ?? 0;
  },
};

function fromRow(r: any): ResolveReceipt {
  return {
    id: r.id,
    targetSosId: r.target_sos_id,
    targetSosAuthor: r.target_sos_author,
    status: r.status,
    quorumNeeded: r.quorum_needed,
    quorumSeen: r.quorum_seen,
    witnessDeviceIds: r.witness_device_ids ?? [],
    createdAt: Date.parse(r.created_at) || 0,
    cooldownEndsAt: r.cooldown_ends_at ?? undefined,
    disputedReason: r.disputed_reason ?? undefined,
  };
}
