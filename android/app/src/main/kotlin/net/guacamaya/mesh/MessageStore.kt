package net.guacamaya.mesh

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import android.content.Context
import kotlinx.coroutines.flow.Flow

const val DEFAULT_RECENT_LIMIT = 2_000
const val MAX_STORED_MESSAGES = 25_000
const val MAX_NEIGHBOR_OBSERVATIONS_PER_NODE = 50

/**
 * Persistent record of every verified SOS frame received. See docs/protocol-flows.md
 * Flow 2 — written only after the reject cascade passes (CRC, ts-skew, hop-ttl,
 * pubkey-binding, signature).
 */
@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["node_id", "msg_id"], unique = true),
        Index(value = ["received_at"]),
        Index(value = ["node_id"]),
    ],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "node_id") val nodeId: ByteArray,
    @ColumnInfo(name = "msg_id") val msgId: Int,
    @ColumnInfo(name = "ts_unix") val tsUnix: Long,
    @ColumnInfo(name = "lat_e7") val latE7: Int,
    @ColumnInfo(name = "lon_e7") val lonE7: Int,
    @ColumnInfo(name = "sos_type") val sosType: Int,
    @ColumnInfo(name = "critical") val critical: Boolean,
    @ColumnInfo(name = "has_heavy") val hasHeavy: Boolean,
    @ColumnInfo(name = "hop_ttl") val hopTtl: Int,
    @ColumnInfo(name = "battery_bucket") val batteryBucket: Int,
    @ColumnInfo(name = "pubkey") val pubkey: ByteArray,
    @ColumnInfo(name = "payload_raw") val payloadRaw: ByteArray,
    /** 64-byte Ed25519 signature — needed to rebuild the 118 B /ingest frame.
     *  Empty for rows written before schema v3 (excluded from upload). */
    @ColumnInfo(name = "sig", defaultValue = "x''") val sig: ByteArray = ByteArray(0),
    @ColumnInfo(name = "rssi") val rssi: Int,
    @ColumnInfo(name = "received_at") val receivedAt: Long,
    /** Set once the frame has been accepted by the backend's POST /ingest. */
    @ColumnInfo(name = "uploaded", defaultValue = "0") val uploaded: Boolean = false,
    /**
     * True for THIS device's *own* SOS frames, persisted so the data-mule uploader
     * delivers them to the backend when this phone has connectivity. Excluded from
     * the radar / "devices heard" queries below, which mean frames received from
     * *other* nodes — but still eligible for [selectUploadable].
     */
    @ColumnInfo(name = "own", defaultValue = "0") val own: Boolean = false,
) {
    // Room needs equals/hashCode for ByteArray fields.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * Bounded history of authenticated, direct-neighbour radio samples. The observer
 * position belongs to the receiver at the instant of measurement; it is retained
 * for a future cooperative multilateration exchange, never embedded in the SOS.
 */
@Entity(
    tableName = "neighbor_observations",
    indices = [
        Index(value = ["node_id", "observed_at"]),
        Index(value = ["observed_at"]),
    ],
)
data class NeighborObservationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "node_id") val nodeId: String,
    @ColumnInfo(name = "observed_at") val observedAt: Long,
    @ColumnInfo(name = "rssi") val rssi: Int,
    @ColumnInfo(name = "source") val source: Int,
    @ColumnInfo(name = "tx_power") val txPower: Int?,
    @ColumnInfo(name = "primary_phy") val primaryPhy: Int,
    @ColumnInfo(name = "secondary_phy") val secondaryPhy: Int,
    @ColumnInfo(name = "observer_lat_e7") val observerLatE7: Int?,
    @ColumnInfo(name = "observer_lon_e7") val observerLonE7: Int?,
    @ColumnInfo(name = "observer_accuracy_m") val observerAccuracyMeters: Float?,
)

/** Latest filtered state. One row per directly heard node makes the radar cheap
 * to observe and preserves liveness across a process restart. */
