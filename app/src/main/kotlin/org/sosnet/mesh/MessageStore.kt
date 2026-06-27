package org.sosnet.mesh

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import android.content.Context
import kotlinx.coroutines.flow.Flow

/**
 * Persistent record of every verified SOS frame received. See docs/protocol-flows.md
 * Flow 2 — written only after the reject cascade passes (CRC, ts-skew, hop-ttl,
 * pubkey-binding, signature).
 */
@Entity(tableName = "messages")
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: MessageEntity): Long

    @Query("SELECT * FROM messages ORDER BY received_at DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<MessageEntity>>

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun count(): Int

    @Query("DELETE FROM messages")
    suspend fun clear()
}

@Database(entities = [MessageEntity::class], version = 1, exportSchema = false)
abstract class SOSNetDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile private var instance: SOSNetDatabase? = null

        fun get(context: Context): SOSNetDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SOSNetDatabase::class.java,
                    "sosnet.db"
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
