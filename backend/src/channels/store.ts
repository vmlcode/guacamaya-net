import { ChannelRecord, ChannelId } from "@guacamaya/shared";

// In-memory storage for channel records, keyed by ID for O(1) deduplication
const logStore = new Map<string, ChannelRecord>();

export const channelsStore = {
  /**
   * Adds a record if it is not already in the store.
   * @returns true if the record was added, false if it was a duplicate and skipped.
   */
  addRecord(record: ChannelRecord): boolean {
    if (logStore.has(record.id)) {
      return false; // Deduplicated
    }
    logStore.set(record.id, record);
    return true;
  },

  /**
   * Returns records belonging to a channel after a given timestamp (ms), sorted chronologically.
   */
  getRecords(channel: ChannelId, since: number = 0): ChannelRecord[] {
    return Array.from(logStore.values())
      .filter((rec) => rec.channel === channel && rec.timestamp > since)
      .sort((a, b) => a.timestamp - b.timestamp);
  },

  /**
   * Returns all records in the store after a given timestamp (ms), sorted chronologically.
   */
  getAllRecords(since: number = 0): ChannelRecord[] {
    return Array.from(logStore.values())
      .filter((rec) => rec.timestamp > since)
      .sort((a, b) => a.timestamp - b.timestamp);
  },

  /**
   * Gets the total number of records currently stored.
   */
  size(): number {
    return logStore.size;
  },

  /**
   * Clears the store.
   */
  clear(): void {
    logStore.clear();
  }
};