@Entity(
    tableName = "neighbor_estimates",
    indices = [Index(value = ["last_seen_at"]), Index(value = ["state"])],
)
data class NeighborEstimateEntity(
    @PrimaryKey
    @ColumnInfo(name = "node_id") val nodeId: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "last_seen_at") val lastSeenAt: Long,
    @ColumnInfo(name = "filtered_rssi") val filteredRssi: Float,
    @ColumnInfo(name = "rssi_variance") val rssiVariance: Float,
    @ColumnInfo(name = "distance_m") val distanceMeters: Float?,
    @ColumnInfo(name = "uncertainty_m") val uncertaintyMeters: Float?,
    @ColumnInfo(name = "source") val source: Int,
    @ColumnInfo(name = "tx_power") val txPower: Int?,
    @ColumnInfo(name = "calibrated") val calibrated: Boolean,
    @ColumnInfo(name = "stable") val stable: Boolean,
    @ColumnInfo(name = "sample_count") val sampleCount: Int,
    @ColumnInfo(name = "state") val state: Int,
)

/**
 * Persistent anti-replay for control frames (delivery 0x01 / backend-resolve 0x02
 * / self-cancel 0x03). Keyed by the 64-byte Ed25519 signature hex — a replay is
 * the exact same frame, so the signature alone is the dedup key. Survives reboot,
 * which the in-memory [DedupeCache] does not: a control frame re-relayed after a
 * restart must not be re-acted-upon. Pruned by [expiresAt] so the table stays
 * bounded under churn. Mirrors `control_replay` on the backend.
 */
@Entity(
    tableName = "control_replay",
    indices = [Index(value = ["expires_at"])],
)
data class ControlReplayEntity(
    /** 64-byte Ed25519 signature hex (128 chars). The dedup key. */
    @PrimaryKey @ColumnInfo(name = "signature_hex") val signatureHex: String,
    /** 0x01 delivery | 0x02 backend-resolve | 0x03 self-cancel. */
    @ColumnInfo(name = "frame_type") val frameType: Int,
    /** sha256(issuer pubkey)[0..3] hex — dedupe namespace / debugging. */
    @ColumnInfo(name = "issuer_node_id") val issuerNodeId: String,
    /** u16 CSPRNG control message id. */
    @ColumnInfo(name = "ctrl_msg_id") val ctrlMsgId: Int,
    /** Unix ms the underlying control frame expires at. Drives pruning. */
    @ColumnInfo(name = "expires_at") val expiresAt: Long,
    @ColumnInfo(name = "received_at") val receivedAt: Long,
)

/**
 * A control receipt this device has verified and acted upon, one row per own SOS
 * `(node_id, msg_id)`. A terminal row (backend-resolve 0x02 or self-cancel 0x03)
 * is the *local* authority to STOP advertising that SOS — sticky across reboots so
 * a resolved SOS does not resume TX. Delivery (0x01) is informational only and is
 * never terminal. The authority is pinned by signature + pubkey so a forged
 * "stop" from a non-backend, non-self issuer cannot flip state.
 */
@Entity(
    tableName = "control_trust",
    primaryKeys = ["node_id", "msg_id"],
    indices = [Index(value = ["terminal"]), Index(value = ["established_at"])],
)
data class ControlTrustEntity(
    /** Self node id (sha256(pubkey)[0..3] hex) — the SOS originator. */
    @ColumnInfo(name = "node_id") val nodeId: String,
    @ColumnInfo(name = "msg_id") val msgId: Int,
    /** True once a terminal receipt (0x02 / 0x03) was verified → stop TX. */
    @ColumnInfo(name = "terminal") val terminal: Boolean,
    /** Control type that established the current state (0x01/0x02/0x03). */
    @ColumnInfo(name = "resolve_type") val resolveType: Int,
    /** sha256(issuer pubkey)[0..3] hex — backend for 0x02, self for 0x03. */
    @ColumnInfo(name = "issuer_node_id") val issuerNodeId: String,
    /** Issuer pubkey hex (backend or self). Re-verifiable against the pinned key. */
    @ColumnInfo(name = "authority_pubkey_hex") val authorityPubkeyHex: String,
    /** Signature hex of the control frame that established this trust. */
    @ColumnInfo(name = "authority_signature_hex") val authoritySignatureHex: String,
    /** Unix ms the underlying control frame expires at. */
    @ColumnInfo(name = "expires_at") val expiresAt: Long,
    /** Unix ms this device acted on the receipt. */
    @ColumnInfo(name = "established_at") val establishedAt: Long,
)

