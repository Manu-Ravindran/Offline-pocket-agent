package com.offline.pocketagent

import android.app.Dialog
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import java.io.File
import java.time.Duration
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog

/** Exercise the actual dialog while startup is queued, without loading native model weights. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class SettingsUiTest {
    private lateinit var mainDispatcher: TestDispatcher
    private var activityController: ActivityController<MainActivity>? = null

    @Before fun pauseStartupAndClearSavedSetup() {
        mainDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(mainDispatcher)
        RuntimeEnvironment.getApplication().getSharedPreferences("pocket_agent", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After fun closeActivityAndRestoreMainDispatcher() {
        try {
            ShadowDialog.getLatestDialog()?.dismiss()
            activityController?.pause()?.stop()?.destroy()
            mainDispatcher.scheduler.runCurrent()
            frames()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    @Config(qualifiers = "w393dp-h852dp-port")
    fun settingsDuringStartupShowsRealStatusAndSetupActions() {
        val activity = createActivity()
        val model = ViewModelProvider(activity)[PocketChatViewModel::class.java]
        assertTrue("Startup is deliberately queued for this regression", model.state.value.busy)

        val dialog = openSettings(activity)
        val decor = dialog.window!!.decorView
        assertVisible(tagged(decor, "settings.title"))
        assertStatusSection(decor, "language-model")
        assertStatusSection(decor, "vision-file")
        assertStatusSection(decor, "image-analysis")
        assertVisible(text(decor, "Choose Qwen3.5-4B model"))
        assertVisible(text(decor, "Choose mmproj-F16.gguf vision file"))
        assertTrue("The settings window needs a positive measured height", dialog.window!!.attributes.height > 0)
        assertTrue("The settings window must fit the available display",
            dialog.window!!.attributes.height < activity.windowManager.currentWindowMetrics.bounds.height())
    }

    @Test
    @Config(qualifiers = "w740dp-h360dp-land")
    fun landscapeKeepsHeaderVisibleAndMakesLowerStatusRowsReachable() {
        val dialog = openSettings(createActivity())
        val decor = dialog.window!!.decorView
        val header = tagged(decor, "settings.title")
        val headerBefore = visibleBounds(header)
        val scroll = tagged(decor, "settings.scroll") as ScrollView
        val image = tagged(decor, "settings.image-analysis")
        val destination = Rect(0, 0, image.width, image.height)
        scroll.offsetDescendantRectToMyCoords(image, destination)
        scroll.scrollTo(0, destination.top)
        frames()

        assertTrue("The short landscape sheet must offer real scrolling", scroll.scrollY > 0)
        assertVisible(image)
        assertStatusSection(decor, "image-analysis")
        assertEquals("Scrolling settings must not hide or move its fixed title", headerBefore, visibleBounds(header))
        assertVisible(tagged(decor, "settings.close"))
    }

    @Test
    @Config(qualifiers = "w393dp-h852dp-port-night")
    fun nightModeDoesNotProduceWhiteTextOnTheWhiteSettingsPanel() {
        val dialog = openSettings(createActivity())
        val decor = dialog.window!!.decorView
        for (tag in listOf("settings.title", "settings.language-model.state", "settings.language-model.detail",
            "settings.vision-file.state", "settings.image-analysis.state", "settings.image-analysis.detail")) {
            val label = tagged(decor, tag) as TextView
            assertVisible(label)
            assertNotEquals("Settings text must remain legible in night mode: $tag", Color.WHITE, label.currentTextColor)
            assertTrue("Status text cannot be transparent: $tag", Color.alpha(label.currentTextColor) > 0)
        }
    }

    @Test
    @Config(qualifiers = "w393dp-h852dp-port")
    fun selectedProjectorCanBeAppliedToAnAlreadySavedLanguageModel() {
        val application = RuntimeEnvironment.getApplication()
        val directory = File(application.filesDir, "models/${UUID.randomUUID()}").apply { mkdirs() }
        val modelFile = File(directory, "model.gguf").apply { writeText("GGUF") }
        application.getSharedPreferences("pocket_agent", Context.MODE_PRIVATE).edit()
            .putString("model_path", modelFile.absolutePath)
            .putString("model_name", "Qwen3.5-4B-Q4_K_M.gguf").commit()
        val activity = createActivity()
        val model = ViewModelProvider(activity)[PocketChatViewModel::class.java]
        model.stagedProjector = Uri.parse("content://test.documents/mmproj-F16.gguf")
        assertNull("The large language model must not need to be selected again", model.stagedModel)

        val dialog = openSettings(activity)
        val decor = dialog.window!!.decorView
        val apply = text(decor, "Use saved model + selected vision file")
        val scroll = tagged(decor, "settings.scroll") as ScrollView
        val destination = Rect(0, 0, apply.width, apply.height)
        scroll.offsetDescendantRectToMyCoords(apply, destination)
        scroll.scrollTo(0, destination.top)
        frames()
        assertVisible(apply)
        assertTrue((tagged(decor, "settings.language-model.detail") as TextView).text.contains("Qwen3.5-4B"))
    }

    @Test
    @Config(qualifiers = "w393dp-h852dp-port")
    fun logsCanBeCollectedFromSettingsDuringStartupWithExportActions() {
        val activity = createActivity()
        val model = ViewModelProvider(activity)[PocketChatViewModel::class.java]
        val settings = openSettings(activity)
        val decor = settings.window!!.decorView
        val collect = tagged(decor, "settings.collect-logs")
        val scroll = tagged(decor, "settings.scroll") as ScrollView
        val destination = Rect(0, 0, collect.width, collect.height)
        scroll.offsetDescendantRectToMyCoords(collect, destination)
        scroll.scrollTo(0, destination.top)
        frames()

        assertTrue("Log collection must be accessible before startup completes", model.state.value.busy)
        assertVisible(collect)
        assertTrue("Diagnostics must remain enabled while startup is queued", collect.isEnabled)
        assertFalse("This test must not import or load native model weights", model.controller.modelStatus().modelImported)
        assertTrue(collect.performClick())

        // Release no-model startup and the collection launch. IO reads real bounded app logs;
        // poll its Main continuation with a deadline rather than relying on a fixed sleep.
        val reportDialog = runBlocking {
            withTimeout(5_000) {
                var report: Dialog? = null
                while (report == null) {
                    mainDispatcher.scheduler.runCurrent()
                    frames()
                    val latest = ShadowDialog.getLatestDialog()
                    if (latest !== settings && latest?.isShowing == true &&
                        latest.window?.decorView?.findViewWithTag<View>("diagnostics.report") != null) {
                        report = latest
                    } else {
                        delay(10)
                    }
                }
                requireNotNull(report)
            }
        }
        val reportDecor = reportDialog.window!!.decorView
        val report = descendants(reportDecor).filterIsInstance<TextView>()
            .firstOrNull { it.text.startsWith("Pocket Agent diagnostic report\n") }
        assertNotNull("Collect logs must show the actual technical report", report)
        assertVisible(report!!)
        assertTrue("A complete report must include model readiness", report.text.contains("Model state\n"))
        assertTrue("A complete report must include its closing marker", report.text.contains("End of diagnostic report."))
        for ((tag, label) in listOf("copy" to "Copy", "download" to "Download", "share" to "Share")) {
            val action = tagged(reportDecor, "diagnostics.$tag") as TextView
            assertEquals(label, action.text.toString())
            assertVisible(action)
            assertTrue("The $label report action must be usable", action.isEnabled)
        }
        assertTrue(tagged(reportDecor, "diagnostics.copy").performClick())
        val clipboard = activity.getSystemService(ClipboardManager::class.java)
        assertEquals("Copy must preserve the complete report", report.text.toString(),
            clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertFalse("Collecting logs must not load native weights", model.controller.modelStatus().modelLoaded)
    }

    private fun createActivity(): MainActivity {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        activityController = controller
        frames()
        return controller.get()
    }

    private fun openSettings(activity: MainActivity): Dialog {
        val button = descendants(activity.window.decorView)
            .firstOrNull { it.contentDescription?.toString() == "Model and chat settings" }
        assertNotNull("The settings action must exist on the chat toolbar", button)
        assertTrue("The settings action must work while the model is starting", button!!.isEnabled)
        assertTrue(button.performClick())
        frames()
        val dialog = ShadowDialog.getLatestDialog()
        assertNotNull("Clicking Settings must open a dialog", dialog)
        assertTrue(dialog!!.isShowing)
        return dialog
    }

    private fun assertStatusSection(decor: View, name: String) {
        val row = tagged(decor, "settings.$name")
        val state = tagged(decor, "settings.$name.state") as TextView
        val detail = tagged(decor, "settings.$name.detail") as TextView
        assertVisible(row)
        assertTrue("The $name status must contain a concrete value", state.text.isNotBlank())
        assertFalse("The $name status must not stay at its placeholder", state.text.contains("Checking"))
        assertTrue("The $name status needs an explanation", detail.text.isNotBlank())
    }

    private fun tagged(root: View, tag: String): View =
        requireNotNull(root.findViewWithTag<View>(tag)) { "Missing settings view: $tag" }

    private fun text(root: View, value: String): TextView =
        requireNotNull(descendants(root).filterIsInstance<TextView>().firstOrNull { it.text.toString() == value }) {
            "Missing settings action: $value"
        }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun assertVisible(view: View) {
        assertTrue("The settings view is not shown: ${view.tag ?: view.contentDescription}", view.isShown)
        val bounds = visibleBounds(view)
        assertTrue("The settings view has no visible area: ${view.tag ?: view.contentDescription}", bounds.width() > 0 && bounds.height() > 0)
    }

    private fun visibleBounds(view: View): Rect = Rect().also {
        assertTrue("The settings view is outside the visible window: ${view.tag ?: view.contentDescription}", view.getGlobalVisibleRect(it))
    }

    private fun frames() {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(40))
    }
}
