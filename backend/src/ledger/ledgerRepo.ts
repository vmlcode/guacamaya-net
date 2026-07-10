import {
  reconcileState,
  type LedgerEvent,
  type SosState,
  type EventId,
  type LedgerRejectReason,
  type ReconcileResult,
} from "@guacamaya/shared";
import { supabase, isSupabaseConfigured } from "../db/supabase.js";
import {
  ledgerStore,
  type ChainStatus,
  type RejectedEvent,
  type StoredLedgerEvent,
} from "./store.js";

const EVENTS_TABLE = "ledger_events";
const STATE_TABLE = "sos_state";
const REJECTED_TABLE = "ledger_rejected_events";

export interface LedgerCursor {
  receivedAt: number;
  eventId: EventId;
}

export type { ChainStatus, RejectedEvent, StoredLedgerEvent };

// ─── bytes<->hex ─────────────────────────────────────────────────────────────

function bytesToHex(bytes: Uint8Array): string {
  let out = "";
  for (let i = 0; i < bytes.length; i++) out += bytes[i]!.toString(16).padStart(2, "0");
  return out;
}

function hexToBytes(hex: string): Uint8Array {
  const out = new Uint8Array(Math.floor(hex.length / 2));
  for (let i = 0; i < out.length; i++) out[i] = parseInt(hex.substr(i * 2, 2), 16);
  return out;
}

// ─── JSONB (de)serialization: Uint8Array <-> { $bytes: hex } ─────────────────

function toJsonValue(v: unknown): unknown {
  if (v instanceof Uint8Array) return { $bytes: bytesToHex(v) };
  if (Array.isArray(v)) return v.map(toJsonValue);
  if (v && typeof v === "object") {
    const out: Record<string, unknown> = {};
    const src = v as Record<string, unknown>;
    for (const k of Object.keys(src)) out[k] = toJsonValue(src[k]);
    return out;
  }
  return v;
}

function fromJsonValue(v: unknown): unknown {
  if (Array.isArray(v)) return v.map(fromJsonValue);
  if (v && typeof v === "object") {
    const obj = v as Record<string, unknown>;
    const keys = Object.keys(obj);
    if (keys.length === 1 && typeof obj.$bytes === "string") {
      return hexToBytes(obj.$bytes);
    }
    const out: Record<string, unknown> = {};
    for (const k of keys) out[k] = fromJsonValue(obj[k]);
    return out;
  }
  return v;
}

// ─── Row mapping ─────────────────────────────────────────────────────────────

function eventToRow(event: LedgerEvent, receivedAt: number, chainStatus: ChainStatus) {
  return {
    event_id: event.eventId,
    sos_id: event.sosId,
    type: event.type,
    previous_sos_event_hash: event.previousSosEventHash ?? null,
    issued_at: event.issuedAt,
    issuer_public_key: bytesToHex(event.issuerPublicKey),
    payload: toJsonValue(event.payload),
    payload_hash: bytesToHex(event.payloadHash),
    proof: toJsonValue(event.proof),
    event_signature: event.eventSignature ? bytesToHex(event.eventSignature) : null,
    chain_status: chainStatus,
    received_at: receivedAt,
  };
}

function rowToEvent(row: Record<string, unknown>): LedgerEvent {
  return {
    version: 1,
    eventId: row.event_id as EventId,
    sosId: row.sos_id as string,
    type: row.type as LedgerEvent["type"],
    previousSosEventHash: (row.previous_sos_event_hash as string | null) ?? null,
    issuedAt: row.issued_at as number,
    issuerPublicKey: hexToBytes(row.issuer_public_key as string),
    payload: fromJsonValue(row.payload) as LedgerEvent["payload"],
    payloadHash: hexToBytes(row.payload_hash as string),
    payloadVersion: 1,
    proof: fromJsonValue(row.proof) as LedgerEvent["proof"],
    eventSignature: row.event_signature ? hexToBytes(row.event_signature as string) : undefined,
  };
}

function rowToStored(row: Record<string, unknown>): StoredLedgerEvent {
  return {
    event: rowToEvent(row),
    receivedAt: row.received_at as number,
    chainStatus: row.chain_status as ChainStatus,
  };
}

