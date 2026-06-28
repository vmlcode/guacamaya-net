import type {
  ResolveReceipt,
  ResolveStatus,
  ResolveWitness,
} from "@guacamaya/shared";

/**
 * In-memory fallback for the resolve flow. Mirrors the channelsStore /
 * locationsStore pattern: append-only, dedup by `(targetSosId, witnessDeviceId)`.
 *
 * One receipt per target tracks accumulating witnesses across submissions.
 * Quorum threshold and cooldown are applied by the routes layer; this store
 * just records what was accepted and exposes transitions for the cooldown
 * sweeper and the originator-veto hook.
 */

interface PendingClear {
  receiptId: string;
  cooldownEndsAt: number;
  disputed: boolean;
  disputedReason?: string;
}

const MAX_PENDING = 10_000;

const receiptsByTarget = new Map<string, ResolveReceipt>();
const receiptsById = new Map<string, ResolveReceipt>();
const witnessesByTarget = new Map<string, ResolveWitness[]>();
const witnessIndex = new Set<string>(); // `${targetSosId}::${deviceId}`
const pendingClears = new Map<string, PendingClear>();
const vetoMutex = new Set<string>();

function key(target: string, deviceId: string): string {
  return `${target}::${deviceId}`;
}

export const resolveStore = {
  hasWitness(targetSosId: string, deviceId: string): boolean {
    return witnessIndex.has(key(targetSosId, deviceId));
  },

  /**
   * Records a witness for the target if not already present.
   * @returns true if newly added, false if duplicate.
   */
  recordWitness(targetSosId: string, witness: ResolveWitness): boolean {
    const k = key(targetSosId, witness.deviceId);
    if (witnessIndex.has(k)) return false;
    witnessIndex.add(k);
    const list = witnessesByTarget.get(targetSosId) ?? [];
    list.push(witness);
    witnessesByTarget.set(targetSosId, list);
    return true;
  },

  getWitnesses(targetSosId: string): ResolveWitness[] {
    return witnessesByTarget.get(targetSosId) ?? [];
  },

  quorumSeen(targetSosId: string): number {
    return witnessesByTarget.get(targetSosId)?.length ?? 0;
  },

  upsertReceipt(receipt: ResolveReceipt): void {
    receiptsByTarget.set(receipt.targetSosId, receipt);
    receiptsById.set(receipt.id, receipt);
  },

  getReceiptById(id: string): ResolveReceipt | undefined {
    return receiptsById.get(id);
  },

  getReceiptByTarget(targetSosId: string): ResolveReceipt | undefined {
    return receiptsByTarget.get(targetSosId);
  },

  getStatus(targetSosId: string): ResolveStatus | undefined {
    return receiptsByTarget.get(targetSosId)?.status;
  },

  markPendingClear(targetSosId: string, receiptId: string, cooldownEndsAt: number): void {
    if (pendingClears.size >= MAX_PENDING) {
      // Evict oldest pending (FIFO by insertion order).
      const firstKey = pendingClears.keys().next().value;
      if (firstKey) pendingClears.delete(firstKey);
    }
    pendingClears.set(targetSosId, { receiptId, cooldownEndsAt, disputed: false });
  },

  getPendingClear(targetSosId: string): PendingClear | undefined {
    return pendingClears.get(targetSosId);
  },

  /** Returns all pending-clears for targets originating from `authorDeviceId`. */
  getPendingClearsByAuthor(authorDeviceId: string): PendingClear[] {
    const out: PendingClear[] = [];
    for (const [target, pc] of pendingClears) {
      const r = receiptsByTarget.get(target);
      if (r?.targetSosAuthor === authorDeviceId) out.push(pc);
    }
    return out;
  },

  /** Find the pending-clear target for a re-fired SOS frame from the same originator. */
  findPendingClearForAuthor(authorDeviceId: string, excludeTargetId: string): string | null {
    for (const [target, pc] of pendingClears) {
      if (pc.disputed) continue;
      if (target === excludeTargetId) continue;
      const r = receiptsByTarget.get(target);
      if (r?.targetSosAuthor === authorDeviceId) return target;
    }
    return null;
  },

  autoDispute(targetSosId: string, reason: string): ResolveReceipt | undefined {
    const pc = pendingClears.get(targetSosId);
    if (!pc) return undefined;
    pc.disputed = true;
    pc.disputedReason = reason;
    const receipt = receiptsByTarget.get(targetSosId);
    if (receipt) {
      receipt.status = "disputed";
      receipt.disputedReason = reason;
      receiptsById.set(receipt.id, receipt);
    }
    return receipt;
  },

  promoteToCleared(targetSosId: string): ResolveReceipt | undefined {
    const pc = pendingClears.get(targetSosId);
    if (!pc || pc.disputed) return undefined;
    pendingClears.delete(targetSosId);
    const receipt = receiptsByTarget.get(targetSosId);
    if (receipt) {
      receipt.status = "cleared";
      receipt.cooldownEndsAt = undefined;
      receiptsById.set(receipt.id, receipt);
    }
    return receipt;
  },

  /** Background sweep — promote pending clears whose cooldown expired. */
  promoteExpiredClears(now: number = Date.now()): ResolveReceipt[] {
    const promoted: ResolveReceipt[] = [];
    for (const [target, pc] of pendingClears) {
      if (pc.disputed) continue;
      if (pc.cooldownEndsAt > now) continue;
      const r = this.promoteToCleared(target);
      if (r) promoted.push(r);
    }
    return promoted;
  },

  /** In-process mutex around the dispute/clear transition (single-process deployment). */
  acquireTargetMutex(targetSosId: string): boolean {
    if (vetoMutex.has(targetSosId)) return false;
    vetoMutex.add(targetSosId);
    return true;
  },

  releaseTargetMutex(targetSosId: string): void {
    vetoMutex.delete(targetSosId);
  },

  /** Test-only. */
  clear(): void {
    receiptsByTarget.clear();
    receiptsById.clear();
    witnessesByTarget.clear();
    witnessIndex.clear();
    pendingClears.clear();
    vetoMutex.clear();
  },
};
