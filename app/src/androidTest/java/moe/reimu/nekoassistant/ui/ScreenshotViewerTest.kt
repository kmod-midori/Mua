package moe.reimu.nekoassistant.ui

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreenshotViewerTest {
    @get:Rule val compose = createComposeRule()
    private val bitmap = Bitmap.createBitmap(400, 800, Bitmap.Config.ARGB_8888)

    @After fun cleanup() { bitmap.recycle() }

    private fun showViewer() {
        val visible = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                if (visible.value) ScreenshotViewer(bitmap, "Screenshot") { visible.value = false }
            }
        }
    }

    @Test fun doubleTapZoomPreventsDragDismissUntilZoomedOut() {
        showViewer()
        compose.onNode(isDialog()).performTouchInput { doubleClick(center) }
        compose.waitForIdle()
        compose.onNode(isDialog()).performTouchInput {
            swipe(Offset(center.x, height * 0.3f), Offset(center.x, height * 0.8f))
        }
        compose.onNode(isDialog()).assertExists()
        compose.onNode(isDialog()).performTouchInput { doubleClick(center) }
        compose.waitForIdle()
        compose.onNode(isDialog()).performTouchInput {
            swipe(Offset(center.x, height * 0.3f), Offset(center.x, height * 0.8f))
        }
        compose.onNode(isDialog()).assertDoesNotExist()
    }

    @Test fun shortDragsResetInsteadOfAccumulatingTowardDismissal() {
        showViewer()
        repeat(4) {
            compose.onNode(isDialog()).performTouchInput {
                swipe(center, center + Offset(0f, height * 0.04f))
            }
            compose.waitForIdle()
            compose.onNode(isDialog()).assertExists()
        }
        compose.onNode(isDialog()).performTouchInput {
            swipe(Offset(center.x, height * 0.3f), Offset(center.x, height * 0.8f))
        }
        compose.onNode(isDialog()).assertDoesNotExist()
    }
}