function stateToRow(state: SosState) {
  return {
    sos_id: state.sosId,
    current_type: state.type,
    terminal: state.terminal,
    last_event_id: state.lastEventId,
    last_event_at: state.lastEventAt,
    witness_count: state.witnessCount,
    retention_until: state.retentionUntil ?? null,
    updated_at: Date.now(),
  };
}

function rowToState(row: Record<string, unknown>): SosState {
  return {
    sosId: row.sos_id as string,
    type: row.current_type as SosState["type"],
    terminal: row.terminal as boolean,
    lastEventId: row.last_event_id as EventId,
    lastEventAt: row.last_event_at as number,
    witnessCount: row.witness_count as number,
    retentionUntil: (row.retention_until as number | null) ?? null,
  };
}

// ─── Repository ──────────────────────────────────────────────────────────────

export const ledgerRepo = {
  async upsertEvent(event: LedgerEvent): Promise<{ inserted: boolean; chainStatus: ChainStatus }> {
    if (!isSupabaseConfigured || !supabase) {
      if (ledgerStore.hasEvent(event.eventId)) {
        const existing = ledgerStore.getEvent(event.eventId)!;
        return { inserted: false, chainStatus: existing.chainStatus };
      }
      ledgerStore.putEvent(event, Date.now(), "orphan");
      const result = await this.reconcile(event.sosId);
      const chainStatus = this.classifyAfter(result, event.eventId);
      ledgerStore.setChainStatus(event.eventId, chainStatus);
      return { inserted: true, chainStatus };
    }

    const { data: existing } = await supabase
      .from(EVENTS_TABLE)
      .select("event_id, chain_status")
      .eq("event_id", event.eventId)
      .maybeSingle();
    if (existing) {
      return { inserted: false, chainStatus: (existing as Record<string, unknown>).chain_status as ChainStatus };
    }

    const { error } = await supabase
      .from(EVENTS_TABLE)
      .upsert(eventToRow(event, Date.now(), "orphan"), {
        onConflict: "event_id",
        ignoreDuplicates: true,
      });
    if (error) throw error;

    const result = await this.reconcile(event.sosId);
    return { inserted: true, chainStatus: this.classifyAfter(result, event.eventId) };
  },

  classifyAfter(result: ReconcileResult, eventId: EventId): ChainStatus {
    if (result.attached.includes(eventId)) return "attached";
    if (result.conflicts.includes(eventId)) return "conflict";
    return "orphan";
  },

  async getEvent(eventId: EventId): Promise<StoredLedgerEvent | null> {
    if (!isSupabaseConfigured || !supabase) {
      return ledgerStore.getEvent(eventId) ?? null;
    }
    const { data, error } = await supabase
      .from(EVENTS_TABLE)
      .select("*")
      .eq("event_id", eventId)
      .maybeSingle();
    if (error) throw error;
    return data ? rowToStored(data as Record<string, unknown>) : null;
  },

  async getEventsBySosId(sosId: string): Promise<StoredLedgerEvent[]> {
    if (!isSupabaseConfigured || !supabase) {
      return ledgerStore.getEventsBySosId(sosId);
    }
    const { data, error } = await supabase
      .from(EVENTS_TABLE)
      .select("*")
      .eq("sos_id", sosId)
      .order("issued_at", { ascending: true })
      .order("event_id", { ascending: true });
    if (error) throw error;
    return (data ?? []).map((r) => rowToStored(r as Record<string, unknown>));
  },

  async listEventsAfter(cursor: LedgerCursor | null, limit: number): Promise<StoredLedgerEvent[]> {
    if (!isSupabaseConfigured || !supabase) {
      return ledgerStore.listEventsAfter(cursor, limit);
    }
    let query = supabase
      .from(EVENTS_TABLE)
      .select("*")
      .order("received_at", { ascending: true })
      .order("event_id", { ascending: true })
      .limit(limit);
    if (cursor) {
      query = query.or(
        `and(received_at.gt.${cursor.receivedAt}),and(received_at.eq.${cursor.receivedAt},event_id.gt.${cursor.eventId})`,
      );
    }
    const { data, error } = await query;
    if (error) throw error;
    return (data ?? []).map((r) => rowToStored(r as Record<string, unknown>));
  },

  async addRejected(input: {
    rawEvent: unknown;
    reason: LedgerRejectReason;
    claimedEventId?: string;
    sosId?: string;
  }): Promise<RejectedEvent> {
    const receivedAt = Date.now();
    if (!isSupabaseConfigured || !supabase) {
      return ledgerStore.addRejected({
        claimedEventId: input.claimedEventId,
        sosId: input.sosId,
        reason: input.reason,
        rawEvent: input.rawEvent,
        receivedAt,
      });
    }
    const row = {
      claimed_event_id: input.claimedEventId ?? null,
      sos_id: input.sosId ?? null,
      reason: input.reason,
      raw_event: toJsonValue(input.rawEvent),
      received_at: receivedAt,
    };
    const { data, error } = await supabase
      .from(REJECTED_TABLE)
      .insert(row)
      .select("rejection_id")
      .maybeSingle();
    if (error) throw error;
    return {
      rejectionId: (data as Record<string, unknown> | null)?.rejection_id as string | undefined ?? "",
      claimedEventId: input.claimedEventId,
      sosId: input.sosId,
      reason: input.reason,
      rawEvent: input.rawEvent,
      receivedAt,
    };
  },

  async listRejected(since: number, limit: number): Promise<RejectedEvent[]> {
    if (!isSupabaseConfigured || !supabase) {
      return ledgerStore.listRejected(since, limit);
    }
    const { data, error } = await supabase
      .from(REJECTED_TABLE)
      .select("*")
      .gte("received_at", since)
      .order("received_at", { ascending: true })
      .limit(limit);
    if (error) throw error;
    return (data ?? []).map((r) => {
      const row = r as Record<string, unknown>;
      return {
        rejectionId: row.rejection_id as string,
        claimedEventId: (row.claimed_event_id as string | null) ?? undefined,
        sosId: (row.sos_id as string | null) ?? undefined,
        reason: row.reason as string,
        rawEvent: fromJsonValue(row.raw_event),
        receivedAt: row.received_at as number,
      };
    });
  },

  async getSosState(sosId: string): Promise<SosState | null> {
    if (!isSupabaseConfigured || !supabase) {
      return ledgerStore.getSosState(sosId) ?? null;
    }
    const { data, error } = await supabase
      .from(STATE_TABLE)
      .select("*")
      .eq("sos_id", sosId)
      .maybeSingle();
    if (error) throw error;
    return data ? rowToState(data as Record<string, unknown>) : null;
  },

  async reconcile(sosId: string): Promise<ReconcileResult> {
    const stored = await this.getEventsBySosId(sosId);
    const events = stored.map((s) => s.event);
    const result = reconcileState(events);

    const batches: Array<{ ids: EventId[]; status: ChainStatus }> = [
      { ids: result.attached, status: "attached" },
      { ids: result.conflicts, status: "conflict" },
      { ids: result.orphans, status: "orphan" },
    ];
    for (const batch of batches) {
      if (batch.ids.length === 0) continue;
      if (!isSupabaseConfigured || !supabase) {
        for (const id of batch.ids) ledgerStore.setChainStatus(id, batch.status);
      } else {
        const { error } = await supabase
          .from(EVENTS_TABLE)
          .update({ chain_status: batch.status })
          .in("event_id", batch.ids);
        if (error) throw error;
      }
    }

    if (result.state) {
      await this.putSosState(result.state);
    } else {
      await this.clearSosState(sosId);
    }
    return result;
  },

  async putSosState(state: SosState): Promise<void> {
    if (!isSupabaseConfigured || !supabase) {
      ledgerStore.putSosState(state);
      return;
    }
    const { error } = await supabase.from(STATE_TABLE).upsert(stateToRow(state), {
      onConflict: "sos_id",
    });
    if (error) throw error;
  },

  async clearSosState(sosId: string): Promise<void> {
    if (!isSupabaseConfigured || !supabase) {
      ledgerStore.clearSosState(sosId);
      return;
    }
    const { error } = await supabase.from(STATE_TABLE).delete().eq("sos_id", sosId);
    if (error) throw error;
  },
};
