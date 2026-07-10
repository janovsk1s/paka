package com.paka.app

import android.app.Activity
import android.view.WindowManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SensitiveWindowProtectionTest {
    @Test
    fun nestedOwnerCannotClearAnotherOwnersSecureFlag() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val window = activity.window

        SensitiveWindowProtection.acquire(window)
        SensitiveWindowProtection.acquire(window)
        SensitiveWindowProtection.release(window)
        assertTrue(window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)

        SensitiveWindowProtection.release(window)
        assertFalse(window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        controller.pause().stop().destroy()
    }
}
