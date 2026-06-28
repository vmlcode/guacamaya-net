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
    @ColumnInfo(name = "rssi") val rssi: Int,
    @ColumnInfo(name = "received_at") val receivedAt: Long,
) {
    // Room needs equals/hashCode for ByteArray fields.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: MessageEntity): Long

    @Query("SELECT * FROM messages ORDER BY received_at DESC LIMIT :limit")
    fun observeRecent(limit: Int = DEFAULT_RECENT_LIMIT): Flow<List<MessageEntity>>

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM messages")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(DISTINCT node_id) FROM messages")
    fun observeNodeCount(): Flow<Int>

    /** Latest row per node_id (for map/radar — one device = one entry). */
    @Query(
        """
        SELECT m.* FROM messages m
        INNER JOIN (
            SELECT node_id, MAX(received_at) AS max_received
            FROM messages GROUP BY node_id
        ) g ON m.node_id = g.node_id AND m.received_at = g.max_received
        ORDER BY m.received_at DESC
        LIMIT :limit
        """
    )
    fun observeLatestPerNode(limit: Int = 500): Flow<List<MessageEntity>>

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
}

@Database(entities = [MessageEntity::class], version = 2, exportSchema = false)
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
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
