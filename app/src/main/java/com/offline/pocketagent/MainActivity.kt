package com.offline.pocketagent

import android.app.AlertDialog
import android.app.Application
import android.app.Dialog
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.PersistableBundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import com.offline.pocketagent.output.AnswerExport
import com.offline.pocketagent.output.AnswerExportCache
import com.offline.pocketagent.output.AnswerExports
import com.offline.pocketagent.output.ExportDestinationPolicy
import com.offline.pocketagent.output.MarkdownBlock
import com.offline.pocketagent.output.MarkdownDocument
import com.offline.pocketagent.output.NativeMarkdownRenderer
import com.offline.pocketagent.output.PendingAnswerExport
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PocketChatMessage(
    val id: Long,
    val role: String,
    val text: String,
    val pending: Boolean = false,
    val error: Boolean = false,
    val sources: List<String> = emptyList(),
    val steps: List<String> = emptyList(),
)

data class PocketChatState(
    val busy: Boolean = false,
    val operation: String? = null,
    val hasModel: Boolean = false,
    val modelReady: Boolean = false,
    val modelSummary: String = "No local model selected.",
    val documents: List<ImportedDocument> = emptyList(),
    val messages: List<PocketChatMessage> = emptyList(),
    val error: String? = null,
)

/** Retain the loaded model and active conversation across screen recreation. */
class PocketChatViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(PocketChatState())
    val state = _state.asStateFlow()
    private val _status = MutableStateFlow("Starting…")
    val status = _status.asStateFlow()
    val controller = AppController(application) { _status.value = it }
    private val nextId = AtomicLong(System.currentTimeMillis())
    private var operationJob: Job? = null
    private var initialized = false
    var stagedModel: Uri? = null
    var stagedProjector: Uri? = null
    var pendingDownload: PendingAnswerExport? = null
    private val exportCache = AnswerExportCache(application.cacheDir)

    fun initialize() {
        if (initialized) return
        initialized = true
        startOperation("startup") {
            val documents = controller.listDocuments()
            val hasModel = controller.hasSavedModel()
            _state.update { it.copy(documents = documents, hasModel = hasModel) }
            if (hasModel) {
                _status.value = "Loading your saved model…"
                val ready = controller.warmUpSavedModel()
                _state.update { it.copy(modelReady = ready) }
            }
        }
    }

    fun importDocuments(uris: List<Uri>, cleanup: () -> Unit = {}) {
        if (uris.isEmpty() || _state.value.busy) { cleanup(); return }
        startOperation("import") {
            try {
                val failures = mutableListOf<String>()
                var count = 0
                for (uri in uris) {
                    try {
                        controller.importDocument(uri)
                        count += 1
                        val documents = controller.listDocuments()
                        _state.update { it.copy(documents = documents) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        failures += error.message ?: "A selected file could not be read."
                    }
                }
                if (count > 0) notice("Added $count ${if (count == 1) "file" else "files"}. Your documents are ready to explore.")
                if (failures.isNotEmpty()) _state.update { it.copy(error = failures.joinToString("\n")) }
            } finally {
                cleanup()
            }
        }
    }

    fun configureModel() {
        val model = stagedModel ?: return
        val projector = stagedProjector
        startOperation("model") {
            _status.value = "Importing your model…"
            val result = controller.configureModel(model, projector)
            stagedModel = null
            stagedProjector = null
            _state.update { it.copy(modelReady = true, hasModel = true) }
            notice(result)
        }
    }

    fun retrySavedModel() = startOperation("model") {
        _status.value = "Loading your saved model…"
        val ready = controller.warmUpSavedModel()
        _state.update { it.copy(modelReady = ready) }
    }

    fun send(question: String) {
        if (_state.value.busy || question.isBlank()) return
        val replyId = nextId.incrementAndGet()
        val user = PocketChatMessage(nextId.incrementAndGet(), "user", question.trim())
        val assistant = PocketChatMessage(replyId, "assistant", "", pending = true)
        val attachmentIds = _state.value.documents.map { it.id }
        _state.update { it.copy(busy = true, operation = "generation", error = null,
            messages = (it.messages + user + assistant).takeLast(60)) }
        _status.value = "Thinking…"
        operationJob = viewModelScope.launch {
            val lastPartial = AtomicLong(0)
            try {
                val answer = controller.ask(question.trim(), documentIds = attachmentIds, onPartial = { partial ->
                    val now = SystemClock.uptimeMillis()
                    val previous = lastPartial.get()
                    if (now - previous >= 50 && lastPartial.compareAndSet(previous, now)) {
                        updateMessage(replyId) { if (it.pending) it.copy(text = partial) else it }
                    }
                })
                updateMessage(replyId) { it.copy(text = answer.text, pending = false,
                    sources = answer.sources, steps = answer.steps) }
                _state.update { it.copy(modelReady = true) }
            } catch (cancelled: CancellationException) {
                updateMessage(replyId) { it.copy(text = it.text.ifBlank { "Response stopped." }, pending = false) }
                throw cancelled
            } catch (error: Exception) {
                updateMessage(replyId) { it.copy(text = error.message ?: "I could not finish this response. Please try again.",
                    pending = false, error = true) }
            } finally {
                finishOperation()
            }
        }
    }

    fun stop() {
        _status.value = "Stopping…"
        controller.cancelGeneration()
        operationJob?.cancel()
    }

    fun dismissError() = _state.update { it.copy(error = null) }
    fun reportError(message: String) = _state.update { it.copy(error = message) }
    suspend fun previewDocument(id: String): String {
        check(!_state.value.busy) { "Finish the current action before opening this preview." }
        _state.update { it.copy(busy = true, operation = "preview") }
        _status.value = "Opening extracted text…"
        return try { controller.previewDocument(id) } finally { finishOperation() }
    }
    fun newChat() {
        if (_state.value.busy) return
        _state.update { it.copy(documents = emptyList(), messages = emptyList(), error = null) }
        controller.clearConversation()
    }

    fun clearImportedFiles() = startOperation("clear") {
        val count = controller.clearImportedDocuments()
        _state.update { it.copy(documents = emptyList(), messages = emptyList()) }
        notice("Removed $count imported ${if (count == 1) "copy" else "copies"}. Your original files and saved model are unchanged.")
    }

    suspend fun stageExport(export: AnswerExport): PendingAnswerExport = withContext(Dispatchers.IO) { exportCache.stage(export) }
    fun exportFile(export: PendingAnswerExport): File = exportCache.file(export)
    fun discardExport(export: PendingAnswerExport?) = exportCache.discard(export)

    fun saveExport(uri: Uri, export: PendingAnswerExport) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ExportDestinationPolicy.requireExternalContent(uri.scheme, uri.authority,
                        "${getApplication<Application>().packageName}.files")
                    exportCache.file(export).inputStream().use { input ->
                        requireNotNull(getApplication<Application>().contentResolver.openOutputStream(uri, "wt")) {
                            "The selected destination could not be opened."
                        }.use { output -> input.copyTo(output) }
                    }
                }
                notice("Saved ${export.fileName}.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { reportError(error.message ?: "The answer could not be saved.") }
            finally { exportCache.discard(export) }
        }
    }

    private fun notice(text: String) = _state.update { it.copy(
        messages = (it.messages + PocketChatMessage(nextId.incrementAndGet(), "notice", text)).takeLast(60)) }

    private fun updateMessage(id: Long, transform: (PocketChatMessage) -> PocketChatMessage) = _state.update { state ->
        state.copy(messages = state.messages.map { if (it.id == id) transform(it) else it })
    }

    private fun startOperation(kind: String, block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, operation = kind, error = null) }
        operationJob = viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(error = "Action stopped.") }
                throw cancelled
            } catch (error: Exception) {
                _state.update { it.copy(error = error.message ?: "This action could not be completed.") }
            } finally {
                finishOperation()
            }
        }
    }

    private fun finishOperation() {
        _state.update { it.copy(busy = false, operation = null,
            hasModel = controller.hasSavedModel(), modelSummary = controller.modelSummary()) }
        _status.value = "Ready"
        operationJob = null
    }

    override fun onCleared() {
        controller.cancelGeneration()
        controller.close()
        super.onCleared()
    }
}

