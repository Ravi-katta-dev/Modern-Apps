package com.vayunmathur.things.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BodyMetricsCalculateTest {
    private val male = ScaleProfile(sex = Sex.Male, age = 30, heightCm = 175.0, athlete = false)

    @Test
    fun noImpedanceYieldsZeroCompositionButKeepsBmi() {
        val metrics = BodyComposition.calculate(male, ScaleMeasurement(weightKg = 70.0))
        assertEquals(22.9, metrics.bmi)
        assertEquals(0.0, metrics.bodyFatPercent)
        assertEquals(0.0, metrics.lbmKg)
        assertEquals(0.0, metrics.fatMassKg)
        assertEquals(0.0, metrics.waterPercent)
        assertEquals(0, metrics.visceralLevel)
        assertEquals(0, metrics.bodyAge)
        assertEquals(0, metrics.score)
        assertNull(metrics.segmental)
    }

    @Test
    fun zeroWeightYieldsZeroBmrAndZeroComposition() {
        val metrics = BodyComposition.calculate(male, ScaleMeasurement(weightKg = 0.0))
        assertEquals(0.0, metrics.bmi)
        assertEquals(0, metrics.bmrKcal)
        assertEquals(0.0, metrics.bodyFatPercent)
        assertEquals(0.0, metrics.lbmKg)
    }

    @Test
    fun impedancePresentKeepsEveryOutputInHumanRange() {
        val metrics = BodyComposition.calculate(male, ScaleMeasurement(70.0, 500, 520))
        assertTrue(metrics.bodyFatPercent in 5.1..75.0)
        assertTrue(metrics.lbmKg in 0.0..70.0)
        assertTrue(metrics.waterPercent in 35.0..75.0)
        assertTrue(metrics.bmrKcal in 800..4000)
        assertTrue(metrics.visceralLevel in 1..59)
        assertTrue(metrics.bodyAge in 10..80)
        assertTrue(metrics.score in 40..100)
        assertEquals(70.0, metrics.lbmKg + metrics.fatMassKg, 1e-9)
    }

    @Test
    fun athleteHasLowerOrEqualFatThanNonAthlete() {
        val base = ScaleMeasurement(weightKg = 110.0, resistance50 = 300, resistance500 = 320)
        val plain = BodyComposition.calculate(male, base)
        val athletic = BodyComposition.calculate(male.copy(athlete = true), base)
        assertTrue(athletic.bodyFatPercent <= plain.bodyFatPercent)
    }

    @Test
    fun femaleHasHigherOrEqualFatThanMaleOnSameReading() {
        val reading = ScaleMeasurement(weightKg = 120.0, resistance50 = 500, resistance500 = 520)
        val maleMetrics = BodyComposition.calculate(male, reading)
        val femaleMetrics = BodyComposition.calculate(male.copy(sex = Sex.Female), reading)
        assertTrue(femaleMetrics.bodyFatPercent >= maleMetrics.bodyFatPercent)
    }

    @Test
    fun segmentalSplitsSumBackToTotals() {
        val seg = SegmentalImpedance(
            rh20 = 100.0, lh20 = 100.0, t20 = 100.0, rf20 = 100.0, lf20 = 100.0,
            rh100 = 100.0, lh100 = 100.0, t100 = 100.0, rf100 = 100.0, lf100 = 100.0,
        )
        val metrics = BodyComposition.calculate(male, ScaleMeasurement(70.0, 500, 520, seg))
        val result = assertNotNull(metrics.segmental)
        val fatSum = result.fatRh + result.fatLh + result.fatT + result.fatRf + result.fatLf
        val muscleSum = result.muscleRh + result.muscleLh + result.muscleT + result.muscleRf
        assertEquals(metrics.fatMassKg, fatSum, 1e-9)
        assertEquals(metrics.muscleMassKg, muscleSum + result.muscleLf, 1e-9)
    }

    @Test
    fun emptySegmentalYieldsNull() {
        val empty = SegmentalImpedance()
        val metrics = BodyComposition.calculate(male, ScaleMeasurement(70.0, 500, 520, empty))
        assertNull(metrics.segmental)
    }

    @Test
    fun heightOutsideOperatingRangeIsClamped() {
        val short = BodyComposition.calculate(male.copy(heightCm = 10.0), ScaleMeasurement(70.0, 500, 520))
        val tall = BodyComposition.calculate(male.copy(heightCm = 300.0), ScaleMeasurement(70.0, 500, 520))
        assertEquals(BodyComposition.calcBmi(40.0, 70.0), short.bmi)
        assertEquals(BodyComposition.calcBmi(240.0, 70.0), tall.bmi)
    }
}
