# Pocket Agent

An Android app for asking questions about Word documents, Excel workbooks, PDFs, and camera images using a model stored on your phone. Built for an ARM64 Android phone such as the 16 GB Galaxy S26 Ultra.

The app has no Internet permission. GitHub builds the APK online; importing files, OCR, retrieval, calculations, and model inference happen on the phone. Model files and your documents are never included in the repository or build artifacts.

## Build and install from your phone

1. Open this repository in your phone's browser and select **Actions → Build Android APK**.
2. Open the latest successful run. Under **Artifacts**, download **pocket-agent-debug** while signed in to GitHub.
3. Extract the ZIP in your phone's file manager, then open `app-debug.apk`. Allow installation from that browser or file manager if Android asks.
4. Open **Pocket Agent**. Add a document or take a picture. Tap **View extracted text** to check the result. Document extraction works before you load an LLM.
5. Download a compatible GGUF model using your browser, then choose it under **Your local model**. For visual questions, also select the matching vision projector and tap **Load model**.
6. Enable airplane mode and try the examples below.

Each change on `main` starts a build. To rebuild manually, open the workflow and select **Run workflow**. GitHub's mobile browser view may hide some controls; use **Desktop site** if needed. No computer, Android Studio, API key, or paid inference service is required for this workflow. GitHub Actions availability and limits depend on your GitHub account.

This is a debug development build. A newly generated GitHub runner uses a new debug signing key, so a later APK may require uninstalling the previous build first. Uninstalling deletes the app's imported files and settings; keep originals in your phone's document folder. A persistent signing key is a later distribution step.

## First model to try

For the S26 Ultra with 16 GB RAM, a 4-bit 4B model is a reasonable benchmark candidate; available RAM, context, and heat still determine practical performance. The first implementation deliberately uses CPU inference as a working baseline. It does **not** claim NPU acceleration.

One candidate is [Unsloth's Qwen3.5-4B GGUF conversion](https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/tree/main):

- `Qwen3.5-4B-Q4_K_M.gguf` — approximately 2.74 GB, for language inference.
- `mmproj-F16.gguf` from the **same model repository** — approximately 672 MB, for image input.

Download the individual files rather than the whole repository. Keep enough free storage for both the downloaded files and the app's imported copies. Start with the model alone, then load it again with its matching vision file. An incompatible projector or unsupported model should produce a load error rather than a cloud fallback. This candidate has not yet been tested on the user's physical S26 Ultra.

Smaller compatible 2B GGUF models are an option if loading or responses are too slow. A projector must match the exact model checkpoint. A text-only model cannot gain vision by attaching an unrelated projector. Qualcomm QAIRT NPU bundles are a different format and are not imported by this initial GGUF interface.

The runtime is pinned to the official [Qualcomm GenieX v0.9.3 Android AAR](https://github.com/qualcomm/GenieX/releases/tag/v0.9.3). GitHub Actions downloads it and verifies its SHA-256 before building. No native library compilation or model download is required on your phone.

## Supported files

| File | Initial behavior | Source reference |
| --- | --- | --- |
| Word `.docx` | Reads body text, tables, headers, and footers | Paragraph or table row |
| Excel `.xlsx` | Reads cells, sheets, stored formulas, and cached results | Worksheet and cell |
| PDF | Reads native text on Android 15+, OCRs pages without text | Page number |
| Camera / image | Bundled OCR for printed Latin-script text; optional local VLM for visual questions | Image name |

Older `.doc`, `.xls`, and `.xlsb` files need to be saved as `.docx` or `.xlsx` before import. Password-protected files must be unlocked first. This version reads and analyzes imported copies; it does not edit Office documents or reproduce their page layout.

Excel formulas are **not recalculated**. Cached results can be stale or absent. The spreadsheet sum tool uses decimal arithmetic over stored numeric values, excludes text and date/time cells, and reports when it uses cached formula results. It refuses a range containing a formula without a numeric cached result. Excel display formats, charts, macros, and embedded objects are not interpreted. General PDF diagram understanding is not wired into the VLM path yet; export or photograph a page and import the image for a visual question.

Default import limits are 50 MB per document, 40 PDF pages, 10 worksheets, 10,000 cells, and 120,000 extracted text characters per file. The app reports truncation. Retrieval selects relevant excerpts for the model; a single answer does not inspect every part of a long library. Photos and scanned pages are resized before OCR to bound memory use. Recognition errors and model mistakes remain possible; verify important numbers against the original sources.

## Try these tasks

- “Summarize the main points in this Word document.”
- “Find the cancellation terms in the PDF and cite the page.”
- “Sum the stored numeric values in Sheet1 B2:B20.”
- “Read the text in the photo.”
- With a compatible vision model loaded: “Describe the most recently imported image.”

The agent can select local search, read, and spreadsheet sum tools in a bounded loop. The app validates tool names and arguments, returns results to the model, and displays source references and actions. Documents are treated as data, including any instructions written inside them. There is no arbitrary shell, network, or phone-control tool.

## Development

The project uses Kotlin, standard Android views, the Android document picker and camera capture intent, bundled ML Kit OCR, and GenieX's local GGUF runtime. Android 12+ and ARM64 are required by this build configuration.

The GitHub workflow runs generated-fixture Office parser tests, agent tests, Android lint, and a debug APK build. It also checks that the packaged app does not request Internet permission. A successful CI build establishes compilation and those checks; it does not establish phone-specific model accuracy, latency, or battery use.

To build outside GitHub, use JDK 17 and Gradle 8.11.1, install Android SDK 35, download the pinned AAR to `app/libs/geniex-android-v0.9.3.aar`, and run:

```sh
gradle testDebugUnitTest lintDebug assembleDebug
```

## Device acceptance check

1. Import a small `.docx`, an `.xlsx` with known values and formulas, a text PDF, a scanned PDF, and a camera photo.
2. Check the extracted sources and warnings. Test a known spreadsheet total.
3. Load the model and, separately, its matching vision projector. Ask a question about each file.
4. Repeat after enabling airplane mode. Check that no account, remote endpoint, or download is needed.
5. Record model load time, total answer time, peak memory if available, and behavior after several minutes of use.

Future work includes NPU/GPU benchmarking, stable signed releases, better table and date formatting, and selective PDF page image analysis.
