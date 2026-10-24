# Pocket Agent

A private, offline Android chat assistant for Word documents, Excel workbooks, PDFs, and camera images. Built for an ARM64 Android phone such as the 16 GB Galaxy S26 Ultra.

## Chat experience

- One white chat screen, a fixed composer, camera/file attachments, and a custom adaptive launcher icon.
- Answers stream into the conversation. Stop cancels generation.
- Model configuration lives in Settings. Import the model once; saved models load automatically when the app opens.
- The loaded model and conversation survive screen rotation. New conversation clears the current chat context.
- Attachment chips open extracted text previews. Sources and actions are available from the answer.

Ordinary chat and document questions use one model pass with preselected evidence. Simple explicit spreadsheet range sums use decimal arithmetic without calling the model. Other calculation requests retain a bounded agent tool loop. Batched prompt processing replaces the previous one-token-at-a-time vision configuration. These changes reduce avoidable work; latency and sustained heat still need measurement on the phone.

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
4. Repeat in airplane mode. Record time to first visible text and total answer time for a greeting, a document question, and an image question.
5. Confirm invalid model files and malformed/oversized documents produce a bounded error.
