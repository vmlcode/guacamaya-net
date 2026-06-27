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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import android.content.Context
import kotlinx.coroutines.flow.Flow

object MessageChannel {
    const val SOS = "SOS"
    const val CHAT = "CHAT"
}

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
    @ColumnInfo(name = "body_text") val bodyText: String? = null,
    @ColumnInfo(name = "channel") val channel: String = MessageChannel.SOS,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as MessageEntity
        if (id != other.id) return false
        if (!nodeId.contentEquals(other.nodeId)) return false
        if (msgId != other.msgId) return false
        if (tsUnix != other.tsUnix) return false
        if (latE7 != other.latE7) return false
        if (lonE7 != other.lonE7) return false
        if (sosType != other.sosType) return false
        if (critical != other.critical) return false
        if (hasHeavy != other.hasHeavy) return false
        if (hopTtl != other.hopTtl) return false
        if (batteryBucket != other.batteryBucket) return false
        if (!pubkey.contentEquals(other.pubkey)) return false
        if (!payloadRaw.contentEquals(other.payloadRaw)) return false
        if (rssi != other.rssi) return false
        if (receivedAt != other.receivedAt) return false
        if (bodyText != other.bodyText) return false
        if (channel != other.channel) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + nodeId.contentHashCode()
        result = 31 * result + msgId
        result = 31 * result + tsUnix.hashCode()
        result = 31 * result + latE7
        result = 31 * result + lonE7
        result = 31 * result + sosType
        result = 31 * result + critical.hashCode()
        result = 31 * result + hasHeavy.hashCode()
        result = 31 * result + hopTtl
        result = 31 * result + batteryBucket
        result = 31 * result + pubkey.contentHashCode()
        result = 31 * result + payloadRaw.contentHashCode()
        result = 31 * result + rssi
        result = 31 * result + receivedAt.hashCode()
        result = 31 * result + (bodyText?.hashCode() ?: 0)
        result = 31 * result + channel.hashCode()
        return result
    }
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

@Database(entities = [MessageEntity::class], version = 2, exportSchema = false)
abstract class SOSNetDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile private var instance: SOSNetDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN body_text TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN channel TEXT NOT NULL DEFAULT 'SOS'")
            }
        }

        fun get(context: Context): SOSNetDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SOSNetDatabase::class.java,
                    "sosnet.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration()
                    .build().also { instance = it }
            }
    }
}
