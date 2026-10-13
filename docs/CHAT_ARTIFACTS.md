# Real chat artifacts

The agent creates immutable UTF-8 text artifacts with `ArtifactCreate`, checks
JSON/Python/shell syntax, bundles explicit regular files with `ArtifactBundle`,
checks ZIP CRCs and records byte size and SHA-256. Each artifact has its own UUID
directory and manifest under the private project's `.term-agent/artifacts`.
`ArtifactInspect` rechecks bytes; `ArtifactPublish` persists a chat publication.
New artifact IDs represent new versions. Unsupported binary formats fail.

Arabic PDF uses the live authenticated Android bridge. Android StaticLayout and
platform fonts shape the text into real PdfDocument pages; PdfRenderer opens
and renders the result. Python independently checks the PDF header and actual
file before publishing its manifest. Without the bridge, PDF creation reports
an unavailable capability and never publishes a pretend PDF.

Android verifies the manifest, path containment, bytes, hash and format before
importing into its own persistent artifact store. The owner's conversation ID
is attached by the host and never chosen by a model argument. Chat cards expose
working preview, open, save and share actions. The unexported read-only provider
issues `content://` URIs with temporary grants. Save uses the system document
picker and verifies the exported hash. It does not need broad storage access.

HTML preview disables JavaScript, network loads, file/content access and native
bridges. ZIP preview lists members; PDF opens through an Android viewer. Source
and export files remain separate. The current import window processes the 300
newest manifests; already imported artifacts remain persistent.

Desktop tests verify text formats, real ZIPs, persistence, invalid syntax,
traversal and tampering. Eight-format chat/PDF export acceptance still requires
an ARM64 Android device and PDF viewer; see V2_VALIDATION.md.
