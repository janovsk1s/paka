package com.paka.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records which classes and methods Paka touches during a cold start, opening
 * a pass and the 2FA list, so the installer can compile them ahead of time.
 * Regenerate after larger changes: ./gradlew :app:generateBaselineProfile
 * The journey uses English UI text, so run it on an English device.
 */
@RunWith(AndroidJUnit4::class)
class StartupProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    // The plugin names the variant's package, so release and preview both work.
    private val packageName = InstrumentationRegistry.getArguments().getString("targetAppId") ?: "com.paka.app"

    @Test
    fun generate() = rule.collect(packageName) {
        pressHome()
        startActivityAndWait()
        // A fresh install opens on onboarding.
        device.findObject(By.text("start"))?.click()
        device.wait(Until.hasObject(By.desc("Add")), TIMEOUT_MS)
        // Synthetic entries, added once; later iterations reuse them.
        if (!device.hasObject(By.text(PASS_NAME))) {
            addEntry(device, "e.g. Billa" to PASS_NAME, "code content" to "https://example.invalid/paka/profile/0001")
        }
        // Wait for each screen before acting: Back pressed early leaves Paka.
        device.wait(Until.findObject(By.text(PASS_NAME)), TIMEOUT_MS).click()
        device.wait(Until.gone(By.desc("Add")), TIMEOUT_MS)
        device.waitForIdle()
        device.pressBack()
        device.wait(Until.findObject(By.desc("Show 2FA codes")), TIMEOUT_MS).click()
        device.wait(Until.hasObject(By.desc("Show passes")), TIMEOUT_MS)
        if (!device.hasObject(By.text(ACCOUNT_NAME))) {
            // RFC 6238's published sample secret, not a real account.
            addEntry(device, "e.g. GitHub" to ACCOUNT_NAME, "base32 key" to "JBSWY3DPEHPK3PXP")
        }
        device.wait(Until.hasObject(By.text(ACCOUNT_NAME)), TIMEOUT_MS)
    }

    /** Long-press + opens manual entry for the current list; each pair is placeholder to value. */
    private fun addEntry(device: UiDevice, name: Pair<String, String>, data: Pair<String, String>) {
        device.wait(Until.findObject(By.desc("Add")), TIMEOUT_MS).longClick()
        enterField(device, name.first, name.second)
        enterField(device, data.first, data.second)
        device.wait(Until.findObject(By.text("save")), TIMEOUT_MS).click()
        device.wait(Until.gone(By.text("save")), TIMEOUT_MS)
        device.waitForIdle()
        // Saving a pass opens it; return to the list.
        if (!device.hasObject(By.desc("Add"))) device.pressBack()
        device.wait(Until.hasObject(By.desc("Add")), TIMEOUT_MS)
    }

    private fun enterField(device: UiDevice, placeholder: String, value: String) {
        device.wait(Until.findObject(By.text(placeholder)), TIMEOUT_MS).click()
        device.wait(Until.findObject(By.clazz("android.widget.EditText")), TIMEOUT_MS).text = value
        // Let the keyboard settle first: it moves "save" while it slides in.
        device.waitForIdle()
        device.findObject(By.text("save")).click()
        device.wait(Until.gone(By.clazz("android.widget.EditText")), TIMEOUT_MS)
    }

    private companion object {
        const val PASS_NAME = "Profile pass"
        const val ACCOUNT_NAME = "Profile code"
        const val TIMEOUT_MS = 10_000L
    }
}