/**
 * A verified self-cancel (0x03) control frame queued for data-mule upload to the
 * backend. Only self-cancel is persisted here: it is the sole mesh-originated
 * control the backend acts on (`processControlFrames` → `SOS_CANCELLED` ledger
 * event). Delivery (0x01) and backend-resolve (0x02) are minted server-side, so
 * re-uploading them would be pure noise.
 *
 * Keyed by the 64-byte Ed25519 signature hex — the same dedup key as
 * [ControlReplayEntity] — so a frame already admitted is inserted exactly once (a
 * replay is rejected by [MessageDao.insertControlReplay] before this insert is ever
 * reached; IGNORE on the PK is the belt-and-braces). [frame] is the canonical 118 B
 * upload blob (payload‖pubkey‖sig, hop-TTL stripped) the backend's `controlFrames[]`
 * field expects — identical layout to a 118 B SOS ingest frame.
 */
@Entity(
    tableName = "outbound_control",
    indices = [Index(value = ["queued_at"])],
)
data class OutboundControlEntity(
    /** 64-byte Ed25519 signature hex (128 chars). The dedup key. */
    @PrimaryKey @ColumnInfo(name = "signature_hex") val signatureHex: String,
    /** 0x03 self-cancel (the only type persisted for upload). */
    @ColumnInfo(name = "frame_type") val frameType: Int,
    /** Canonical 118 B upload frame (payload22 + pubkey32 + sig64, TTL stripped). */
    @ColumnInfo(name = "frame") val frame: ByteArray,
    /** Set once the backend accepted/duplicated the frame in `controlFrames[]`. */
    @ColumnInfo(name = "uploaded", defaultValue = "0") val uploaded: Boolean = false,
    @ColumnInfo(name = "queued_at") val queuedAt: Long,
) {
    // Room needs equals/hashCode for ByteArray fields.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * A locally-authored "Encontrado" (found) frame queued for data-mule upload to
 * the backend (`foundFrames[]` in POST /ingest). Keyed by the 64-byte Ed25519
 * signature hex so the same report is queued exactly once. [frame] is the
 * canonical 118 B upload blob (payload22 ‖ pubkey32 ‖ sig64) built by
 * net.guacamaya.proto.buildFoundFrame.
 */
@Entity(
    tableName = "outbound_found",
    indices = [Index(value = ["queued_at"])],
)
data class OutboundFoundEntity(
    /** 64-byte Ed25519 signature hex (128 chars). The dedup key. */
    @PrimaryKey @ColumnInfo(name = "signature_hex") val signatureHex: String,
    /** Canonical 118 B upload frame (payload22 + pubkey32 + sig64). */
    @ColumnInfo(name = "frame") val frame: ByteArray,
    /** Set once the backend accepted/duplicated the frame in `foundFrames[]`. */
    @ColumnInfo(name = "uploaded", defaultValue = "0") val uploaded: Boolean = false,
    @ColumnInfo(name = "queued_at") val queuedAt: Long,
) {
    // Room needs equals/hashCode for ByteArray fields.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

@Entity(
    tableName = "chat_messages",
    primaryKeys = ["sender_node_id", "message_id"],
    indices = [Index(value = ["timestamp_ms"]), Index(value = ["received_at"])],
)
data class ChatMessageEntity(
    @ColumnInfo(name = "sender_node_id") val senderNodeId: String,
    @ColumnInfo(name = "message_id") val messageId: Long,
    @ColumnInfo(name = "timestamp_ms") val timestampMs: Long,
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "public_key") val publicKey: ByteArray,
    @ColumnInfo(name = "signature") val signature: ByteArray,
    @ColumnInfo(name = "own") val own: Boolean,
    /** 0 queued, 1 advertised, 2 relayed. */
    @ColumnInfo(name = "delivery_state") val deliveryState: Int,
    @ColumnInfo(name = "received_at") val receivedAt: Long,
)

@Entity(
    tableName = "chat_fragments",
    primaryKeys = ["sender_node_id", "message_id", "fragment_index"],
    indices = [Index(value = ["received_at"])],
)
data class ChatFragmentEntity(
    @ColumnInfo(name = "sender_node_id") val senderNodeId: String,
    @ColumnInfo(name = "message_id") val messageId: Long,
    @ColumnInfo(name = "fragment_index") val fragmentIndex: Int,
    @ColumnInfo(name = "fragment_count") val fragmentCount: Int,
    @ColumnInfo(name = "total_length") val totalLength: Int,
    @ColumnInfo(name = "timestamp_seconds") val timestampSeconds: Long,
    @ColumnInfo(name = "chunk") val chunk: ByteArray,
    @ColumnInfo(name = "public_key") val publicKey: ByteArray,
    @ColumnInfo(name = "signature") val signature: ByteArray,
    @ColumnInfo(name = "ttl") val ttl: Int,
    @ColumnInfo(name = "received_at") val receivedAt: Long,
)

@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: MessageEntity): Long

    @Query("SELECT * FROM messages WHERE own = 0 ORDER BY received_at DESC LIMIT :limit")
    fun observeRecent(limit: Int = DEFAULT_RECENT_LIMIT): Flow<List<MessageEntity>>

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM messages WHERE own = 0")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(DISTINCT node_id) FROM messages WHERE own = 0")
    fun observeNodeCount(): Flow<Int>

    /** Latest row per node_id (for map/radar — one device = one entry). */
    @Query(
        """
        SELECT m.* FROM messages m
        INNER JOIN (
            SELECT node_id, MAX(received_at) AS max_received
            FROM messages WHERE own = 0 GROUP BY node_id
        ) g ON m.node_id = g.node_id AND m.received_at = g.max_received
        WHERE m.own = 0
        ORDER BY m.received_at DESC
        LIMIT :limit
        """
    )
    fun observeLatestPerNode(limit: Int = 500): Flow<List<MessageEntity>>

    /**
     * Latest help-request frame per node (newest first), for store-and-forward
     * re-advertising. Excludes presence/heartbeat (sos_type OTHER = 7 and not critical):
     * only aid requests are worth holding and re-broadcasting. See AgePolicy /
     * Payload.isHelpRequest for the matching receive-side predicate.
     */
    @Query(
        """
        SELECT m.* FROM messages m
        INNER JOIN (
            SELECT node_id, MAX(received_at) AS max_received
            FROM messages WHERE own = 0 GROUP BY node_id
        ) g ON m.node_id = g.node_id AND m.received_at = g.max_received
        WHERE m.own = 0 AND (m.critical = 1 OR m.sos_type != 7)
        ORDER BY m.received_at DESC
        LIMIT :limit
        """
    )
    suspend fun latestHelpFramesPerNode(limit: Int): List<MessageEntity>

    /**
     * Keep only the [keep] most-recently-received rows; delete the rest. Called
     * after each insert so the table stays bounded on 1–2 GB devices.
     */
    @Query(
        "DELETE FROM messages WHERE id NOT IN " +
            "(SELECT id FROM messages ORDER BY received_at DESC LIMIT :keep)"
    )
    suspend fun pruneOldKeeping(keep: Int)

    @Query("DELETE FROM messages")
    suspend fun clear()

    /**
     * Oldest-first batch of frames not yet uploaded to the backend. Only rows that
     * carry a full 64-byte signature are eligible — pre-v3 rows (empty sig) cannot
     * rebuild the 118 B /ingest frame and are skipped. Oldest-first so frames near
     * the prune horizon are mule-uploaded before they are evicted.
     */
    @Query(
        "SELECT * FROM messages WHERE uploaded = 0 AND length(sig) = 64 " +
            "ORDER BY received_at ASC LIMIT :limit"
    )
    suspend fun selectUploadable(limit: Int): List<MessageEntity>

    @Query("UPDATE messages SET uploaded = 1 WHERE id IN (:ids)")
    suspend fun markUploaded(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM messages WHERE uploaded = 0 AND length(sig) = 64")
    suspend fun countUploadable(): Int

    @Insert
    suspend fun insertNeighborObservation(entity: NeighborObservationEntity): Long

    @Query(
        "DELETE FROM neighbor_observations WHERE node_id = :nodeId AND id NOT IN " +
            "(SELECT id FROM neighbor_observations WHERE node_id = :nodeId " +
            "ORDER BY observed_at DESC, id DESC LIMIT :keep)",
    )
    suspend fun pruneNeighborObservations(nodeId: String, keep: Int = MAX_NEIGHBOR_OBSERVATIONS_PER_NODE)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNeighborEstimate(entity: NeighborEstimateEntity)

    @Query("SELECT * FROM neighbor_estimates ORDER BY state ASC, filtered_rssi DESC LIMIT :limit")
    fun observeNeighborEstimates(limit: Int = 500): Flow<List<NeighborEstimateEntity>>

    @Query(
        "UPDATE neighbor_estimates SET state = CASE " +
            "WHEN :now - last_seen_at >= :lostAfter THEN 2 " +
            "WHEN :now - last_seen_at >= :degradedAfter THEN 1 " +
            "ELSE 0 END",
    )
    suspend fun refreshNeighborStates(now: Long, degradedAfter: Long, lostAfter: Long)

    // ── Control frames: persistent replay guard + acted-on trust state ─────────

    /**
     * Admit a freshly-verified control frame exactly once per signature. Returns
     * the row id (>0) iff newly inserted; -1 (IGNORE conflict) on a replay. This
     * is the persistent complement to [DedupeCache] — the gate for acting on /
     * relaying a control frame across reboots.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertControlReplay(entity: ControlReplayEntity): Long

    @Query("SELECT EXISTS(SELECT 1 FROM control_replay WHERE signature_hex = :signatureHex)")
    suspend fun hasControlReplay(signatureHex: String): Boolean

    @Query("DELETE FROM control_replay WHERE expires_at < :now")
    suspend fun pruneControlReplay(now: Long): Int

    @Query("SELECT COUNT(*) FROM control_replay")
    suspend fun countControlReplay(): Int

    /**
     * Upsert the trust state for one of this device's own SOS frames. One row per
     * `(node_id, msg_id)`; a terminal upsert is the local authority to stop TX.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertControlTrust(entity: ControlTrustEntity)

    @Query("SELECT * FROM control_trust WHERE node_id = :nodeId AND msg_id = :msgId")
    suspend fun getControlTrust(nodeId: String, msgId: Int): ControlTrustEntity?

    @Query("SELECT * FROM control_trust WHERE node_id = :nodeId AND msg_id = :msgId")
    fun observeControlTrust(nodeId: String, msgId: Int): Flow<ControlTrustEntity?>

    /** Every own SOS currently suppressed by a terminal receipt — consulted on boot. */
    @Query("SELECT * FROM control_trust WHERE terminal = 1")
    suspend fun terminalControlTrusts(): List<ControlTrustEntity>

    @Query("SELECT COUNT(*) FROM control_trust")
    suspend fun countControlTrust(): Int

    @Query("DELETE FROM control_trust")
    suspend fun clearControlTrust()

    // ── Outbound control frames: mule-upload queue (self-cancel → backend) ───────

    /**
     * Queue a verified self-cancel (0x03) for mule upload. IGNORE on the PK means the
     * same frame is queued exactly once even if the caller re-persists it. Returns
     * the row id (>0) iff newly inserted; -1 on a duplicate signature.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOutboundControl(entity: OutboundControlEntity): Long

    /**
     * Oldest-first batch of control frames not yet uploaded, drained alongside SOS
     * frames in the same `/ingest` call (the backend's `controlFrames[]` field).
     */
    @Query("SELECT * FROM outbound_control WHERE uploaded = 0 ORDER BY queued_at ASC LIMIT :limit")
    suspend fun selectUploadableControl(limit: Int): List<OutboundControlEntity>

    @Query("UPDATE outbound_control SET uploaded = 1 WHERE signature_hex IN (:sigs)")
    suspend fun markControlUploaded(sigs: List<String>)

    @Query("SELECT COUNT(*) FROM outbound_control WHERE uploaded = 0")
    suspend fun countUploadableControl(): Int

    // ── Outbound found frames: mule-upload queue ("Encontrado" → backend) ────────

    /** Queue a locally-authored found frame. IGNORE on the PK dedupes by signature. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOutboundFound(entity: OutboundFoundEntity): Long

    /** Oldest-first batch of found frames not yet uploaded, drained alongside SOS
     *  frames in the same `/ingest` call (the backend's `foundFrames[]` field). */
    @Query("SELECT * FROM outbound_found WHERE uploaded = 0 ORDER BY queued_at ASC LIMIT :limit")
    suspend fun selectUploadableFound(limit: Int): List<OutboundFoundEntity>

    @Query("UPDATE outbound_found SET uploaded = 1 WHERE signature_hex IN (:sigs)")
    suspend fun markFoundUploaded(sigs: List<String>)

    @Query("SELECT COUNT(*) FROM outbound_found WHERE uploaded = 0")
    suspend fun countUploadableFound(): Int
}

