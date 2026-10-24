# Pocket Agent security review — 0.2

Scope: Android source, file-import paths, local agent execution, model loading, manifest, and GitHub build/signing workflow. The review includes regression tests and packaged-manifest checks. A physical-device penetration test and a complete audit of third-party native libraries have not been performed.

## Corrections

| Boundary | Finding | Correction |
| --- | --- | --- |
| Office XML | Byte preflight and parser could interpret encodings differently | Strict UTF-8/16 decoding; the checked canonical text is the parser input; DTD/entities and other encodings are rejected |
| ZIP processing | Metadata-only limits did not bound repeated actual inflation or native directory indexing | Bounded central-directory preflight, actual byte budgets, entry and structure limits; ZIP64/multipart rejected |
| Spreadsheet inputs | Long/repeated sheet and cell references could amplify metadata; pathological formats could waste CPU | Validated bounded sheet/cell references, duplicate rejection, linear format scanning |
| Images | Missing original-dimension/allocation checks could permit oversized decodes | Supported-signature checks, pixel/dimension bounds, ceiling-based sampling, decoded-allocation checks, and cleanup |
| PDF native parsing | Renderer previously ran with the main app's private-data access | Private isolated-process service receives only read-only descriptors; bounded text/raw pixels cross IPC; dimensions and exact byte counts are validated; timeouts/watchdogs close or kill stalled work |
| GGUF native loading | Four-byte magic was insufficient to establish model origin | Exact approved SHA-256 allowlist before native loading; canonical private paths and same-folder pairing; files become read-only; cached verification is bound to role/path/size/mtime |
| File copies | Provider size hints and stalled streams could be misleading | Actual byte caps, free-space checks, SHA during copying, cancellation/timeout cleanup, no overwrite of existing files |
| File grants | Persisted document grants outlived the import | Temporary grants only; camera grants revoked and temporary capture cleaned after import |
| Agent output | Unobserved source references and spreadsheet errors could be accepted | Source-ID validation, visible tool failures, decimal totals, refusal of missing caches/error-like values, bounded allowlisted read-only tools |
| Build/update | Initial APK was debuggable and had an ephemeral signing key | Preview/release debugging disabled; persistent release signing supported using an encrypted key and a GitHub secret; parallel preview protects the old installation |
| Supply chain | Mutable action tags could select different code over time | Action commit pins, fixed AAR version and checksum, limited workflow permissions and no signing secrets in PR builds |

These findings establish concrete resource and trust-boundary gaps. No demonstrated XXE exfiltration or verified current GenieX memory-corruption vulnerability was established during this review.

## Boundaries kept narrow

- The APK has no Internet or broad-storage permission. CI inspects the merged manifest, including library contributions.
- Only the launcher activity is intentionally exported. The FileProvider and isolated PDF service are private; camera sharing exposes only a dedicated cache directory.
- Documents and model metadata never become shell commands or executable code. Agent actions cannot execute scripts, browse the network, or modify original documents.
- Untrusted names are display labels, not filesystem paths. Imported files receive private generated directories.
- Native model loading accepts the exact reviewed artifact pair. Verification proofs live in private preferences and are not imported from model sidecars or documents.
- Plaintext signing keys/passwords stay in temporary runner files. The repository stores only encrypted key material. Existing keys are never regenerated on password failure.

## Remaining risks and device checks

Android framework image decoding, EXIF handling, ML Kit, GenieX, and their native dependencies remain attack surfaces. Approved model hashes establish artifact identity, not correctness of every native operation. Keep Android and dependencies updated and review new model versions before allowing them. Root access or a compromised app UID can bypass private-storage verification assumptions.

PDF isolation limits a compromised renderer's access to app data; its binding, cancellation, watchdog, and raw-pixel protocol still need smoke testing on the S26 Ultra. Source/CI validation cannot prove absence of device-specific failures.

Prompt injection can influence an answer despite instructions and source checks. It is contained by the absence of shell/network/write tools; factual accuracy and complete evidence use are not guaranteed. Streaming text is provisional until the final source check completes.

Offline operation reduces direct network exposure but does not remove operating-system, dependency, physical-access, or social-engineering risks. This release does not promise an unhackable device.

The original 0.1 signing key is unavailable. Android cannot accept a differently signed in-place update. Preview installs alongside the old app, and the first signed-release migration needs a fresh installation; originals/downloads should be retained. Future signed-release updates preserve app-private data when the same key and package are used.
