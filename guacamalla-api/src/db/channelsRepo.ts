import { ChannelRecord, ChannelId } from "@guacamaya/shared";
import { supabase, isSupabaseConfigured } from "./supabase.js";
import { channelsStore } from "../channels/store.js";

const TABLE = "channel_records";

/**
 * Durable data-access layer for channel records.
 *
 * Mirrors the in-memory `channelsStore` interface but persists to Supabase
 * (Postgres) when configured. Falls back to the in-memory store otherwise, so
 * local development and tests run without a database.
 *
 * Dedupe is by `id` (SHA-256 of canonical content) — the same union+dedupe
 * the mesh gossip log performs on devices.
 */
export const channelsRepo = {
  /**
   * Persists a record unless its `id` already exists.
   * @returns true if inserted, false if it was a duplicate.
   */
  async addRecord(record: ChannelRecord): Promise<boolean> {
    if (!isSupabaseConfigured || !supabase) {
      return channelsStore.addRecord(record);
    }

    // ignoreDuplicates → no-op on id conflict; `select` returns inserted rows.
    const { data, error } = await supabase
      .from(TABLE)
      .upsert(record, { onConflict: "id", ignoreDuplicates: true })
      .select("id");

    if (error) throw error;
    return (data?.length ?? 0) > 0;
  },

  /**
   * Records of a channel after `since` (unix ms), oldest first.
   */
  async getRecords(channel: ChannelId, since: number = 0): Promise<ChannelRecord[]> {
    if (!isSupabaseConfigured || !supabase) {
      return channelsStore.getRecords(channel, since);
    }

    const { data, error } = await supabase
      .from(TABLE)
      .select("id, channel, timestamp, ttl, author, verified, payload, sig")
      .eq("channel", channel)
      .gt("timestamp", since)
      .order("timestamp", { ascending: true });

    if (error) throw error;
    return (data ?? []) as ChannelRecord[];
  },

  /**
   * All records after `since` (unix ms), oldest first. Used by data-mule sync.
   */
  async getAllRecords(since: number = 0): Promise<ChannelRecord[]> {
    if (!isSupabaseConfigured || !supabase) {
      return channelsStore.getAllRecords(since);
    }

    const { data, error } = await supabase
      .from(TABLE)
      .select("id, channel, timestamp, ttl, author, verified, payload, sig")
      .gt("timestamp", since)
      .order("timestamp", { ascending: true });

    if (error) throw error;
    return (data ?? []) as ChannelRecord[];
  },

  /**
   * Fetch a single record by id. Used by /resolve to look up the original SOS
   * (geo + author + timestamp gates need it). Returns null when not found.
   */
  async getById(id: string): Promise<ChannelRecord | null> {
    if (!isSupabaseConfigured || !supabase) {
      return channelsStore.getById(id);
    }
    const { data, error } = await supabase
      .from(TABLE)
      .select("id, channel, timestamp, ttl, author, verified, payload, sig")
      .eq("id", id)
      .maybeSingle();
    if (error) throw error;
    return (data as ChannelRecord | null) ?? null;
  },
};
