package com.readygym.app.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "profile")
data class ProfileEntity(@PrimaryKey val id: Int = 1, val json: String, val updatedAt: Long)

/** Every confirmed plan version is kept; the highest version is the active plan. */
@Entity(tableName = "plan_version")
data class PlanVersionEntity(@PrimaryKey val version: Int, val createdAt: Long, val reason: String, val json: String)

@Entity(tableName = "workout_session", indices = [Index("startedAt")])
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val planVersion: Int,
    val dayIndex: Int,
    val dayTitle: String,
    val startedAt: Long,
    val endedAt: Long?,
)

@Entity(
    tableName = "set_log",
    foreignKeys = [ForeignKey(SessionEntity::class, ["id"], ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId"), Index("exerciseId")],
)
data class SetLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val exerciseId: String,
    val setIndex: Int,
    val reps: Int?,
    val seconds: Int?,
    val loadKg: Double?,
    val done: Boolean,
)

@Entity(
    tableName = "feedback",
    foreignKeys = [ForeignKey(SessionEntity::class, ["id"], ["sessionId"], onDelete = ForeignKey.CASCADE)],
)
data class FeedbackEntity(@PrimaryKey val sessionId: Long, val json: String)

data class SessionWithSets(
    @Embedded val session: SessionEntity,
    @Relation(parentColumn = "id", entityColumn = "sessionId") val sets: List<SetLogEntity>,
)

@Dao
interface GymDao {
    @Query("SELECT * FROM profile WHERE id = 1") fun profile(): Flow<ProfileEntity?>
    @Upsert suspend fun saveProfile(p: ProfileEntity)

    @Query("SELECT * FROM plan_version ORDER BY version DESC LIMIT 1") fun activePlan(): Flow<PlanVersionEntity?>
    @Query("SELECT * FROM plan_version ORDER BY version DESC LIMIT 1") suspend fun activePlanNow(): PlanVersionEntity?
    @Query("SELECT * FROM plan_version ORDER BY version DESC") fun planVersions(): Flow<List<PlanVersionEntity>>
    @Insert suspend fun insertPlan(p: PlanVersionEntity)

    @Insert suspend fun insertSession(s: SessionEntity): Long
    @Update suspend fun updateSession(s: SessionEntity)
    @Query("DELETE FROM workout_session WHERE id = :id") suspend fun deleteSession(id: Long)
    @Insert suspend fun insertSets(sets: List<SetLogEntity>)
    @Query("DELETE FROM set_log WHERE sessionId = :sessionId") suspend fun deleteSets(sessionId: Long)

    @Transaction @Query("SELECT * FROM workout_session WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    fun openSession(): Flow<SessionWithSets?>
    @Transaction @Query("SELECT * FROM workout_session WHERE id = :id") suspend fun session(id: Long): SessionWithSets?
    @Transaction @Query("SELECT * FROM workout_session WHERE endedAt IS NOT NULL ORDER BY startedAt DESC")
    fun finishedSessions(): Flow<List<SessionWithSets>>
    @Transaction @Query("SELECT * FROM workout_session WHERE endedAt IS NOT NULL ORDER BY startedAt DESC LIMIT :limit")
    suspend fun recentFinished(limit: Int): List<SessionWithSets>

    /** Completed sets of the most recent finished session that included [exerciseId]. */
    @Query("""SELECT * FROM set_log WHERE exerciseId = :exerciseId AND done = 1 AND sessionId = (
        SELECT s.id FROM workout_session s JOIN set_log l ON l.sessionId = s.id
        WHERE l.exerciseId = :exerciseId AND l.done = 1 AND s.endedAt IS NOT NULL
        AND (:planVersion IS NULL OR s.planVersion = :planVersion)
        ORDER BY s.startedAt DESC LIMIT 1) ORDER BY setIndex""")
    suspend fun lastSetsFor(exerciseId: String, planVersion: Int? = null): List<SetLogEntity>

    @Upsert suspend fun saveFeedback(f: FeedbackEntity)
    @Query("SELECT * FROM feedback WHERE sessionId = :sessionId") suspend fun feedback(sessionId: Long): FeedbackEntity?
    @Query("SELECT * FROM feedback") fun allFeedback(): Flow<List<FeedbackEntity>>
}

@Database(
    entities = [ProfileEntity::class, PlanVersionEntity::class, SessionEntity::class, SetLogEntity::class, FeedbackEntity::class],
    version = 1,
)
abstract class GymDatabase : RoomDatabase() {
    abstract fun dao(): GymDao

    companion object {
        @Volatile private var instance: GymDatabase? = null
        fun get(context: Context): GymDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, GymDatabase::class.java, "readygym.db")
                .build().also { instance = it }
        }

        /** Robolectric gives every test a fresh SQLite, so a cached connection must not leak across. */
        @androidx.annotation.VisibleForTesting
        internal fun resetForTest() = synchronized(this) { instance?.close(); instance = null }
    }
}
