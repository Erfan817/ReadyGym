package com.readygym.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.readygym.core.weight.WeightEntry
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WeightRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "weight-repository-test.db"
    private val today = LocalDate.of(2026, 10, 25)
    private val now = today.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private var db = open()
    private var repo = WeightRepository(db) { now }

    private fun open() = Room.databaseBuilder(context, GymDatabase::class.java, name)
        .addMigrations(GymDatabase.MIGRATION_1_2).allowMainThreadQueries().build()

    private fun reopen() {
        db.close()
        db = open()
        repo = WeightRepository(db) { now }
    }

    @After fun close() { db.close(); context.deleteDatabase(name) }

    @Test fun dailyReplacementHistoryOrderingAndDeletionSurviveReopening() = runTest {
        assertTrue(repo.entries.first().isEmpty())
        repo.saveEntry(today, 70.1)
        repo.saveEntry(today.minusDays(2), 71.2)
        repo.saveEntry(today, 69.14)
        val expected = listOf(WeightEntry(today.minusDays(2), 71.2), WeightEntry(today, 69.1))
        assertEquals(expected, repo.entries.first())
        reopen()
        assertEquals(expected, repo.entries.first())
        repo.deleteEntry(today)
        reopen()
        assertEquals(listOf(expected.first()), repo.entries.first())
        // There is no profile dependency, and weight tracking does not manufacture a profile.
        assertNull(db.dao().profileNow())
    }

    @Test fun targetIsIndependentAndCanBeChangedOrClearedAfterRestart() = runTest {
        assertNull(repo.targetKg.first())
        repo.saveTarget(69.1)
        reopen()
        assertEquals(69.1, repo.targetKg.first()!!, 0.0)
        assertTrue(repo.entries.first().isEmpty())
        repo.saveTarget(72.06)
        reopen()
        assertEquals(72.1, repo.targetKg.first()!!, 0.0)
        repo.saveTarget(null)
        reopen()
        assertNull(repo.targetKg.first())
    }

    @Test fun invalidAndFutureMeasurementsCannotReplaceSavedValues() = runTest {
        repo.saveEntry(today, 70.0)
        repo.saveTarget(69.1)
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 29.99, 250.01).forEach { kg ->
            try { repo.saveEntry(today, kg); fail("accepted invalid measurement $kg") }
            catch (_: IllegalArgumentException) { }
            try { repo.saveTarget(kg); fail("accepted invalid target $kg") }
            catch (_: IllegalArgumentException) { }
        }
        try { repo.saveEntry(today.plusDays(1), 71.0); fail("accepted a future date") }
        catch (_: IllegalArgumentException) { }
        assertEquals(listOf(WeightEntry(today, 70.0)), repo.entries.first())
        assertEquals(69.1, repo.targetKg.first()!!, 0.0)
    }
}
