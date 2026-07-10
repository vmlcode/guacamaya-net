import type { LedgerEvent, SosState, EventId } from "@guacamaya/shared";

export type ChainStatus = "attached" | "orphan" | "conflict";

export interface RejectedEvent {
  rejectionId: string;
  claimedEventId?: string;
  sosId?: string;
  reason: string;
  rawEvent: unknown;
  receivedAt: number;
}

export interface StoredLedgerEvent {
  event: LedgerEvent;
  receivedAt: number;
  chainStatus: ChainStatus;
}

interface MemoryRecord {
  event: LedgerEvent;
  receivedAt: number;
  chainStatus: ChainStatus;
}

const events = new Map<EventId, MemoryRecord>();
const bySosId = new Map<string, EventId[]>();
const states = new Map<string, SosState>();
const rejected: RejectedEvent[] = [];
let rejectionCounter = 0;

function compareReceived(a: MemoryRecord, b: MemoryRecord): number {
  if (a.receivedAt !== b.receivedAt) return a.receivedAt - b.receivedAt;
  return a.event.eventId.localeCompare(b.event.eventId);
}

export const ledgerStore = {
  hasEvent(eventId: EventId): boolean {
    return events.has(eventId);
  },

  getEvent(eventId: EventId): StoredLedgerEvent | undefined {
    const rec = events.get(eventId);
    if (!rec) return undefined;
    return { event: rec.event, receivedAt: rec.receivedAt, chainStatus: rec.chainStatus };
  },

  getEventsBySosId(sosId: string): StoredLedgerEvent[] {
    const ids = bySosId.get(sosId) ?? [];
    const out: StoredLedgerEvent[] = [];
    for (const id of ids) {
      const rec = events.get(id);
      if (rec) out.push({ event: rec.event, receivedAt: rec.receivedAt, chainStatus: rec.chainStatus });
    }
    return out;
  },

  putEvent(event: LedgerEvent, receivedAt: number, chainStatus: ChainStatus): void {
    if (events.has(event.eventId)) return;
    events.set(event.eventId, { event, receivedAt, chainStatus });
    const list = bySosId.get(event.sosId) ?? [];
    list.push(event.eventId);
    bySosId.set(event.sosId, list);
  },

  setChainStatus(eventId: EventId, status: ChainStatus): void {
    const rec = events.get(eventId);
    if (rec) rec.chainStatus = status;
  },

  getSosState(sosId: string): SosState | undefined {
    return states.get(sosId);
  },

  putSosState(state: SosState): void {
    states.set(state.sosId, state);
  },

  clearSosState(sosId: string): void {
    states.delete(sosId);
  },

  addRejected(input: Omit<RejectedEvent, "rejectionId">): RejectedEvent {
    const rejectionId = `rej-${input.receivedAt}-${rejectionCounter++}`;
    const entry: RejectedEvent = { rejectionId, ...input };
    rejected.push(entry);
    return entry;
  },

  listRejected(since: number, limit: number): RejectedEvent[] {
    return rejected
      .filter((r) => r.receivedAt >= since)
      .sort((a, b) => a.receivedAt - b.receivedAt)
      .slice(0, limit);
  },

  listEventsAfter(cursor: { receivedAt: number; eventId: EventId } | null, limit: number): StoredLedgerEvent[] {
    const all = [...events.values()].sort(compareReceived);
    if (!cursor) {
      return all.slice(0, limit).map((rec) => ({
        event: rec.event,
        receivedAt: rec.receivedAt,
        chainStatus: rec.chainStatus,
      }));
    }
    const idx = all.findIndex((r) => r.event.eventId === cursor.eventId);
    if (idx === -1) {
      return all
        .filter(
          (r) =>
            r.receivedAt > cursor.receivedAt ||
            (r.receivedAt === cursor.receivedAt && r.event.eventId > cursor.eventId),
        )
        .slice(0, limit)
        .map((rec) => ({
          event: rec.event,
          receivedAt: rec.receivedAt,
          chainStatus: rec.chainStatus,
        }));
    }
    return all.slice(idx + 1, idx + 1 + limit).map((rec) => ({
      event: rec.event,
      receivedAt: rec.receivedAt,
      chainStatus: rec.chainStatus,
    }));
  },

  reset(): void {
    events.clear();
    bySosId.clear();
    states.clear();
    rejected.length = 0;
    rejectionCounter = 0;
  },
};
