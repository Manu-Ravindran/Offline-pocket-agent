package com.offline.pocketagent

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The entire interface uses Android views; no web service or account is required. */
class MainActivity : ComponentActivity() {
    private lateinit var controller: AppController
    private lateinit var page: LinearLayout
    private lateinit var modelLabel: TextView
    private lateinit var projectorLabel: TextView
    private lateinit var clearVisionButton: Button
    private lateinit var statusLabel: TextView
    private lateinit var fileList: LinearLayout
    private lateinit var questionInput: EditText
    private lateinit var progress: ProgressBar
    private lateinit var answerCard: LinearLayout
    private lateinit var answerText: TextView
    private lateinit var sourcesLabel: TextView
    private lateinit var sourcesText: TextView
    private lateinit var stepsLabel: TextView
    private lateinit var stepsText: TextView
    private val controls = mutableListOf<View>()
    private var selectedModel: Uri? = null
    private var selectedProjector: Uri? = null
    private var cameraUri: Uri? = null
    private var busy = false
    private var displayedAnswer: AgentAnswer? = null

    private val dark: Boolean
        get() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    private val pageColor: Int get() = color(if (dark) "#101916" else "#F5F7F2")
    private val surface: Int get() = color(if (dark) "#1A2721" else "#FFFFFF")
    private val ink: Int get() = color(if (dark) "#F0F6F0" else "#163B30")
    private val secondary: Int get() = color(if (dark) "#ADBDB2" else "#5A6D62")
    private val accent: Int get() = color(if (dark) "#A8DFBD" else "#205D42")
    private val accentText: Int get() = color(if (dark) "#123721" else "#FFFFFF")
    private val border: Int get() = color(if (dark) "#394A40" else "#DAE3D9")
    private val soft: Int get() = color(if (dark) "#253A2C" else "#EAF2E8")

