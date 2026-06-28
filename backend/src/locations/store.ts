import { LocationPoint } from "@guacamaya/shared";

// Append-only. Dedup key: `id` (content hash). Mirrors channelsStore.
const points = new Map<string, LocationPoint>();

export const locationsStore = {
  /** @returns true if new, false if duplicate. */
  add(p: LocationPoint): boolean {
    if (points.has(p.id)) return false;
    points.set(p.id, p);
    return true;
  },

  /** All points after `since` ms, optionally filtered by device, oldest first. */
  getPoints(since = 0, deviceId?: string): LocationPoint[] {
    return Array.from(points.values())
      .filter((p) => p.timestamp > since && (!deviceId || p.deviceId === deviceId))
      .sort((a, b) => a.timestamp - b.timestamp);
  },

  clear(): void {
    points.clear();
  },
};
