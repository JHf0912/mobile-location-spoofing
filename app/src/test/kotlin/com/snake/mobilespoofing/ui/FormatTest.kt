package com.snake.mobilespoofing.ui

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the display format produced by [formatPoint].
 *
 * The separator is part of the contract, not an accident of the implementation: the current
 * output is exactly `"<lat with 5 decimals>, <lon with 5 decimals>"` — a comma followed by a
 * single space. These tests record that behavior so any change to it is deliberate.
 */
class FormatTest {

    @Test
    fun `formats a point with exactly five decimal places`() {
        assertEquals("39.90420, 116.40740", formatPoint(39.9042, 116.4074))
    }

    @Test
    fun `formats negative latitude and longitude`() {
        assertEquals("-33.86880, -70.66930", formatPoint(-33.8688, -70.6693))
    }

    @Test
    fun `formats zero with five decimal places`() {
        assertEquals("0.00000, 0.00000", formatPoint(0.0, 0.0))
    }

    @Test
    fun `pads values that have fewer than five decimals`() {
        assertEquals("1.50000, 2.25000", formatPoint(1.5, 2.25))
    }

    @Test
    fun `rounds values that have more than five decimals`() {
        assertEquals("3.45679, -1.23457", formatPoint(3.456789, -1.234567))
    }

    @Test
    fun `carries rounding over into the next integer`() {
        assertEquals("3.00000, -2.00000", formatPoint(2.999999, -1.999999))
    }

    @Test
    fun `formats the coordinate bounds`() {
        assertEquals("90.00000, 180.00000", formatPoint(90.0, 180.0))
        assertEquals("-90.00000, -180.00000", formatPoint(-90.0, -180.0))
    }

    @Test
    fun `separates latitude and longitude with a comma and a single space`() {
        val formatted = formatPoint(12.34567, 89.01234)

        assertEquals("12.34567, 89.01234", formatted)
        assertEquals(listOf("12.34567", "89.01234"), formatted.split(", "))
        assertFalse(formatted.contains(";"))
    }

    @Test
    fun `uses a dot for decimals even when the default locale uses a comma`() {
        val original = Locale.getDefault()
        try {
            // A comma-decimal default locale would leak into the output if formatPoint did not
            // resolve Locale.ROOT explicitly.
            Locale.setDefault(Locale.GERMANY)

            assertEquals("39.90420, 116.40740", formatPoint(39.9042, 116.4074))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `always emits five decimal digits per coordinate`() {
        val pattern = Regex("^-?\\d+\\.\\d{5}, -?\\d+\\.\\d{5}$")

        assertTrue(pattern.matches(formatPoint(12.3, 45.6)))
        assertTrue(pattern.matches(formatPoint(-12.3, -45.6)))
    }
}
