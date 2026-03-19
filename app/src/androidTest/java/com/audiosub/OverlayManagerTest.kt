package com.audiosub

import android.content.Context
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.audiosub.overlay.PipelineState
import com.audiosub.overlay.SubtitleOverlayManager
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke-tests for [SubtitleOverlayManager].
 *
 * These tests require SYSTEM_ALERT_WINDOW permission to be pre-granted on the test device.
 * Run: adb shell appops set com.audiosub SYSTEM_ALERT_WINDOW allow
 */
@RunWith(AndroidJUnit4::class)
class OverlayManagerTest {

    private lateinit var context: Context
    private lateinit var manager: SubtitleOverlayManager

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(
            "SYSTEM_ALERT_WINDOW permission required",
            Settings.canDrawOverlays(context)
        )
        manager = SubtitleOverlayManager(context)
    }

    @After
    fun tearDown() {
        if (manager.isAttached) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                manager.detach()
            }
        }
    }

    @Test
    fun attach_setsIsAttachedTrue() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            manager.attach()
        }
        assert(manager.isAttached)
    }

    @Test
    fun detach_setsIsAttachedFalse() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            manager.attach()
            manager.detach()
        }
        assert(!manager.isAttached)
    }

    @Test
    fun setState_doesNotCrash() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            manager.attach()
            listOf(
                PipelineState.INITIALIZING,
                PipelineState.DOWNLOADING(),
                PipelineState.LISTENING,
                PipelineState.TRANSCRIBING,
                PipelineState.TRANSLATING,
                PipelineState.ERROR
            ).forEach { manager.setState(it) }
        }
    }

    @Test
    fun showSubtitle_doesNotCrashWithLongText() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            manager.attach()
            manager.showSubtitle("이것은 매우 긴 자막 텍스트입니다. 여러 줄로 나뉠 수도 있어요. 테스트 중입니다.")
        }
        Thread.sleep(300)
    }
}
