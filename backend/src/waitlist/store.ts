export interface WaitlistEntry {
  email: string;
  country: string;
  createdAt: number;
}

// Append-only. Dedup key: normalized email (not a content hash — unlike
// ChannelRecord/LocationPoint, waitlist signups aren't part of the mesh
// protocol, so the email itself is the natural unique key). Mirrors
// locationsStore/channelsStore in shape.
const entries = new Map<string, WaitlistEntry>();

export const waitlistStore = {
  /** @returns true if new, false if this email was already on the list. */
  add(entry: WaitlistEntry): boolean {
    if (entries.has(entry.email)) return false;
    entries.set(entry.email, entry);
    return true;
  },

  size(): number {
    return entries.size;
  },

  clear(): void {
    entries.clear();
  },
};
