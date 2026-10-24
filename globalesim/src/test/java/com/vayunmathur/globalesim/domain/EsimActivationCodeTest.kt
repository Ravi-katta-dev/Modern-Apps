package com.vayunmathur.globalesim.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class EsimActivationCodeTest {
    @Test
    fun bareMatchingIdGetsFullPrefix() {
        assertEquals(
            "LPA:1\$smdp.example\$TN123",
            buildActivationCode("TN123", "smdp.example"),
        )
    }

    @Test
    fun wellFormedCodePassesThrough() {
        assertEquals(
            "LPA:1\$smdp.example\$TN123",
            buildActivationCode("LPA:1\$smdp.example\$TN123", "smdp.example"),
        )
    }

    @Test
    fun fusedSmdpAndMatchingIdAreSplit() {
        // The exact shape seen in the broken handoff: one `$` missing.
        assertEquals(
            "LPA:1\$consumer.e-sim.global\$TN2026021619282618FF23C5",
            buildActivationCode(
                "LPA:1\$consumer.e-sim.globalTN2026021619282618FF23C5",
                "consumer.e-sim.global",
            ),
        )
    }
}