@Dao
interface ChatDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertChatMessage(entity: ChatMessageEntity): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertChatFragment(entity: ChatFragmentEntity): Long
    @Query("SELECT * FROM chat_messages ORDER BY timestamp_ms ASC LIMIT :limit")
    fun observeChatMessages(limit: Int = 500): Flow<List<ChatMessageEntity>>
    @Query("SELECT * FROM chat_fragments WHERE sender_node_id = :sender AND message_id = :messageId ORDER BY fragment_index ASC")
    suspend fun chatFragments(sender: String, messageId: Long): List<ChatFragmentEntity>
    @Query("DELETE FROM chat_fragments WHERE sender_node_id = :sender AND message_id = :messageId")
    suspend fun deleteChatFragments(sender: String, messageId: Long)
    @Query("UPDATE chat_messages SET delivery_state = :state WHERE sender_node_id = :sender AND message_id = :messageId")
    suspend fun updateChatDelivery(sender: String, messageId: Long, state: Int)
    @Query("DELETE FROM chat_fragments WHERE received_at < :cutoff")
    suspend fun pruneChatFragments(cutoff: Long)
    @Query("DELETE FROM chat_messages WHERE received_at < :cutoff")
    suspend fun pruneChatMessages(cutoff: Long)
}

/**
 * v2 → v3: add the signature and upload-tracking columns required by the data-mule
 * uploader (see net.guacamaya.ingest). Non-destructive — existing collected frames
 * are kept; they simply get an empty sig and are excluded from upload.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN sig BLOB NOT NULL DEFAULT x''")
        db.execSQL("ALTER TABLE messages ADD COLUMN uploaded INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * v3 → v4: add the `own` flag that marks this device's own SOS frames (persisted
 * for data-mule upload but excluded from the radar / "devices heard" queries).
 * Non-destructive — existing rows default to own = 0 (received from other nodes).
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN own INTEGER NOT NULL DEFAULT 0")
    }
}

/** v4 → v5: persist a small, per-neighbour radio history and its filtered state.
 * Keeping this separate from signed SOS frames prevents radio telemetry from
 * changing the protocol's upload or relay semantics. */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS neighbor_observations (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "node_id TEXT NOT NULL, observed_at INTEGER NOT NULL, rssi INTEGER NOT NULL, " +
                "source INTEGER NOT NULL, tx_power INTEGER, primary_phy INTEGER NOT NULL, " +
                "secondary_phy INTEGER NOT NULL, observer_lat_e7 INTEGER, observer_lon_e7 INTEGER, " +
                "observer_accuracy_m REAL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_neighbor_observations_node_id_observed_at ON neighbor_observations (node_id, observed_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_neighbor_observations_observed_at ON neighbor_observations (observed_at)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS neighbor_estimates (" +
                "node_id TEXT NOT NULL PRIMARY KEY, updated_at INTEGER NOT NULL, last_seen_at INTEGER NOT NULL, " +
                "filtered_rssi REAL NOT NULL, rssi_variance REAL NOT NULL, distance_m REAL, uncertainty_m REAL, " +
                "source INTEGER NOT NULL, tx_power INTEGER, calibrated INTEGER NOT NULL, sample_count INTEGER NOT NULL, " +
                "state INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_neighbor_estimates_last_seen_at ON neighbor_estimates (last_seen_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_neighbor_estimates_state ON neighbor_estimates (state)")
    }
}

