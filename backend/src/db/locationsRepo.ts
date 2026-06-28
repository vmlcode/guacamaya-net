import { LocationPoint } from "@guacamaya/shared";
import { supabase, isSupabaseConfigured } from "./supabase.js";
import { locationsStore } from "../locations/store.js";

const TABLE = "location_points";

/**
 * Durable data-access layer for location points.
 *
 * Mirrors locationsStore but persists to Supabase when configured.
 * Dedup is by `id` (SHA-256 of canonical content) — same strategy as channelsRepo.
 * Falls back to in-memory store so local dev works without a database.
 */
export const locationsRepo = {
  /**
   * Persists a batch of points; duplicates (same `id`) are silently ignored.
   * @returns count of newly inserted points.
   */
  async addPoints(batch: LocationPoint[]): Promise<number> {
    if (batch.length === 0) return 0;

    if (!isSupabaseConfigured || !supabase) {
      let count = 0;
      for (const p of batch) {
        if (locationsStore.add(p)) count++;
      }
      return count;
    }

    const rows = batch.map((p) => ({
      id: p.id,
      device_id: p.deviceId,
      lat: p.lat,
      lon: p.lon,
      timestamp: p.timestamp,
      accuracy: p.accuracy ?? null,
    }));

    const { data, error } = await supabase
      .from(TABLE)
      .upsert(rows, { onConflict: "id", ignoreDuplicates: true })
      .select("id");

    if (error) throw error;
    return data?.length ?? 0;
  },

  /** Points after `since` ms, optionally filtered by device, oldest first. */
  async getPoints(since = 0, deviceId?: string): Promise<LocationPoint[]> {
    if (!isSupabaseConfigured || !supabase) {
      return locationsStore.getPoints(since, deviceId);
    }

    let query = supabase
      .from(TABLE)
      .select("id, device_id, lat, lon, timestamp, accuracy")
      .gt("timestamp", since)
      .order("timestamp", { ascending: true });

    if (deviceId) {
      query = query.eq("device_id", deviceId);
    }

    const { data, error } = await query;
    if (error) throw error;

    return (data ?? []).map((row: any) => ({
      id: row.id,
      deviceId: row.device_id,
      lat: row.lat,
      lon: row.lon,
      timestamp: row.timestamp,
      accuracy: row.accuracy ?? undefined,
    }));
  },
};
