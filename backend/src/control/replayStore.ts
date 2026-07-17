/**
 * In-memory replay guard for verified control frames (delivery /
 * backend-resolve / self-cancel). The Supabase-backed repo falls back to this
 * store when the database is not configured (dev/test — same three-layer
 * pattern as channels/locations/ledger).
 *
 * Keyed by the 64-byte Ed25519 signature hex: a control frame is admitted once
 * even if it is re-uploaded by several data mules or relayed across hops.
 * `expiresAtMs` lets callers prune entries whose underlying frame has expired.
 */
export const controlReplayStore = {
  _map: new Map<string, number>(),

  has(signatureHex: string): boolean {
    return this._map.has(signatureHex);
  },

  /**
   * Admit a freshly-verified control frame. Returns true iff this signature had
   * not been seen before (or its previous entry already expired and was pruned).
   */
  admit(signatureHex: string, expiresAtMs: number, nowMs: number): boolean {
    const existing = this._map.get(signatureHex);
    if (existing !== undefined && existing > nowMs) return false;
    this._map.set(signatureHex, expiresAtMs);
    return true;
  },

  /** Delete entries whose frame has already expired. */
  prune(nowMs: number): number {
    let removed = 0;
    for (const [sig, exp] of this._map) {
      if (exp <= nowMs) {
        this._map.delete(sig);
        removed++;
      }
    }
    return removed;
  },

  reset(): void {
    this._map.clear();
  },
};