/** One native chat screen, with model configuration tucked into settings. */
class MainActivity : ComponentActivity() {
    private val chat: PocketChatViewModel by viewModels()
    private lateinit var root: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var chatColumn: LinearLayout
    private lateinit var welcome: LinearLayout
    private lateinit var welcomeSetup: Button
    private lateinit var toolbarStatus: TextView
    private lateinit var toolbarProgress: ProgressBar
    private lateinit var attachmentScroll: HorizontalScrollView
    private lateinit var attachmentRow: LinearLayout
    private lateinit var errorBanner: LinearLayout
    private lateinit var errorLabel: TextView
    private lateinit var questionInput: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var attachButton: ImageButton
    private lateinit var cameraButton: ImageButton
    private var cameraUri: Uri? = null
    private var cameraFile: File? = null
    private var modelDialog: Dialog? = null
    private var lastDocuments: List<ImportedDocument>? = null
    private var previousBusy = false
    private var latestStatus = "Starting…"
    private val messageViews = linkedMapOf<Long, MessageViews>()
    private val navy = color("#172A3C")
    private val teal = color("#167D73")
    private val muted = color("#73808C")
    private val line = color("#EAF0F3")
    private val soft = color("#F4F7F9")
    private val markdownRenderer by lazy { NativeMarkdownRenderer(this) }

