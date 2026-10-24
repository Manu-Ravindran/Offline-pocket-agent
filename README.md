# Pocket Agent

A private, offline Android chat assistant for Word documents, Excel workbooks, PDFs, and camera images. Built for an ARM64 Android phone such as the 16 GB Galaxy S26 Ultra.

## Chat experience

- One white chat screen, a fixed composer, camera/file attachments, and a custom adaptive launcher icon.
- Answers stream into the conversation. Stop cancels generation.
- Model configuration lives in Settings. Import the model once; saved models load automatically when the app opens.
- The loaded model and conversation survive screen rotation. New conversation clears messages, attachments, and native model context while retaining the loaded weights. Stored imports from other chats are never a request's default scope.
- Attachment chips open extracted text previews. Sources and actions are available from the answer.
- Completed answers render headings, lists, code, and horizontally scrollable tables. Every answer has **Copy**, **Download**, and **Share** controls.

## Full-document summaries and output

Ask **“Summarize this entire document in detail”** to process every available extracted section rather than retrieve five excerpts. Full-summary processing supports PDFs up to **20 pages per document**, with bounded reading batches, hierarchical combination of all batch notes, and a larger final response budget. A 21-page PDF needs a smaller page selection for a full summary; targeted questions remain available. Word pagination is not reliable from OOXML text, so Word coverage is reported by extracted sections.

The app reports page/section coverage and known extraction limits. Empty OCR, truncated extraction, unreadable pages, or unsupported embedded content prevent a claim that the original document was fully understood. Existing file/text limits below still apply. Reading every source batch does not guarantee perfect factual retention by the model.

Ask **“Give the full summary in a table”** or **“Put it in tabular format”** to request a Markdown table that the app renders as native rows and columns. Document-backed table follow-ups use the full-document reading path. Full summaries use several model calls and show batch progress; they take longer than short questions, especially on CPU. Stop cancels the operation.

Use **Copy** for formatted text, Markdown, or spreadsheet-friendly table text. **Download** opens Android's document picker so you can save to Downloads or another chosen location; formats include Markdown, plain text, a formatted static HTML document, and table CSV. **Share** grants another chosen app read access to a generated export copy. HTML is escaped and contains no scripts; formula-like CSV/TSV values are protected for spreadsheet use. Very large or ragged tables fall back to complete raw text instead of creating an oversized grid or discarding content.

Ordinary chat and targeted document questions use one model pass with preselected evidence; full summaries use the multi-pass path above. Simple explicit spreadsheet range sums use decimal arithmetic without calling the model. Other calculation requests retain a bounded agent tool loop. Batched prompt processing reduces avoidable work; latency and sustained heat still need measurement on the phone.

## Download and install from your phone

Open **Actions → Build Android APK**, filter to branch **main**, and select its latest successful push/manual run. Download official APKs only from this repository's main-branch runs. Under **Artifacts**:

- **pocket-agent-preview** is installable alongside the original 0.1 app. Extract the ZIP and install `app-debug.apk`. It is built without debugging access, but uses a temporary development signing key.
- **pocket-agent-release** is the installable, persistently signed release when signing is configured below.
- **pocket-agent-release-unsigned** is a build result for signing setup; Android cannot install it directly.

Preview has a separate package and private storage. It does not delete or inherit the original app's imported files. Import your existing downloaded GGUF files into the new app once; later launches reuse their private copies automatically. Temporary-key previews may need a fresh installation after another preview build, so use the signed release for ongoing use.

The original 0.1 APK was signed with an ephemeral runner's debug key. That private key was not preserved and cannot be recovered from the APK. The first signed release cannot replace it in place: keep original documents/model downloads, test Preview alongside it, and perform a one-time migration when ready. Subsequent signed-release updates reuse the same key and retain imported models and documents.

## Enable secure release updates once

