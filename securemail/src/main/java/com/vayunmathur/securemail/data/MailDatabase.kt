package com.vayunmathur.securemail.data

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.execSQL
import com.vayunmathur.library.util.DatabaseMigrations
import kotlinx.coroutines.flow.Flow

/**
 * One stored message: envelope metadata plus the opaque PGP/MIME blob exactly
 * as it arrived over the socket (raw bytes, base64 in the DB). The server never
 * sees plaintext; neither does this table — decryption happens in memory only.
 */
@Entity
data class StoredMessage(
    @PrimaryKey val id: String,
    val from: String,
    val toJson: String,
    val dateMillis: Long,
    val messageId: String,
    /** Base64 of the raw PGP/MIME blob (wire format is raw binary). */
    val blobBase64: String,
    /** Decrypted body cached only after an in-memory decrypt (nullable). */
    val decryptedBody: String? = null,
    val decryptedSubject: String? = null,
    val verifiedSigner: String? = null,
    val unencrypted: Boolean = false,
    val isRead: Boolean = false,
)

/** Cached public key for a recipient (own directory, WKD, or VKS result). */
@Entity
data class CachedKey(
    @PrimaryKey val address: String,
    val armoredPublic: String,
    val fingerprintHex: String,
    val source: String,
    val updatedMillis: Long,
)

/** Queued outbound message awaiting socket reconnect or SMTP federation. */
@Entity
data class OutboxMessage(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val toJson: String,
    val subject: String,
    val body: String,
    val inReplyTo: String? = null,
    val createdMillis: Long,
    val lastError: String? = null,
    val attemptCount: Int = 0,
)

@Dao
interface MessageDao {
    @Query("SELECT * FROM StoredMessage ORDER BY dateMillis DESC")
    fun observeAll(): Flow<List<StoredMessage>>

    @Query("SELECT * FROM StoredMessage WHERE id = :id LIMIT 1")
    suspend fun get(id: String): StoredMessage?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(msg: StoredMessage)

    @Query("UPDATE StoredMessage SET isRead = 1 WHERE id = :id")
    suspend fun markRead(id: String)

    @Query(
        "UPDATE StoredMessage SET decryptedBody = :body, decryptedSubject = :subject, " +
            "verifiedSigner = :verified WHERE id = :id",
    )
    suspend fun storeDecrypted(id: String, body: String, subject: String, verified: String?)

    @Query("DELETE FROM StoredMessage WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface KeyDao {
    @Query("SELECT * FROM CachedKey WHERE address = :address LIMIT 1")
    suspend fun get(address: String): CachedKey?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(key: CachedKey)
}

@Dao
interface OutboxDao {
    @Query("SELECT * FROM OutboxMessage ORDER BY createdMillis ASC")
    suspend fun all(): List<OutboxMessage>

    @Query("SELECT COUNT(*) FROM OutboxMessage")
    fun observeCount(): Flow<Int>

    @Insert
    suspend fun insert(entry: OutboxMessage): Long

    @Query("DELETE FROM OutboxMessage WHERE rowId = :rowId")
    suspend fun delete(rowId: Long)

    @Query("UPDATE OutboxMessage SET lastError = :error, attemptCount = attemptCount + 1 WHERE rowId = :rowId")
    suspend fun noteAttempt(rowId: Long, error: String)
}

@Database(
    entities = [StoredMessage::class, CachedKey::class, OutboxMessage::class],
    version = 1,
    exportSchema = false,
)
abstract class MailDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun keyDao(): KeyDao
    abstract fun outboxDao(): OutboxDao

    companion object : DatabaseMigrations {
        override val migrations: List<Migration> = emptyList()
    }
}