    private data class MessageViews(
        val root: View, val body: TextView, val activity: LinearLayout? = null,
        val activityLabel: TextView? = null, val actions: LinearLayout? = null,
        val sources: Button? = null, val details: Button? = null,
        val formatted: LinearLayout? = null,
        var displayed: PocketChatMessage? = null,
    )

    private val pickDocuments = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { chat.importDocuments(it) }
    private val pickModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { chat.stagedModel = uri; showModelSettings() }
    }
    private val pickProjector = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { chat.stagedProjector = uri; showModelSettings() }
    }
    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
        val uri = cameraUri
        val file = cameraFile
        cameraUri = null
        cameraFile = null
        uri?.let(::revokeCameraGrant)
        if (captured && uri != null) chat.importDocuments(listOf(uri), cleanup = { file?.delete() })
        else file?.delete()
    }
    private val createAnswerDocument = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val pending = chat.pendingDownload
        chat.pendingDownload = null
        val uri = result.data?.data
        if (result.resultCode == RESULT_OK && uri != null && pending != null) chat.saveExport(uri, pending)
        else chat.discardExport(pending)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        cameraUri = savedInstanceState?.getString("cameraUri")?.let(Uri::parse)
        cameraFile = savedInstanceState?.getString("cameraFile")?.takeIf {
            it.matches(Regex("capture-[A-Za-z0-9-]+\\.jpg"))
        }?.let { File(File(cacheDir, "camera"), it) }
        if (chat.pendingDownload == null) {
            val cacheName = savedInstanceState?.getString("exportCacheName")
            val fileName = savedInstanceState?.getString("exportFileName")
            val mime = savedInstanceState?.getString("exportMime")
            if (cacheName != null && fileName != null && mime != null) {
                chat.pendingDownload = PendingAnswerExport(fileName, mime, cacheName)
            }
        }
        createContent()
        savedInstanceState?.getString("draft")?.let(questionInput::setText)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { chat.state.collect(::render) }
                launch { chat.status.collect { latestStatus = it; renderStatus() } }
            }
        }
        chat.initialize()
    }

    private fun createContent() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsets.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
            insets
        }
        setContentView(root)
        root.requestApplyInsets()
        buildToolbar()
        root.addView(View(this).apply { setBackgroundColor(line) }, LinearLayout.LayoutParams(-1, dp(1)))
        chatScroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            setPadding(dp(18), dp(16), dp(18), dp(18))
        }
        chatColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        chatScroll.addView(chatColumn, ViewGroup.LayoutParams(-1, -2))
        root.addView(chatScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        buildWelcome()
        buildComposer()
    }

    private fun buildToolbar() {
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(10), dp(12), dp(10))
        }
        toolbar.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_pocket_agent); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(42), dp(42)))
        val title = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(11), 0, 0, 0) }
        title.addView(label("Pocket Agent", 19, true))
        val statusRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        toolbarProgress = ProgressBar(this).apply {
            isIndeterminate = true; indeterminateTintList = ColorStateList.valueOf(teal); visibility = View.GONE
        }
        statusRow.addView(toolbarProgress, LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(6) })
        toolbarStatus = label("Private · On device", 12).apply {
            setTextColor(muted); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        statusRow.addView(toolbarStatus, LinearLayout.LayoutParams(-1, -2))
        title.addView(statusRow, fullWidth(top = 3))
        toolbar.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        toolbar.addView(iconButton(R.drawable.ic_settings, "Model and chat settings") { showModelSettings() },
            LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginStart = dp(6) })
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(76)))
    }

    private fun buildWelcome() {
        welcome = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(14), dp(28), dp(14), dp(26))
        }
        welcome.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_pocket_agent); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(78), dp(78)))
        welcome.addView(label("A little help.\nEntirely on your phone.", 28, true).apply {
            gravity = Gravity.CENTER; setLineSpacing(dp(3).toFloat(), 1f)
        }, fullWidth(top = 22))
        welcome.addView(label("Ask a question, or bring a document\nor photo into the conversation.", 15).apply {
            gravity = Gravity.CENTER; setTextColor(muted); setLineSpacing(dp(4).toFloat(), 1f)
        }, fullWidth(top = 12))
        welcome.addView(suggestion("Summarize a document", R.drawable.ic_document) { chooseDocuments() }, fullWidth(top = 28))
        welcome.addView(suggestion("Understand a photo", R.drawable.ic_camera) { launchCamera() }, fullWidth(top = 10))
        welcome.addView(suggestion("Explore a spreadsheet", R.drawable.ic_chart) { chooseDocuments() }, fullWidth(top = 10))
        welcomeSetup = textButton("Set up your local model") { showModelSettings() }.apply { setTextColor(teal) }
        welcome.addView(welcomeSetup, fullWidth(top = 17))
        chatColumn.addView(welcome, fullWidth())
    }

    private fun suggestion(title: String, icon: Int, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = ripple(shape(Color.WHITE, 16, line))
        setPadding(dp(16), dp(14), dp(16), dp(14))
        isClickable = true; isFocusable = true; contentDescription = title
        addView(ImageView(this@MainActivity).apply {
            setImageResource(icon); imageTintList = ColorStateList.valueOf(teal)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(22), dp(22)))
        addView(label(title, 14, true), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        addView(ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.ic_arrow_right); imageTintList = ColorStateList.valueOf(muted)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(18), dp(18)))
        setOnClickListener { if (!chat.state.value.busy) onClick() }
    }

    private fun buildComposer() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(10)); setBackgroundColor(Color.WHITE)
        }
        errorBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = shape(color("#FFF2F0"), 12); setPadding(dp(12), dp(8), dp(4), dp(8)); visibility = View.GONE
        }
        errorLabel = label("", 13).apply {
            setTextColor(color("#A83E35")); setTextIsSelectable(true)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        errorBanner.addView(errorLabel, LinearLayout.LayoutParams(0, -2, 1f))
        errorBanner.addView(iconButton(R.drawable.ic_close, "Dismiss message") { chat.dismissError() }, LinearLayout.LayoutParams(dp(40), dp(40)))
        panel.addView(errorBanner, fullWidth(bottom = 9))
        attachmentRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        attachmentScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; clipToPadding = false; visibility = View.GONE
            addView(attachmentRow, ViewGroup.LayoutParams(-2, -2))
        }
        panel.addView(attachmentScroll, fullWidth(bottom = 9))
        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = shape(soft, 23, color("#E6EDF0")); setPadding(dp(8), dp(7), dp(8), dp(7))
        }
        questionInput = EditText(this).apply {
            id = View.generateViewId(); hint = "Message Pocket Agent"; textSize = 16f
            setTextColor(navy); setHintTextColor(muted); background = null
            setPadding(dp(10), dp(7), dp(10), dp(7)); minLines = 1; maxLines = 5
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            gravity = Gravity.TOP or Gravity.START; contentDescription = "Message Pocket Agent"
        }
        composer.addView(questionInput, fullWidth())
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        attachButton = iconButton(R.drawable.ic_attach, "Attach documents or images") { chooseDocuments() }
        cameraButton = iconButton(R.drawable.ic_camera, "Take a photo") { launchCamera() }
        tools.addView(attachButton, LinearLayout.LayoutParams(dp(42), dp(42)))
        tools.addView(cameraButton, LinearLayout.LayoutParams(dp(42), dp(42)))
        tools.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        sendButton = iconButton(R.drawable.ic_send, "Send message") { onSendOrStop() }.apply {
            background = ripple(GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(teal, color("#0F625C"))).apply { cornerRadius = dp(14).toFloat() }, Color.WHITE)
            imageTintList = ColorStateList.valueOf(Color.WHITE); elevation = dp(1).toFloat()
        }
        tools.addView(sendButton, LinearLayout.LayoutParams(dp(42), dp(42)))
        composer.addView(tools, fullWidth(top = 2))
        panel.addView(composer, fullWidth())
        panel.addView(label("Private by design · No cloud required", 11).apply {
            gravity = Gravity.CENTER; setTextColor(muted)
        }, fullWidth(top = 8))
        root.addView(panel, fullWidth())
    }

    private fun render(state: PocketChatState) {
        val stickToBottom = isNearBottom()
        val incomingIds = state.messages.map { it.id }.toSet()
        messageViews.keys.filter { it !in incomingIds }.toList().forEach { id ->
            messageViews.remove(id)?.let { chatColumn.removeView(it.root) }
        }
        welcome.visibility = if (state.messages.isEmpty()) View.VISIBLE else View.GONE
        welcomeSetup.visibility = if (state.hasModel) View.GONE else View.VISIBLE
        chatColumn.gravity = if (state.messages.isEmpty()) Gravity.CENTER_VERTICAL else Gravity.TOP
        var addedMessage = false
        state.messages.forEach { message ->
            val views = messageViews[message.id] ?: createMessage(message).also {
                messageViews[message.id] = it; chatColumn.addView(it.root, fullWidth(bottom = 20)); animateArrival(it.root); addedMessage = true
            }
            if (views.displayed != message) {
                if (views.body.text.toString() != message.text) views.body.text = message.text
                val formatCompleted = views.formatted != null && !message.pending && !message.error
                if (formatCompleted && (views.displayed?.pending != false || views.displayed?.text != message.text)) {
                    markdownRenderer.render(message.text, views.formatted!!)
                }
                views.formatted?.visibility = if (formatCompleted) View.VISIBLE else View.GONE
                views.body.visibility = if (formatCompleted || (message.pending && message.text.isBlank())) View.GONE else View.VISIBLE
                views.activity?.visibility = if (message.pending) View.VISIBLE else View.GONE
                views.activityLabel?.text = latestStatus
                views.actions?.visibility = if (!message.pending && !message.error && message.text.isNotBlank()) View.VISIBLE else View.GONE
                views.sources?.apply {
                    visibility = if (message.sources.isEmpty()) View.GONE else View.VISIBLE
                    text = "${message.sources.size} ${if (message.sources.size == 1) "source" else "sources"}"
                }
                views.details?.visibility = if (message.steps.isEmpty()) View.GONE else View.VISIBLE
                views.body.setTextColor(when {
                    message.role == "user" -> Color.WHITE
                    message.role == "notice" -> muted
                    message.error -> color("#A83E35")
                    else -> navy
                })
                views.displayed = message
            }
        }
        if (lastDocuments != state.documents) { renderAttachments(state.documents); lastDocuments = state.documents }
        errorLabel.text = state.error.orEmpty()
        errorBanner.visibility = if (state.error.isNullOrBlank()) View.GONE else View.VISIBLE
        attachButton.isEnabled = !state.busy; cameraButton.isEnabled = !state.busy
        attachButton.alpha = if (state.busy) .4f else 1f; cameraButton.alpha = if (state.busy) .4f else 1f
        if (previousBusy != state.busy) {
            sendButton.setImageResource(if (state.busy) R.drawable.ic_stop else R.drawable.ic_send)
            sendButton.contentDescription = if (state.busy) "Stop current action" else "Send message"
            previousBusy = state.busy
        }
        renderStatus()
        if (addedMessage || (stickToBottom && state.messages.isNotEmpty())) scrollToBottom()
    }

    private fun createMessage(message: PocketChatMessage): MessageViews {
        if (message.role == "notice") {
            val text = label(message.text, 12).apply {
                gravity = Gravity.CENTER; setTextColor(muted); setPadding(dp(12), dp(4), dp(12), dp(4))
            }
            return MessageViews(text, text)
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = if (message.role == "user") Gravity.END else Gravity.START }
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(13), dp(16), dp(13))
            background = if (message.role == "user") GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(teal, color("#11685F"))).apply {
                cornerRadii = floatArrayOf(dp(20).toFloat(), dp(20).toFloat(), dp(6).toFloat(), dp(6).toFloat(),
                    dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat())
            } else shape(color("#F7F9FB"), 18, color("#EDF1F5"))
        }
        val body = label(message.text, 16).apply {
            setTextIsSelectable(true); setLineSpacing(dp(5).toFloat(), 1f)
            maxWidth = resources.displayMetrics.widthPixels - dp(if (message.role == "user") 92 else 70)
        }
        if (message.role == "user") {
            bubble.addView(body); row.addView(bubble, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(42) })
            return MessageViews(row, body)
        }
        bubble.addView(label("Pocket Agent", 11, true).apply { setTextColor(teal); letterSpacing = .02f }, fullWidth(bottom = 8))
        val activity = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        activity.addView(ProgressBar(this).apply { isIndeterminate = true; indeterminateTintList = ColorStateList.valueOf(teal) },
            LinearLayout.LayoutParams(dp(13), dp(13)).apply { marginEnd = dp(8) })
        val activityLabel = label("Thinking…", 12).apply { setTextColor(muted) }
        activity.addView(activityLabel, LinearLayout.LayoutParams(-1, -2))
        bubble.addView(activity, fullWidth(bottom = 8)); bubble.addView(body, fullWidth())
        val formatted = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        bubble.addView(formatted, fullWidth())
        val actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val outputActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val metadataActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val sources = textButton("Sources") {
            chat.state.value.messages.firstOrNull { it.id == message.id }?.let { showTextDialog("Sources", it.sources.joinToString("\n\n")) }
        }
        val details = textButton("Actions") {
            chat.state.value.messages.firstOrNull { it.id == message.id }?.let {
                showTextDialog("Actions taken", it.steps.mapIndexed { index, step -> "${index + 1}. $step" }.joinToString("\n\n"))
            }
        }
        val copy = textButton("Copy") {
            chat.state.value.messages.firstOrNull { it.id == message.id }?.let(::copyAnswer)
        }
        val download = textButton("Download") {
            chat.state.value.messages.firstOrNull { it.id == message.id }?.let { chooseExport(it, share = false) }
        }.apply { contentDescription = "Download or save this answer" }
        val share = textButton("Share") {
            chat.state.value.messages.firstOrNull { it.id == message.id }?.let { chooseExport(it, share = true) }
        }
        listOf(copy, download, share).forEachIndexed { index, button ->
            button.setTextColor(teal)
            button.background = ripple(shape(color("#E8F3F0"), 10))
            outputActions.addView(button, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = dp(6) })
        }
        metadataActions.addView(sources); metadataActions.addView(details)
        actions.addView(outputActions, fullWidth(top = 5))
        actions.addView(metadataActions, fullWidth(top = 2))
        bubble.addView(actions, fullWidth(top = 6)); row.addView(bubble, LinearLayout.LayoutParams(-1, -2))
        return MessageViews(row, body, activity, activityLabel, actions, sources, details, formatted)
    }

    private fun renderAttachments(documents: List<ImportedDocument>) {
        attachmentRow.removeAllViews()
        attachmentScroll.visibility = if (documents.isEmpty()) View.GONE else View.VISIBLE
        documents.forEach { document ->
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = ripple(shape(color("#EEF5F3"), 12)); setPadding(dp(10), dp(9), dp(12), dp(9))
                isClickable = true; isFocusable = true; contentDescription = "View extracted text from ${document.name}"
            }
            chip.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_document); imageTintList = ColorStateList.valueOf(teal)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(16), dp(16)))
            chip.addView(label(document.name, 12, true).apply { maxWidth = dp(170); maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE },
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(7) })
            chip.setOnClickListener {
                if (!chat.state.value.busy) lifecycleScope.launch {
                    try { showTextDialog(document.name, chat.previewDocument(document.id)) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { chat.reportError(error.message ?: "This document preview could not be opened.") }
                }
            }
            attachmentRow.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        }
    }

    private fun renderStatus() {
        if (!::toolbarStatus.isInitialized) return
        val state = chat.state.value
        toolbarProgress.visibility = if (state.busy) View.VISIBLE else View.GONE
        toolbarStatus.text = if (state.busy) latestStatus else when {
            state.modelReady -> "Ready · On device"
            state.hasModel -> "Model saved · On device"
            else -> "Private · On device"
        }
        messageViews.values.lastOrNull { it.displayed?.pending == true }?.activityLabel?.text = latestStatus
    }

    private fun onSendOrStop() {
        if (chat.state.value.busy) { chat.stop(); return }
        val question = questionInput.text.toString().trim()
        if (question.isBlank()) { questionInput.requestFocus(); return }
        if (!chat.state.value.hasModel) { showModelSettings(); return }
        sendButton.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(questionInput.windowToken, 0)
        questionInput.setText(""); chat.send(question)
    }

    private fun showModelSettings() {
        modelDialog?.dismiss()
        val state = chat.state.value
        val dialog = Dialog(this)
        modelDialog = dialog
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = shape(Color.WHITE, 26)
            setPadding(dp(23), dp(12), dp(23), dp(20))
        }
        panel.addView(View(this).apply { background = shape(color("#D8E2E8"), 3) }, LinearLayout.LayoutParams(dp(34), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(18)
        })
        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(label("Settings", 23, true), LinearLayout.LayoutParams(0, -2, 1f))
        titleRow.addView(iconButton(R.drawable.ic_close, "Close settings") { dialog.dismiss() }, LinearLayout.LayoutParams(dp(40), dp(40)))
        panel.addView(titleRow)
        panel.addView(label("YOUR LOCAL MODEL", 10, true).apply { setTextColor(teal); letterSpacing = .1f }, fullWidth(top = 17))
        panel.addView(label(state.modelSummary, 14).apply { setTextIsSelectable(true) }, fullWidth(top = 8))
        panel.addView(label("Import once. Your model stays on this phone and loads automatically. Use the approved Qwen3.5-4B files from the setup guide.", 13).apply {
            setTextColor(muted); setLineSpacing(dp(3).toFloat(), 1f)
        }, fullWidth(top = 9))
        panel.addView(settingsFileButton(chat.stagedModel?.let(::displayName) ?: "Choose Qwen3.5-4B-Q4_K_M.gguf", R.drawable.ic_document) {
            dialog.dismiss(); pickModel.launch(arrayOf("*/*"))
        }, fullWidth(top = 18))
        panel.addView(settingsFileButton(chat.stagedProjector?.let(::displayName) ?: "Add mmproj-F16.gguf · optional", R.drawable.ic_camera) {
            dialog.dismiss(); pickProjector.launch(arrayOf("*/*"))
        }, fullWidth(top = 9))
        if (chat.stagedProjector != null) panel.addView(textButton("Remove selected vision file") {
            chat.stagedProjector = null; showModelSettings()
        }, fullWidth(top = 3))
        if (chat.stagedModel != null) panel.addView(primaryButton("Import & use model") {
            dialog.dismiss(); chat.configureModel()
        }.apply { isEnabled = !state.busy; alpha = if (state.busy) .45f else 1f }, fullWidth(top = 14))
        else if (state.hasModel && !state.modelReady && !state.busy) panel.addView(primaryButton("Load saved model") {
            dialog.dismiss(); chat.retrySavedModel()
        }, fullWidth(top = 14))
        if (state.busy) panel.addView(label("${latestStatus}\nYou can stop the current action from the chat screen.", 12).apply { setTextColor(muted) }, fullWidth(top = 12))
        panel.addView(View(this).apply { setBackgroundColor(line) }, LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(19); bottomMargin = dp(7) })
        panel.addView(textButton("New conversation") { dialog.dismiss(); chat.newChat() }.apply { isEnabled = !state.busy }, fullWidth())
        panel.addView(label("A new conversation starts without earlier messages or attachments. Your model stays loaded.", 11).apply { setTextColor(muted) }, fullWidth(top = 3))
        panel.addView(textButton("Clear imported files") {
            dialog.dismiss()
            AlertDialog.Builder(this).setTitle("Clear imported files?")
                .setMessage("Delete this app’s imported document and image copies and clear this conversation. Your original files and saved model will be kept.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear copies") { _, _ -> chat.clearImportedFiles() }.show()
        }.apply { isEnabled = !state.busy }, fullWidth(top = 8))
        dialog.setContentView(ScrollView(this).apply { addView(panel) })
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = .24f }; setGravity(Gravity.BOTTOM)
        }
        dialog.show()
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            decorView.setPadding(dp(10), dp(24), dp(10), dp(10))
        }
    }

    private fun settingsFileButton(title: String, icon: Int, click: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = ripple(shape(soft, 13, line)); setPadding(dp(13), dp(14), dp(13), dp(14))
        isClickable = true; isFocusable = true
        addView(ImageView(this@MainActivity).apply {
            setImageResource(icon); imageTintList = ColorStateList.valueOf(teal)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(20), dp(20)))
        addView(label(title, 13, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.MIDDLE },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(11) })
        setOnClickListener { if (!chat.state.value.busy) click() }; alpha = if (chat.state.value.busy) .45f else 1f
    }

    private fun showTextDialog(title: String, value: String) {
        val body = label(value, 14).apply {
            setTextIsSelectable(true); setLineSpacing(dp(4).toFloat(), 1f); setPadding(dp(21), dp(12), dp(21), dp(18))
        }
        AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply { addView(body) }).setPositiveButton("Close", null).show()
    }

    private fun copyAnswer(message: PocketChatMessage) {
        val tables = MarkdownDocument.parse(message.text).filterIsInstance<MarkdownBlock.Table>()
        fun copyFormatted() {
            val html = runCatching { AnswerExports.html(message.text) }.getOrNull()
            copyToClipboard(AnswerExports.plainText(message.text), html)
        }
        if (tables.isEmpty()) { copyFormatted(); return }
        val choices = listOf("Copy formatted answer", "Copy Markdown") + tables.indices.map {
            if (tables.size == 1) "Copy table for spreadsheet" else "Copy table ${it + 1} for spreadsheet"
        }
        AlertDialog.Builder(this).setTitle("Copy output").setItems(choices.toTypedArray()) { _, index ->
            when (index) {
                0 -> copyFormatted()
                1 -> copyToClipboard(message.text)
                else -> copyToClipboard(AnswerExports.tableTsv(tables[index - 2]))
            }
        }.show()
    }

    private fun copyToClipboard(plain: String, html: String? = null) {
        val maximumBytes = 200_000
        if (plain.length > maximumBytes || plain.toByteArray(Charsets.UTF_8).size > maximumBytes) {
            chat.reportError("This answer is too large for the clipboard. Use Download to save the full output.")
            return
        }
        try {
            val includeHtml = html != null && html.length <= maximumBytes &&
                plain.toByteArray(Charsets.UTF_8).size + html.toByteArray(Charsets.UTF_8).size <= maximumBytes
            val clip = if (includeHtml) ClipData.newHtmlText("Pocket Agent answer", plain, html!!)
                else ClipData.newPlainText("Pocket Agent answer", plain)
            clip.description.extras = PersistableBundle().apply {
                putBoolean(if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE
                    else "android.content.extra.IS_SENSITIVE", true)
            }
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
        } catch (_: Exception) {
            chat.reportError("The full answer could not be copied. Use Download to save it instead.")
        }
    }

    private fun chooseExport(message: PocketChatMessage, share: Boolean) {
        val options = AnswerExports.options(message.text, "pocket-agent-answer-${message.id}")
        AlertDialog.Builder(this).setTitle(if (share) "Share answer as" else "Download answer as")
            .setItems(options.map { it.label }.toTypedArray()) { _, index ->
                lifecycleScope.launch {
                    try {
                        val export = chat.stageExport(options[index])
                        if (share) {
                            val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", chat.exportFile(export))
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = export.mimeType
                                putExtra(Intent.EXTRA_STREAM, uri)
                                clipData = ClipData.newUri(contentResolver, "Pocket Agent answer", uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            startActivity(Intent.createChooser(intent, "Share answer"))
                        } else {
                            chat.discardExport(chat.pendingDownload)
                            chat.pendingDownload = export
                            createAnswerDocument.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = export.mimeType
                                putExtra(Intent.EXTRA_TITLE, export.fileName)
                            })
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { chat.reportError(error.message ?: "This answer could not be exported.") }
                }
            }.show()
    }

    private fun chooseDocuments() {
        if (chat.state.value.busy) return
        pickDocuments.launch(arrayOf("application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/msword", "application/vnd.ms-excel", "image/*"))
    }

    private fun launchCamera() {
        if (chat.state.value.busy) return
        try {
            val directory = File(cacheDir, "camera").apply { mkdirs() }
            val file = File.createTempFile("capture-", ".jpg", directory)
            cameraFile = file
            cameraUri = FileProvider.getUriForFile(this, "$packageName.files", file)
            takePicture.launch(cameraUri!!)
        } catch (error: Exception) {
            cameraUri?.let(::revokeCameraGrant)
            cameraFile?.delete()
            cameraFile = null
            cameraUri = null
            chat.reportError("The camera could not be opened: ${error.message ?: "No camera app is available."}")
        }
    }

    private fun revokeCameraGrant(uri: Uri) {
        runCatching { revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
    }

    private fun displayName(uri: Uri): String = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "Selected file"
    } catch (_: Exception) { "Selected file" }

    private fun iconButton(icon: Int, description: String, click: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon); imageTintList = ColorStateList.valueOf(navy); background = ripple(shape(Color.TRANSPARENT, 14))
        setPadding(dp(11), dp(11), dp(11), dp(11)); contentDescription = description; setOnClickListener { click() }
    }

    private fun textButton(title: String, click: () -> Unit) = Button(this).apply {
        text = title; textSize = 12f; isAllCaps = false
        minHeight = dp(42); minimumHeight = dp(42); minWidth = 0; minimumWidth = 0
        setPadding(dp(9), dp(5), dp(9), dp(5)); setTextColor(muted)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); background = ripple(shape(Color.TRANSPARENT, 10)); setOnClickListener { click() }
    }

    private fun primaryButton(title: String, click: () -> Unit) = Button(this).apply {
        text = title; textSize = 14f; isAllCaps = false; minHeight = dp(50); minimumHeight = dp(50); setTextColor(Color.WHITE)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); background = ripple(shape(teal, 14), Color.WHITE); setOnClickListener { click() }
    }

    private fun label(value: String, size: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(navy)
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
    }

    private fun shape(fill: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radius).toFloat(); stroke?.let { setStroke(dp(1), it) }
    }

    private fun ripple(content: android.graphics.drawable.Drawable, tint: Int = teal) = RippleDrawable(
        ColorStateList.valueOf((tint and 0x00FFFFFF) or 0x18000000), content, shape(Color.WHITE, 18))

    private fun fullWidth(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top); bottomMargin = dp(bottom) }
    private fun isNearBottom(): Boolean = !::chatScroll.isInitialized || chatScroll.scrollY >= chatColumn.height - chatScroll.height - dp(120)
    private fun scrollToBottom() = chatScroll.post { chatScroll.scrollTo(0, chatColumn.bottom) }
    private fun animateArrival(view: View) {
        view.alpha = 0f; view.translationY = dp(9).toFloat()
        view.animate().alpha(1f).translationY(0f).setDuration(180).setInterpolator(DecelerateInterpolator()).start()
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + .5f).toInt()
    private fun color(value: String): Int = Color.parseColor(value)

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("cameraUri", cameraUri?.toString()); outState.putString("draft", questionInput.text.toString())
        outState.putString("cameraFile", cameraFile?.name)
        chat.pendingDownload?.let {
            outState.putString("exportCacheName", it.cacheName)
            outState.putString("exportFileName", it.fileName)
            outState.putString("exportMime", it.mimeType)
        }
    }
    override fun onDestroy() {
        modelDialog?.dismiss(); super.onDestroy()
        // The retained ViewModel closes the model only when the conversation is actually finished.
    }
}