1. Open [New repository secret](https://github.com/Manu-Ravindran/Offline-pocket-agent/settings/secrets/actions/new).
2. Set **Name** to `ANDROID_SIGNING_PASSWORD` and **Secret** to a unique, randomly generated password of at least 32 characters. Keep it in your password manager. Do not place it in source files, workflow inputs, issues, or chat.
3. Open **Actions → Build Android APK → Run workflow**, choosing `main`.
4. Download **pocket-agent-release** from the successful run.

The trusted main-branch workflow generates a persistent signing key, encrypts it with GnuPG AES-256 and iterated password derivation, and saves only the encrypted file at `.github/signing/release-key.p12.gpg`. The password remains in GitHub Actions secrets. Build jobs decrypt into a private temporary directory and remove plaintext signing material afterward. PR builds never receive the signing secret. An existing key is never silently regenerated when the password is wrong.

Keep the encrypted key file and signing password for future updates. Changing or losing the key prevents Android from accepting updates to an existing installation. The GitHub connector cannot configure repository secrets, so the repository owner performs the one-time secret step.

## First model to try

This security baseline accepts the following exact [Unsloth Qwen3.5-4B files](https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/tree/main):

| File | Download | SHA-256 |
| --- | --- | --- |
| Language model, about 2.74 GB | [Qwen3.5-4B-Q4_K_M.gguf](https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/resolve/main/Qwen3.5-4B-Q4_K_M.gguf?download=true) | `00fe7986ff5f6b463e62455821146049db6f9313603938a70800d1fb69ef11a4` |
| Matching vision file, about 672 MB | [mmproj-F16.gguf](https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/resolve/main/mmproj-F16.gguf?download=true) | `cd88edcf8d031894960bb0c9c5b9b7e1fea6ebee02b9f7ce925a00d12891f864` |

In Settings choose the model, optionally choose its matching vision file, and import. The app verifies SHA-256 before opening files with the native inference runtime. Custom GGUF files are rejected in this baseline. Existing saved models from an earlier installation are verified once when first loaded by the new code; private verification records avoid rereading gigabytes on every launch. Keep roughly 8 GB free for downloads and imported copies.

Settings shows separate live states for the language model, the saved vision file, and image analysis. A selected filename alone does not mean that its model is loaded. If the language model is already saved, choose `mmproj-F16.gguf` and tap **Use saved model + selected vision file**; this imports only the vision file and reuses the saved language model. A saved model can be retried without selecting or copying it again. The settings sheet has a fixed visible header and a bounded scrollable body so its controls remain accessible on smaller screens and while the model is loading.

## Collect diagnostic logs

After reproducing an error, open **Settings → Diagnostics → Collect logs**. You can inspect the report, copy it, download a UTF-8 text file, or share it with an app you choose. Collection remains available during model loading, and **Clear logs** removes the private diagnostic history.

The report contains app/Android/device compatibility information, model import/readiness flags, recent technical stages, sanitized exception types and stack frames, and available process-exit reason codes. Logs are bounded and private. Prompts, replies, document text, images, filenames, file paths, URIs, and raw exception messages are excluded. No device-wide log-reading permission or automatic upload is added. Errors that occurred before this logging feature was installed cannot be reconstructed from a new log; reproduce them once in the new version.

For slow responses, the report separates waiting/preparation, time to the first reply, and total request time from individual model-generation passes. It records output character counts and average speed in characters per second, plus actual generated-token counts and rates when the runtime supplies them; streamed chunks are not counted as tokens. Document processing records format, extraction coverage, and duration without storing document contents. Memory, storage, battery, and thermal readings are current snapshots when the report is collected.

The GGUF is one Qwen backbone for conversation and document work. Its matching vision file enables image input on that same backbone; a second language-model download is not required. Generic questions such as **“Analyze this”**, **“Summarize this”**, or **“Make a table from this”** use the attached image when it is the active context. The app queries the runtime's actual vision capability instead of assuming that a created wrapper means the projector loaded. A failed projector produces a visible warning while text conversation remains available.

The runtime is pinned to [Qualcomm GenieX v0.9.3](https://github.com/qualcomm/GenieX/releases/tag/v0.9.3), and CI checks the AAR's SHA-256. CPU execution remains explicit; NPU/GPU acceleration is a later device benchmark rather than a claimed feature.

## Supported documents

| Input | Processing and sources |
| --- | --- |
| `.docx` | Body paragraphs, tables, headers, footers; paragraph/row references |
| `.xlsx` | Cell data, stored formulas, cached results; sheet/cell references |
| PDF | Text extraction or OCR, with page numbers; native parsing runs in an isolated process |
| Camera / image | Bundled Latin-script OCR; local VLM for general visual questions when the matching vision file is loaded |

Older `.doc`, `.xls`, and `.xlsb` files need conversion to `.docx` or `.xlsx`. Password-protected documents must be unlocked first. Office imports read text/data and preserve the original file. They do not reproduce page layout, execute macros, follow external links, or edit the original. ZIP64/multipart Office archives and non-UTF-8/16 XML parts are outside this initial supported subset.

Excel formulas are not recalculated. Totals can use genuine numeric cached formula results with an explicit warning; missing caches and error-like cells prevent an exact total. Text and date/time cells are excluded. PDF charts/diagrams are not automatically passed to the VLM; export or photograph the relevant page and attach its image.

Extraction is bounded: 50 MB per document, 40 PDF pages, 10 worksheets, 10,000 cells, and 120,000 text characters per file by default. The app reports truncation. Retrieval selects relevant excerpts; it does not promise whole-library analysis in every answer. Models can still make mistakes, and OCR can misread names and numbers. Verify consequential figures against source documents.

## Security and verification

The app requests no platform permissions for networking or broad storage access. File selection grants are temporary, camera sharing is scoped to a cache folder, and imported copies remain in private app storage. App backups and release debugging are disabled. Model-selected tools are local and read-only. There is no shell, WebView, cloud endpoint, or model-download function.

See [Security review](docs/SECURITY_REVIEW.md) for fixes, tested boundaries, and remaining risks. Security review reduces risk; it cannot establish that third-party native code or Android is free of vulnerabilities.

CI runs Kotlin tests, CI security-control tests, lint for both build variants, APK builds, and merged-manifest checks for unexpected permissions/exported components, backup settings, debugging, and PDF isolation. GitHub Actions are pinned to commit hashes. Successful CI establishes those checks; phone-specific model performance, isolated-service behavior, and full workflow usability still need device testing.

## Development

Kotlin and Android views; Android 12+ ARM64; Android SDK 35; JDK 17; Gradle 8.11.1. The project uses bundled ML Kit text recognition and the pinned local GenieX AAR. Models and personal documents never enter the repository or APK build.

GitHub Actions downloads the verified AAR automatically. For local builds place it at `app/libs/geniex-android-v0.9.3.aar`, then run:

```sh
python3 -m unittest discover -s ci -p 'test_*.py'
gradle --continue testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease
```

## Phone acceptance check

1. Install Preview alongside the original app; import the known model and matching vision file once.
2. Close and reopen it. Check automatic model loading, chat history during rotation, streaming, and Stop.
3. Test a Word file, a known Excel range total, a text PDF, a scanned PDF, and a camera image. Inspect their sources and extracted text.
4. Start a new conversation and attach a different file. Check that the old attachment and its facts are absent, while the model stays loaded.
5. Summarize a 20-page test PDF whose final page contains a distinctive fact; inspect coverage and check that the fact survives. Ask for a table, then Copy and Download it.
6. Attach an image with little printed text and ask **“What is this?”** and **“Analyze this image in a table.”** Check the actual visual answer and any projector warning.
7. Repeat in airplane mode. Record time to first visible text and total answer time for a greeting, a targeted document question, a full summary, and an image question.
8. Confirm invalid model files and malformed/oversized documents produce a bounded error.
