# Browser workbench

The main Activity includes an owned WebView with HTTPS and explicit loopback
navigation, back/reload and real bounded DOM observation. File/content URIs,
credential URLs, remote cleartext, mixed content and invalid TLS are rejected.
The page has JavaScript for ordinary web behavior, DOM storage and no native
JavaScript bridge. Generated HTML attachments use a separate disabled-JavaScript,
disabled-network preview origin.

DOM content is untrusted source data. Browser automation tools are not registered
with the model in this delivery: form actions, assertions, screenshots and device
browser control require the next bridge gate and actual device validation. The
UI does not claim desktop Chrome, Playwright, external browser or ADB access.
BrowserSecurityTest exercises allowed and rejected URL classes.
