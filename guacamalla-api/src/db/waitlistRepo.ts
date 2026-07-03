import { supabase, isSupabaseConfigured } from "./supabase.js";
import { waitlistStore, type WaitlistEntry } from "../waitlist/store.js";

const TABLE = "waitlist_entries";

/**
 * Durable data-access layer for waitlist signups.
 *
 * Mirrors locationsRepo/channelsRepo: Supabase when configured, in-memory
 * fallback otherwise. Dedup key is the normalized email — waitlist signups
 * aren't part of the mesh protocol, so there's no canonical content hash to
 * dedupe by like `getRecordId`/`getLocationId`.
 */
export const waitlistRepo = {
  /** @returns true if this is a new signup, false if the email was already on the list. */
  async add(entry: WaitlistEntry): Promise<boolean> {
    if (!isSupabaseConfigured || !supabase) {
      return waitlistStore.add(entry);
    }

    const { data, error } = await supabase
      .from(TABLE)
      .upsert(
        {
          email: entry.email,
          country: entry.country,
          created_at: new Date(entry.createdAt).toISOString(),
        },
        { onConflict: "email", ignoreDuplicates: true },
      )
      .select("email");

    if (error) throw error;
    return (data?.length ?? 0) > 0;
  },
};
