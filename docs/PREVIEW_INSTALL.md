# Development APK installation

CI creates two debug-signed APKs. Fresh runners generate independent debug signing keys: an APK with a different certificate cannot update the installed package in place. Do not uninstall the existing Lumena just to try this change: that can destroy its local settings/chat.

Recommended test artifact: **Lumena-0.8.1-preview.apk**, package **com.lumena.android.preview**, launcher name **Lumena Preview**. It installs alongside the original application. The old application's private history and settings are untouched and are NOT automatically copied into Preview. Paste the existing bridge token into Preview once and select the model. Both clients use the same Termux workspace, so avoid concurrent tasks in the two clients.

For Local chat/tools, Accessibility is not needed. Enable it only when testing the optional official-ChatGPT Companion feature. The history drawer remains separate work.

The standard debug APK is for fresh installs or a locally signed build with the same existing developer signing key. Production updates require a stable private signing key outside the public repository.
