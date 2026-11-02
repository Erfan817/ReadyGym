package com.readygym.app.data

import android.content.Context
import android.database.Cursor
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.readygym.core.Equipment
import com.readygym.core.Gender
import com.readygym.core.Goal
import com.readygym.core.Location
import com.readygym.core.plan.PlanDay
import com.readygym.core.plan.PlanItem
import com.readygym.core.plan.WorkoutPlan
import com.readygym.core.profile.Experience
import com.readygym.core.profile.UserProfile
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Opens an actual v1 on-disk database, using the archived released schema rather than a v2 fake. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WeightMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "weight-v1-migration-test.db"
    private var migrated: GymDatabase? = null
    private val oldTables = listOf("profile", "plan_version", "workout_session", "set_log", "feedback")

    @After fun close() { migrated?.close(); context.deleteDatabase(name) }

    private fun Cursor.rows(): List<List<String?>> = use { cursor ->
        buildList {
            while (cursor.moveToNext()) add((0 until cursor.columnCount).map { index ->
                if (cursor.isNull(index)) null else cursor.getString(index)
            })
        }
    }

    @Test fun upgradingV1RetainsEveryLegacyRowAndStartsWithNoInventedWeightHistory() = runTest {
        val profile = UserProfile(24, 175.0, 70.0, Gender.MALE, Goal.MUSCLE, Location.HOME,
            setOf(Equipment.DUMBBELL), 2, 45, Experience.BEGINNER)
        val plan = WorkoutPlan(7, 1234, "旧计划", "保留组数和轮换", 2, listOf(
            PlanDay("已完成训练", items = listOf(PlanItem("dumbbell-row", 2, 8, 12, loadKg = 8.5, restSeconds = 90))),
            PlanDay("进行中训练", items = listOf(PlanItem("goblet-squat", 2, 8, 12, loadKg = 14.0, restSeconds = 90),
                PlanItem("plank", 2, seconds = 30, restSeconds = 90))),
        ), progressionBaseVersion = 3)
        val schema = JSONObject(File("schemas/com.readygym.app.data.GymDatabase/1.json").readText())
            .getJSONObject("database")
        val before = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.getJSONArray("indices")
                for (j in 0 until indices.length()) {
                    old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            old.execSQL("INSERT INTO profile VALUES (1, ?, 1234)", arrayOf(" ${profile.toJson()} "))
            old.execSQL("INSERT INTO plan_version VALUES (7, 1234, ?, ?)", arrayOf("旧计划", " ${plan.toJson()} "))
            old.execSQL("INSERT INTO workout_session VALUES (10, 7, 0, ?, 1000, 5000)", arrayOf("已完成训练"))
            old.execSQL("INSERT INTO workout_session VALUES (11, 7, 1, ?, 6000, NULL)", arrayOf("进行中训练"))
            old.execSQL("INSERT INTO set_log VALUES (20, 10, 'dumbbell-row', 0, 12, NULL, 8.5, 1)")
            old.execSQL("INSERT INTO set_log VALUES (21, 11, 'goblet-squat', 0, 9, NULL, 14.0, 1)")
            old.execSQL("INSERT INTO set_log VALUES (22, 11, 'plank', 0, NULL, 30, NULL, 0)")
            old.execSQL("INSERT INTO feedback VALUES (10, ?)", arrayOf(" {\"sessionId\":10,\"note\":\"保留原文\"} "))
            old.version = 1
            oldTables.associateWith { table -> old.rawQuery("SELECT * FROM `$table` ORDER BY 1", null).rows() }
        }

        val db = Room.databaseBuilder(context, GymDatabase::class.java, name)
            .addMigrations(GymDatabase.MIGRATION_1_2).allowMainThreadQueries().build().also { migrated = it }
        // Opening forces Room to run and validate the migration against the generated v2 schema.
        val sql = db.openHelper.writableDatabase
        assertEquals(2, sql.version)
        oldTables.forEach { table ->
            assertEquals("legacy rows changed in $table", before.getValue(table),
                sql.query("SELECT * FROM `$table` ORDER BY 1").rows())
        }
        assertEquals(0, sql.query("PRAGMA foreign_key_check").use { it.count })
        val open = db.dao().openSession().first()!!
        assertEquals(11L, open.session.id)
        assertEquals(7, open.session.planVersion)
        assertEquals(2, open.sets.size)
        assertEquals(9, open.sets.first { it.exerciseId == "goblet-squat" }.reps)
        assertEquals(true, open.sets.first { it.exerciseId == "goblet-squat" }.done)
        val gym = GymRepository(db)
        assertEquals(profile, gym.profile.first())
        assertEquals(plan, gym.planVersion(7))
        assertEquals(1, gym.nextDayIndex(gym.activePlan.first()!!))
        assertEquals(1, gym.history.first().size)
        assertEquals(9, gym.openSession.first()!!.completedSets.single().reps)
        val weights = WeightRepository(db) { java.time.Instant.parse("2026-10-25T12:00:00Z").toEpochMilli() }
        assertTrue(weights.entries.first().isEmpty())
        assertNull(weights.targetKg.first())
        weights.saveEntry(LocalDate.of(2026, 1, 1), 70.1)
        weights.saveTarget(69.1)
        assertEquals(1, weights.entries.first().size)
        // Weight writes are independent of every legacy table, including raw history JSON.
        oldTables.forEach { table ->
            assertEquals(before.getValue(table), sql.query("SELECT * FROM `$table` ORDER BY 1").rows())
        }
    }
}