/** v5 → v6: preserve whether the current time-bounded RSSI median has enough
 * samples. A process restart must not render a fresh one-sample estimate as stable. */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE neighbor_estimates ADD COLUMN stable INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * v6 → v7: persistent control-frame replay guard + acted-on trust state.
 * `control_replay` dedupes verified control frames by signature across reboots
 * (the in-memory DedupeCache is lost on restart); `control_trust` pins, per own
 * SOS, the receipt that authorised stopping TX — sticky so a resolved SOS does
 * not resume advertising. Both tables are independent of signed SOS storage, so
 * the protocol's upload/relay semantics are unchanged.
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS control_replay (" +
                "signature_hex TEXT NOT NULL PRIMARY KEY, " +
                "frame_type INTEGER NOT NULL, issuer_node_id TEXT NOT NULL, " +
                "ctrl_msg_id INTEGER NOT NULL, expires_at INTEGER NOT NULL, " +
                "received_at INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_control_replay_expires_at ON control_replay (expires_at)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS control_trust (" +
                "node_id TEXT NOT NULL, msg_id INTEGER NOT NULL, " +
                "terminal INTEGER NOT NULL, resolve_type INTEGER NOT NULL, " +
                "issuer_node_id TEXT NOT NULL, authority_pubkey_hex TEXT NOT NULL, " +
                "authority_signature_hex TEXT NOT NULL, expires_at INTEGER NOT NULL, " +
                "established_at INTEGER NOT NULL, PRIMARY KEY(node_id, msg_id))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_control_trust_terminal ON control_trust (terminal)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_control_trust_established_at ON control_trust (established_at)")
    }
}

