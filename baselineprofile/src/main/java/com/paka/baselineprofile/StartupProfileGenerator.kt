package com.paka.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records which classes and methods Paka touches during a cold start so the
 * installer can compile them ahead of time. Regenerate after larger changes:
 * ./gradlew :app:generateBaselineProfile
 */
@RunWith(AndroidJUnit4::class)
class StartupProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect("com.paka.app") {
        pressHome()
        startActivityAndWait()
    }
}
