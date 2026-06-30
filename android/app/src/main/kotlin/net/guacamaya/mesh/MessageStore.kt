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

@Database(entities = [MessageEntity::class], version = 4, exportSchema = false)
abstract class GuacamayaDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile private var instance: GuacamayaDatabase? = null

        fun get(context: Context): GuacamayaDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    GuacamayaDatabase::class.java,
                    "guacamaya.db"
                )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                    .fallbackToDestructiveMigration() // safety net for any unhandled jump
                    .build().also { instance = it }
            }
    }
}
