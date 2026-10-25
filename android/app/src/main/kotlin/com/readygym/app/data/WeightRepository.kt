package com.readygym.app.data

import com.readygym.core.weight.WeightEntry
import com.readygym.core.weight.weightInTenths
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Local weight history is independent of the coach profile and of all workout data. */
class WeightRepository(db: GymDatabase, private val clock: () -> Long = System::currentTimeMillis) {
    private val dao = db.weightDao()

    val entries: Flow<List<WeightEntry>> = dao.entries().map { records ->
        records.map { WeightEntry(LocalDate.ofEpochDay(it.epochDay), it.deciKilograms / 10.0) }
    }
    val targetKg: Flow<Double?> = dao.target().map { it?.deciKilograms?.div(10.0) }

    /** A second save for the same day replaces that day's entry without creating duplicate points. */
    suspend fun saveEntry(date: LocalDate, kilograms: Double) {
        val today = Instant.ofEpochMilli(clock()).atZone(ZoneId.systemDefault()).toLocalDate()
        require(!date.isAfter(today)) { "不能记录未来的体重，请选择今天或之前的日期。" }
        dao.saveEntry(WeightEntryEntity(date.toEpochDay(), weightInTenths(kilograms)))
    }

    suspend fun deleteEntry(date: LocalDate) = dao.deleteEntry(date.toEpochDay())

    suspend fun saveTarget(kilograms: Double?) {
        if (kilograms == null) dao.clearTarget()
        else dao.saveTarget(WeightTargetEntity(deciKilograms = weightInTenths(kilograms)))
    }
}
