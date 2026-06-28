import { createClient, type SupabaseClient } from "@supabase/supabase-js";

/**
 * Supabase connection for the Guacamaya backend.
 *
 * The backend is a trusted server, so it authenticates with the
 * SERVICE ROLE key (bypasses Row Level Security). This key must never be
 * shipped to the app — clients only ever talk to the backend's HTTP/WS API.
 *
 * If the credentials are absent the client stays `null` and the data layer
 * falls back to the in-memory store, so `bun run dev` works out of the box
 * without a configured database.
 */
const SUPABASE_URL = process.env.SUPABASE_URL;
const SUPABASE_SERVICE_ROLE_KEY = process.env.SUPABASE_SERVICE_ROLE_KEY;

export const isSupabaseConfigured = Boolean(
  SUPABASE_URL && SUPABASE_SERVICE_ROLE_KEY
);

export const supabase: SupabaseClient | null = isSupabaseConfigured
  ? createClient(SUPABASE_URL!, SUPABASE_SERVICE_ROLE_KEY!, {
      auth: {
        // Server-side usage: no session persistence or token refresh needed.
        persistSession: false,
        autoRefreshToken: false,
      },
    })
  : null;
