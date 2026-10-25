package com.readygym.app

import com.readygym.core.weight.WeightEntry
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeightChartGeometryTest {
    private val start = LocalDate.of(2026, 10, 1)
    private fun entry(day: Long, kg: Double) = WeightEntry(start.plusDays(day), kg)

    @Test fun missingDaysKeepTheirDistanceAndDoNotCreateExtraMeasurements() {
        val geometry = weightChartGeometry(listOf(entry(0, 72.0), entry(1, 71.8), entry(10, 71.0)), null)
        assertEquals(3, geometry.points.size)
        assertEquals(0f, geometry.points[0].x, 0.0001f)
        assertEquals(0.1f, geometry.points[1].x, 0.0001f)
        assertEquals(1f, geometry.points[2].x, 0.0001f)
    }

    @Test fun aSingleRecordAndEqualWeightsHaveFiniteVisibleCoordinates() {
        val single = weightChartGeometry(listOf(entry(0, 70.0)), null)
        assertEquals(1, single.points.size)
        assertEquals(0.5f, single.points.single().x, 0.0001f)
        val equal = weightChartGeometry(listOf(entry(0, 70.0), entry(3000, 70.0)), 70.0)
        for (geometry in listOf(single, equal)) {
            assertTrue(geometry.upperKg > geometry.lowerKg)
            assertTrue(geometry.points.all { it.x.isFinite() && it.y.isFinite() && it.y > 0f && it.y < 1f })
            assertTrue(geometry.ticks().size in 3..8)
        }
    }

    @Test fun aDistantTargetRemainsInsideTheChartWithoutClippingTheMeasurements() {
        val geometry = weightChartGeometry(listOf(entry(0, 69.9), entry(1, 70.0)), 50.0)
        assertTrue(geometry.lowerKg < 50.0)
        assertTrue(geometry.upperKg > 70.0)
        assertTrue(geometry.yFraction(50.0) in 0f..1f)
        assertTrue(geometry.points.all { it.y in 0f..1f })
    }

    @Test fun trendDependsOnDistanceToGoalEvenWhenTheGoalIsCrossed() {
        assertEquals(WeightChangeTone.CLOSER, weightChange(70.0, 69.5, 69.1).tone)
        assertEquals(WeightChangeTone.FARTHER, weightChange(70.0, 70.5, 69.1).tone)
        assertEquals(WeightChangeTone.CLOSER, weightChange(68.0, 68.5, 69.1).tone)
        assertEquals(WeightChangeTone.NEUTRAL, weightChange(70.0, 68.2, 69.1).tone)
        assertEquals(WeightChangeTone.NEUTRAL, weightChange(70.0, 71.0, null).tone)
    }

    @Test fun goalSummaryUsesMeasuredWeightOnlyAndRoundsFloatingPointDifferences() {
        assertTrue(weightGoalDescription(70.0, 69.1).contains("还需减重 0.9 kg"))
        assertTrue(weightGoalDescription(68.0, 69.1).contains("还需增重 1.1 kg"))
        assertTrue(weightGoalDescription(69.1, 69.1).contains("已到设定目标"))
        assertTrue(weightGoalDescription(null, 69.1).contains("先记录一次"))
    }
}