/**
 * v7 → v8: outbound queue for self-cancel (0x03) control frames awaiting data-mule
 * upload to the backend's `SOS_CANCELLED` path. `outbound_control` holds verified
 * self-cancels — the only mesh-originated control that mutates the ledger; delivery
 * (0x01) and backend-resolve (0x02) are minted server-side and are never queued here.
 * Independent of signed SOS storage and of the replay/trust tables, so the protocol's
 * upload/relay semantics are unchanged.
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS outbound_control (" +
                "signature_hex TEXT NOT NULL PRIMARY KEY, " +
                "frame_type INTEGER NOT NULL, frame BLOB NOT NULL, " +
                "uploaded INTEGER NOT NULL DEFAULT 0, queued_at INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_outbound_control_queued_at ON outbound_control (queued_at)")
    }
}

val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS chat_messages (sender_node_id TEXT NOT NULL, message_id INTEGER NOT NULL, timestamp_ms INTEGER NOT NULL, text TEXT NOT NULL, public_key BLOB NOT NULL, signature BLOB NOT NULL, own INTEGER NOT NULL, delivery_state INTEGER NOT NULL, received_at INTEGER NOT NULL, PRIMARY KEY(sender_node_id, message_id))")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_messages_timestamp_ms ON chat_messages (timestamp_ms)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_messages_received_at ON chat_messages (received_at)")
        db.execSQL("CREATE TABLE IF NOT EXISTS chat_fragments (sender_node_id TEXT NOT NULL, message_id INTEGER NOT NULL, fragment_index INTEGER NOT NULL, fragment_count INTEGER NOT NULL, total_length INTEGER NOT NULL, timestamp_seconds INTEGER NOT NULL, chunk BLOB NOT NULL, public_key BLOB NOT NULL, signature BLOB NOT NULL, ttl INTEGER NOT NULL, received_at INTEGER NOT NULL, PRIMARY KEY(sender_node_id, message_id, fragment_index))")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_fragments_received_at ON chat_fragments (received_at)")
    }
}

/**
 * v9 → v10: outbound queue for "Encontrado" (found) frames awaiting data-mule
 * upload to the backend's `foundFrames[]` ingest path. Independent of signed SOS
 * storage and of the control tables, so the protocol's upload/relay semantics
 * are unchanged.
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS outbound_found (" +
                "signature_hex TEXT NOT NULL PRIMARY KEY, " +
                "frame BLOB NOT NULL, " +
                "uploaded INTEGER NOT NULL DEFAULT 0, queued_at INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_outbound_found_queued_at ON outbound_found (queued_at)")
    }
}

@Database(
    entities = [
        MessageEntity::class,
        NeighborObservationEntity::class,
        NeighborEstimateEntity::class,
        ControlReplayEntity::class,
        ControlTrustEntity::class,
        OutboundControlEntity::class,
        OutboundFoundEntity::class,
        ChatMessageEntity::class,
        ChatFragmentEntity::class,
    ],
    version = 10,
    exportSchema = false,
)
abstract class GuacamayaDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun chatDao(): ChatDao

    companion object {
        @Volatile private var instance: GuacamayaDatabase? = null

        fun get(context: Context): GuacamayaDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    GuacamayaDatabase::class.java,
                    "guacamaya.db"
                )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
                    .fallbackToDestructiveMigration() // safety net for any unhandled jump
                    .build().also { instance = it }
            }
    }
}