    private val pickDocuments = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) runTask("Reading documents…") {
            var importedCount = 0
            val failures = mutableListOf<String>()
            uris.forEach { uri ->
                rememberUri(uri)
                try {
                    controller.importDocument(uri)
                    importedCount += 1
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    failures += "${displayName(uri)}: ${error.message ?: "Could not read this file"}"
                }
            }
            refreshDocuments()
            if (failures.isEmpty()) {
                statusLabel.text = "$importedCount ${if (importedCount == 1) "file is" else "files are"} ready. Ask a question below."
            } else {
                statusLabel.text = "$importedCount imported. ${failures.joinToString("\n")}"
            }
        }
    }

    private val pickModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            rememberUri(it)
            selectedModel = it
            modelLabel.text = "Model: ${displayName(it)}"
            statusLabel.text = "Model selected. Tap Load model to start it."
        }
    }

    private val pickProjector = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            rememberUri(it)
            selectedProjector = it
            projectorLabel.text = "Vision: ${displayName(it)}"
            clearVisionButton.visibility = View.VISIBLE
            statusLabel.text = "Vision file selected. It must match your model. Tap Load model."
        }
    }

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
        val uri = cameraUri
        cameraUri = null
        if (captured && uri != null) runTask("Reading your camera image…") {
            controller.importDocument(uri)
            refreshDocuments()
            statusLabel.text = "Camera image added. Ask about its text or contents."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedModel = savedInstanceState?.getString("selectedModel")?.let(Uri::parse)
        selectedProjector = savedInstanceState?.getString("selectedProjector")?.let(Uri::parse)
        cameraUri = savedInstanceState?.getString("cameraUri")?.let(Uri::parse)
        controller = AppController(applicationContext) { status ->
            runOnUiThread { if (!isDestroyed && ::statusLabel.isInitialized) statusLabel.text = status }
        }
        createContent()
        savedInstanceState?.getString("question")?.let { questionInput.setText(it) }
        savedInstanceState?.getString("answer")?.let { text ->
            showAnswer(AgentAnswer(
                text,
                savedInstanceState.getStringArrayList("sources") ?: emptyList(),
                savedInstanceState.getStringArrayList("steps") ?: emptyList()
            ))
        }
        runTask("Opening your document library…") {
            refreshDocuments()
            statusLabel.text = "Ready. ${controller.modelSummary()}"
        }
    }

    private fun createContent() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(pageColor)
            isFillViewport = true
            clipToPadding = false
        }
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(28))
        }
        scroll.addView(page, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or
                    WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        setContentView(scroll)
        scroll.requestApplyInsets()

        val wordmark = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        wordmark.addView(label("POCKET AGENT", 13, true).apply { letterSpacing = .12f },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        wordmark.addView(label("ON DEVICE", 10, true).apply {
            setTextColor(accent)
            background = shape(soft, 30)
            setPadding(dp(11), dp(7), dp(11), dp(7))
            contentDescription = "All processing happens on this device"
        })
        page.addView(wordmark)
        page.addView(label("Your documents.\nYour own AI.", 32, true).apply {
            setPadding(0, dp(24), 0, dp(8))
            setLineSpacing(dp(2).toFloat(), 1f)
        })
        page.addView(label("Read, explore, and ask questions — right here on your phone.", 15).apply {
            setTextColor(secondary)
            setLineSpacing(dp(3).toFloat(), 1f)
        })

        val modelCard = card()
        sectionTitle(modelCard, "01", "Your local model")
        modelCard.addView(description("Choose a GGUF model stored on your phone. Add its matching vision file to understand images."))
        modelLabel = description(selectedModel?.let { "Model: ${displayName(it)}" } ?: controller.modelSummary())
        modelCard.addView(modelLabel)
        projectorLabel = description(selectedProjector?.let { "Vision: ${displayName(it)}" } ?: "Vision file: optional")
        modelCard.addView(projectorLabel)
        buttonRow(modelCard,
            "Choose model" to { pickModel.launch(arrayOf("*/*")) },
            "Vision file" to { pickProjector.launch(arrayOf("*/*")) })
        clearVisionButton = button("Clear selected vision file") {
            selectedProjector = null
            projectorLabel.text = "Vision file: optional"
            clearVisionButton.visibility = View.GONE
            statusLabel.text = "Vision file cleared. Load the model to apply this change."
        }.apply { visibility = if (selectedProjector == null) View.GONE else View.VISIBLE }
        modelCard.addView(clearVisionButton, fullWidthParams(top = 8))
        modelCard.addView(button("Load model", primary = true) {
            val model = selectedModel
            if (model == null) {
                Toast.makeText(this, "Choose a model file first.", Toast.LENGTH_SHORT).show()
            } else runTask("Loading your local model…") {
                val result = controller.configureModel(model, selectedProjector)
                modelLabel.text = result
                statusLabel.text = "Model ready. Add your documents or ask a question."
            }
        }, fullWidthParams(top = 10))

        val docsCard = card()
        sectionTitle(docsCard, "02", "Your documents")
        docsCard.addView(description("Word · Excel · PDF · Camera images"))
        buttonRow(docsCard,
            "Add files" to {
                pickDocuments.launch(arrayOf(
                    "application/pdf",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/msword", "application/vnd.ms-excel", "image/*"
                ))
            },
            "Camera" to { launchCamera() })
        fileList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        docsCard.addView(fileList, fullWidthParams(top = 12))

        val askCard = card()
        sectionTitle(askCard, "03", "Ask Pocket Agent")
        questionInput = EditText(this).apply {
            hint = "Summarize this document, compare figures, or explain this image…"
            textSize = 16f
            setTextColor(ink)
            setHintTextColor(secondary)
            minLines = 3
            maxLines = 8
            gravity = Gravity.TOP or Gravity.START
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = shape(pageColor, 12, border)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            contentDescription = "Question about your documents"
        }
        controls += questionInput
        askCard.addView(questionInput, fullWidthParams(top = 10))
        askCard.addView(button("Ask Pocket Agent", primary = true) {
            val question = questionInput.text.toString().trim()
            if (question.isEmpty()) {
                questionInput.error = "Enter a question first."
                questionInput.requestFocus()
            } else runTask("Working with your files…") {
                showAnswer(controller.ask(question))
                statusLabel.text = "Done. You can ask another question."
                scroll.post { scroll.smoothScrollTo(0, answerCard.top) }
            }
        }, fullWidthParams(top = 12))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
            progressTintList = android.content.res.ColorStateList.valueOf(accent)
            indeterminateTintList = android.content.res.ColorStateList.valueOf(accent)
        }
        askCard.addView(progress, fullWidthParams(top = 12))
        statusLabel = description("Ready. Choose a model and add a document.").apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setTextIsSelectable(true)
        }
        askCard.addView(statusLabel, fullWidthParams(top = 8))

        answerCard = card().apply { visibility = View.GONE }
        answerCard.addView(label("Answer", 21, true))
        answerText = label("", 16).apply {
            setTextIsSelectable(true)
            setLineSpacing(dp(5).toFloat(), 1f)
        }
        answerCard.addView(answerText, fullWidthParams(top = 12))
        sourcesLabel = label("Sources", 13, true).apply { setTextColor(accent) }
        answerCard.addView(sourcesLabel, fullWidthParams(top = 20))
        sourcesText = description("").apply { setTextIsSelectable(true) }
        answerCard.addView(sourcesText)
        stepsLabel = label("Actions taken", 13, true).apply { setTextColor(accent) }
        answerCard.addView(stepsLabel, fullWidthParams(top = 16))
        stepsText = description("").apply { setTextIsSelectable(true) }
        answerCard.addView(stepsText)

        page.addView(description("Your model, documents, and answers stay on this phone.").apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), 0)
        }, fullWidthParams(top = 10))
    }

    private suspend fun refreshDocuments() {
        val documents = controller.listDocuments()
        fileList.removeAllViews()
        if (documents.isEmpty()) {
            fileList.addView(description("No documents yet. Add a file or take a photo to begin.").apply {
                background = shape(soft, 12)
                setPadding(dp(14), dp(14), dp(14), dp(14))
            })
            return
        }
        documents.forEach { document ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = shape(pageColor, 10)
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            row.addView(label(document.name, 14, true).apply { setTextIsSelectable(true) })
            row.addView(description(document.summary))
            fileList.addView(row, fullWidthParams(top = 7))
        }
    }

    private fun showAnswer(answer: AgentAnswer) {
        displayedAnswer = answer
        answerText.text = answer.text
        sourcesText.text = answer.sources.joinToString("\n\n")
        stepsText.text = answer.steps.mapIndexed { index, step -> "${index + 1}. $step" }.joinToString("\n")
        sourcesLabel.visibility = if (answer.sources.isEmpty()) View.GONE else View.VISIBLE
        sourcesText.visibility = sourcesLabel.visibility
        stepsLabel.visibility = if (answer.steps.isEmpty()) View.GONE else View.VISIBLE
        stepsText.visibility = stepsLabel.visibility
        answerCard.visibility = View.VISIBLE
    }

    private fun runTask(initialStatus: String, block: suspend () -> Unit) {
        if (busy) return
        lifecycleScope.launch {
            setBusy(true)
            statusLabel.text = initialStatus
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                statusLabel.text = error.message ?: "Something went wrong. Please try again."
                Toast.makeText(this@MainActivity, "Could not finish this action. See the status below.", Toast.LENGTH_LONG).show()
            } finally {
                setBusy(false)
            }
        }
    }

    private fun setBusy(value: Boolean) {
        busy = value
        controls.forEach {
            it.isEnabled = !value
            it.alpha = if (value) .55f else 1f
        }
        progress.visibility = if (value) View.VISIBLE else View.GONE
    }

    private fun launchCamera() {
        try {
            val directory = File(cacheDir, "camera").apply { mkdirs() }
            val file = File.createTempFile("capture-", ".jpg", directory)
            cameraUri = FileProvider.getUriForFile(this, "$packageName.files", file)
            takePicture.launch(cameraUri!!)
        } catch (error: Exception) {
            statusLabel.text = "Could not open the camera: ${error.message ?: "No camera app available"}"
        }
    }

    private fun rememberUri(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Camera output and some document providers only grant temporary access.
        }
    }

    private fun displayName(uri: Uri): String = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment ?: "Selected file"
    } catch (_: Exception) {
        uri.lastPathSegment ?: "Selected file"
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(surface, 18, border)
        setPadding(dp(18), dp(18), dp(18), dp(18))
        page.addView(this, fullWidthParams(top = 18))
    }

    private fun sectionTitle(parent: LinearLayout, number: String, title: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(label(number, 11, true).apply {
            setTextColor(accent)
            gravity = Gravity.CENTER
            background = shape(soft, 10)
        }, LinearLayout.LayoutParams(dp(32), dp(32)))
        row.addView(label(title, 18, true), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(10) })
        parent.addView(row)
    }

    private fun buttonRow(parent: LinearLayout, first: Pair<String, () -> Unit>, second: Pair<String, () -> Unit>) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button(first.first, onClick = first.second), LinearLayout.LayoutParams(0, dp(50), 1f).apply {
            marginEnd = dp(5)
        })
        row.addView(button(second.first, onClick = second.second), LinearLayout.LayoutParams(0, dp(50), 1f).apply {
            marginStart = dp(5)
        })
        parent.addView(row, fullWidthParams(top = 12))
    }

    private fun button(title: String, primary: Boolean = false, onClick: () -> Unit): Button = Button(this).apply {
        text = title
        textSize = 14f
        isAllCaps = false
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        minHeight = dp(50)
        minimumHeight = dp(50)
        setTextColor(if (primary) accentText else ink)
        background = RippleDrawable(
            android.content.res.ColorStateList.valueOf(color(if (primary) "#33FFFFFF" else "#26205D42")),
            shape(if (primary) accent else surface, 12, if (primary) null else border),
            shape(Color.WHITE, 12)
        )
        setPadding(dp(10), dp(8), dp(10), dp(8))
        setOnClickListener { onClick() }
        controls += this
    }

    private fun label(value: String, size: Int, bold: Boolean = false): TextView = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(ink)
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
    }

    private fun description(value: String): TextView = label(value, 13).apply {
        setTextColor(secondary)
        setLineSpacing(dp(3).toFloat(), 1f)
        setPadding(0, dp(7), 0, 0)
    }

    private fun fullWidthParams(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun shape(fill: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        stroke?.let { setStroke(dp(1), it) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + .5f).toInt()
    private fun color(value: String): Int = Color.parseColor(value)

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("selectedModel", selectedModel?.toString())
        outState.putString("selectedProjector", selectedProjector?.toString())
        outState.putString("cameraUri", cameraUri?.toString())
        outState.putString("question", questionInput.text.toString())
        displayedAnswer?.let {
            outState.putString("answer", it.text)
            outState.putStringArrayList("sources", ArrayList(it.sources))
            outState.putStringArrayList("steps", ArrayList(it.steps))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        controller.close()
    }
}
