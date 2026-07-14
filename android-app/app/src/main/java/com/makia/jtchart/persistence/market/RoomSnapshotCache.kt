package com.makia.jtchart.persistence.market

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.withTransaction
import com.makia.jtchart.domain.market.Query as MarketQuery

@Entity(tableName = "market_snapshots")
data class SnapshotEntity(
    @PrimaryKey val queryKey: String,
    val fetchedAtEpochMs: Long,
    val lastAccessedAtEpochMs: Long,
    val datasetFingerprint: String,
    val payload: ByteArray,
    val schemaVersion: Int,
)

@Entity(tableName = "chart_viewports")
data class ViewportEntity(
    @PrimaryKey val queryKey: String,
    val datasetFingerprint: String,
    val logicalFrom: Double,
    val logicalTo: Double,
    val updatedAtEpochMs: Long,
)

@Dao
interface SnapshotDao {
    @Query("SELECT * FROM market_snapshots WHERE queryKey = :queryKey")
    suspend fun snapshot(queryKey: String): SnapshotEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SnapshotEntity)

    @Query("UPDATE market_snapshots SET lastAccessedAtEpochMs = :accessedAt WHERE queryKey = :queryKey")
    suspend fun touch(queryKey: String, accessedAt: Long)

    @Query("SELECT queryKey, lastAccessedAtEpochMs, length(payload) AS payloadBytes FROM market_snapshots")
    suspend fun lruEntries(): List<LruEntryRow>

    @Query("DELETE FROM market_snapshots WHERE queryKey IN (:queryKeys)")
    suspend fun deleteSnapshots(queryKeys: List<String>)

    @Query("DELETE FROM chart_viewports WHERE queryKey IN (:queryKeys)")
    suspend fun deleteViewports(queryKeys: List<String>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertViewport(entity: ViewportEntity)

    @Query("SELECT * FROM chart_viewports WHERE queryKey = :queryKey AND datasetFingerprint = :fingerprint")
    suspend fun viewport(queryKey: String, fingerprint: String): ViewportEntity?

    @Query("DELETE FROM chart_viewports WHERE queryKey = :queryKey AND datasetFingerprint != :fingerprint")
    suspend fun deleteStaleViewport(queryKey: String, fingerprint: String)
}

data class LruEntryRow(
    val queryKey: String,
    val lastAccessedAtEpochMs: Long,
    val payloadBytes: Long,
)

@Database(
    entities = [SnapshotEntity::class, ViewportEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class JtChartDatabase : RoomDatabase() {
    abstract fun snapshotDao(): SnapshotDao
}

class RoomSnapshotCache(private val database: JtChartDatabase) : SnapshotCache {
    private val dao get() = database.snapshotDao()

    @Transaction
    override suspend fun get(query: MarketQuery, accessedAtEpochMs: Long): StoredSnapshot? = database.withTransaction {
        val entity = dao.snapshot(query.canonicalKey) ?: return@withTransaction null
        val decoded = entity.decode()
        require(decoded.snapshot.query == query) { "Snapshot QueryKey collision" }
        dao.touch(query.canonicalKey, accessedAtEpochMs)
        decoded.copy(lastAccessedAtEpochMs = accessedAtEpochMs)
    }

    @Transaction
    override suspend fun put(
        snapshot: CandleSnapshot,
        accessedAtEpochMs: Long,
        protectedQueryKeys: Set<String>,
    ): StoredSnapshot = database.withTransaction {
        val stored = SnapshotCanonicalizer.store(snapshot, accessedAtEpochMs)
        dao.upsert(stored.toEntity())
        dao.deleteStaleViewport(snapshot.query.canonicalKey, stored.datasetFingerprint)
        val entries = dao.lruEntries().map { LruEntry(it.queryKey, it.lastAccessedAtEpochMs, it.payloadBytes) }
        val evictions = SnapshotLruPolicy.keysToEvict(
            entries,
            protectedQueryKeys + snapshot.query.canonicalKey,
        )
        if (evictions.isNotEmpty()) {
            dao.deleteSnapshots(evictions)
            dao.deleteViewports(evictions)
        }
        stored
    }

    override suspend fun saveViewport(viewport: StoredViewport) {
        dao.upsertViewport(ViewportEntity(
            viewport.queryKey,
            viewport.datasetFingerprint,
            viewport.logicalFrom,
            viewport.logicalTo,
            viewport.updatedAtEpochMs,
        ))
    }

    override suspend fun getViewport(queryKey: String, datasetFingerprint: String): StoredViewport? =
        dao.viewport(queryKey, datasetFingerprint)?.let {
            StoredViewport(it.queryKey, it.datasetFingerprint, it.logicalFrom, it.logicalTo, it.updatedAtEpochMs)
        }

    private fun SnapshotEntity.decode(): StoredSnapshot {
        require(schemaVersion == SnapshotCanonicalizer.SCHEMA_VERSION) { "Unsupported snapshot schema" }
        val decoded = SnapshotCanonicalizer.decode(payload, fetchedAtEpochMs, lastAccessedAtEpochMs)
        require(decoded.snapshot.query.canonicalKey == queryKey) { "Snapshot key mismatch" }
        require(decoded.datasetFingerprint == datasetFingerprint) { "Snapshot fingerprint mismatch" }
        return decoded
    }

    private fun StoredSnapshot.toEntity() = SnapshotEntity(
        queryKey = snapshot.query.canonicalKey,
        fetchedAtEpochMs = snapshot.fetchedAtEpochMs,
        lastAccessedAtEpochMs = lastAccessedAtEpochMs,
        datasetFingerprint = datasetFingerprint,
        payload = canonicalJson,
        schemaVersion = SnapshotCanonicalizer.SCHEMA_VERSION,
    )
}

object RoomPersistenceFactory {
    fun createDatabase(context: Context): JtChartDatabase = Room.databaseBuilder(
        context.applicationContext,
        JtChartDatabase::class.java,
        "jt-chart-cache.db",
    ).fallbackToDestructiveMigration(dropAllTables = true).build()

    fun createCache(database: JtChartDatabase): SnapshotCache = RoomSnapshotCache(database)
}
