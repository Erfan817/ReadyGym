package com.readygym.core.weight

import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WeightTrackingTest {
    @Test fun supportedWeightsRoundToOneDecimalWithoutAccumulatedFloatSteps() {
        assertEquals(691, weightInTenths(69.1))
        assertEquals(69.1, normalizedWeightKg(69.14), 0.0)
        assertEquals(69.2, normalizedWeightKg(69.16), 0.0)
        assertEquals(300, weightInTenths(30.0))
        assertEquals(2500, weightInTenths(250.0))
    }

    @Test fun invalidNumbersAreRejectedBeforeRounding() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 29.99, 250.01).forEach { kg ->
            assertThrows(IllegalArgumentException::class.java) { weightInTenths(kg) }
            assertThrows(IllegalArgumentException::class.java) { WeightEntry(LocalDate.of(2026, 10, 25), kg) }
        }
    }

    @Test fun formatIsUsableAsNumericInputEvenWithCommaDecimalLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("69.1", formatWeightKg(69.1))
        } finally { Locale.setDefault(original) }
    }
}
