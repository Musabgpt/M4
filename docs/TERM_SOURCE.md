# Arabic Terminal — Alpine Linux on Android

تطبيق مستقل لسطح أوامر Linux على Android؛ يتضمن Alpine Linux ARM64 الحقيقي مع apk package manager وبيئة ملفات Linux عبر PRoot. يعمل بدون Root أو Termux آخر؛ **ليس kernel Linux منفصلًا** ويشارك نواة Android. بناء APK يجلب Alpine minirootfs الموقّع/المتحقق بصمته من الموقع الرسمي وحزم PRoot من مستودع Termux ويضمنها داخل ملف التطبيق؛ لا يلزم الإنترنت لبدء Linux بعد التثبيت. تحتاج اتصال إنترنت لتثبيت برامج من apk.

## الميزات

- Alpine rootfs حقيقي: /etc /bin /usr وapk add وBusyBox والأدوات المتاحة، بعد فك الملفات مرة واحدة.
- Android PTY مع دعم ANSI وتقسيم الجلسات وأوامر عربية: «افتح واتساب» «الرئيسية» «ارجع» «اعرض التطبيقات» وغيرها.
- خيار Root Android عندما يتوفر su ويمنح المستخدم الإذن. الجذر الوهمي داخل PRoot ليس صلاحيات جذر Android.
- واجهة طرفية مبسطة وتثبيت موضع الإدخال فوق لوحة مفاتيح الهاتف.
- ARM64 فقط في الإصدار الحالي، محاكاة PRoot لا تدعم systemd كجهاز لينكس مستقل، وبعض أوامر النواة قد لا تعمل.

## البناء

GitHub Actions → بناء الطرفية العربية APK → Artifact الخاص بالبناء الناجح. ملفات Linux تُدرج خلال بناء GitHub. تتطلب Java 17 وAndroid SDK 36 وNDK 27 وGradle 8.13. التجربة على جهاز Samsung فعلي مطلوبة للتحقق من سياسة ptrace وSELinux ومن أداء PRoot.

## الاعتمادات والتراخيص

الواجهة الخاصة بنا Apache-2.0. PRoot وتبعياته لها تراخيص GPL/LGPL خاصة بها، ويجب الحفاظ على الإشعارات وتوفير المصادر عند إعادة توزيعها: https://github.com/termux/termux-packages/tree/master/packages/proot ، https://github.com/proot-me/proot ، https://github.com/termux/libandroid-shmem . Alpine: https://alpinelinux.org/about/ . Apache Commons Compress: Apache-2.0.

لا يمكن للتطبيق تجاوز قيود Android أو الحصول على Root بمجرد قبول إذن إمكانية الوصول. التحكم المتقدم يخضع للصلاحيات الفعلية في النظام.

## تشخيص الشبكة

نفذ `فحص الشبكة` لاختبار HTTPS من Android، ثم `فحص لينكس` لاختبار DNS و`curl` وإمكانية الكتابة و`apk update` داخل Alpine نفسها. ظهور HTTP 200 في اختبار Android لا يثبت نجاح Linux/PRoot networking. لا تعطل فحص TLS ولا تستخدم `apk --allow-untrusted` لتجاوز أخطاء الاتصال.

اختبار أدوات Alpine المدمجة على سطر واحد: `python3 --version; git --version; node --version; npm --version`. استخدام مسافات بين الأوامر ليس مكافئًا للفواصل المنقوطة.

## تجربة ترمينال كمبيوتر أقرب للينكس الكامل

الإصدار 0.7 يتضمن Bash كصدفة افتراضية، وأدوات Linux إضافية مثل coreutils وnano وtmux وhtop وfindutils وripgrep وjq وopenssh-client وzip/unzip، بجانب Python/Git/Node/npm. هذه الأدوات مضمّنة من وقت البناء ولا تحتاج apk update عند أول تشغيل؛ وبعض برامج TUI قد تحتاج مزيداً من توافق شاشة ANSI على الهاتف.

لحماية بيانات النسخة السابقة، الإصدار 0.7 يستخدم معرّف تطبيق منفصلاً بحيث يثبت بجانب نسخة 0.6؛ ملفات النسخة القديمة لا تنتقل تلقائياً بسبب عزل Android.


## 0.8 — ثابت متماسك مع أرشيف Alpine
النسخ السابقة تعثرت عند zipinfo -> unzip بسبب إنشاء Hard Link على Android. الإصلاح مزدوج: GNU tar --hard-dereference يولد ملفات مستقلة ويتحقق من صفر روابط صلبة في الأرشيف، ومثبت Java يستخدم SafeRootfsExtractor بفحص المسارات، وتحويل روابط Hard Link إلى نسخ ملفات بدلاً من Files.createLink. اختبارات Android JVM تفك أرشيف Alpine الكامل وتتحقق من Bash/Python/Git/Node/npm قبل بناء APK.
معرّف التطبيق عاد إلى io.musab.arabicterminal بناءً على موافقة المستخدم على حذف النسخ القديمة. مفتاح Debug على GitHub قد يختلف في عمليات البناء اللاحقة، ولذلك لا ينبغي الاعتماد عليه للتحديث دون مفتاح توقيع ثابت وآمن.

## v0.8.2 — Android PRoot apk compatibility experiment

Ship the official Alpine 3.22 aarch64 apk-tools-static v2 as an additional executable at /usr/local/bin/apk-v2. APK v3 (/sbin/apk) remains unchanged. Signed indexes, package verification and HTTPS are not disabled. Compare apk-v2 --version; apk-v2 update with apk update on Android. This is an experimental workaround until validated on a real handset. APK builds are debug-signed; back up app-private files before any uninstall.

## v0.9.0 Package manager

New Alpine Package Manager menu for search, package details, installed packages, update repository indexes, install and remove. Supports Arabic commands for each task. Uses verified `apk-v2` without replacing Alpine's native `apk` (v3). Before adding or removing, runs `--simulate` and backs up `/lib/apk/db` plus `/etc/apk/world` under `/root/.arabicterminal-apk-backups`. Sensitive base packages are protected from UI removal. Full-system upgrades remain disabled because they have not been tested on Android. CI includes standalone command-injection regression tests and the existing Android/Linux checks. APK debug signing may change between builds; back up files before uninstalling an existing APK.

## v0.10.0 — Safer recovery and better interactive terminal display

- **Restore backups**: From the Android menu select **استعادة ZIP إلى /root** and pick a ZIP from the Android Storage Access Framework. The archive is scanned as a stream, constrained to at most 20,000 entries, a 1 GiB individual-file limit and 2 GiB total uncompressed size. Absolute paths, traversal paths, backslashes, duplicate paths and invalid ZIPs are rejected. Extraction is first staged in a private temporary directory under the real Alpine rootfs. Only complete backups are published to `/root/Recovered-YYYYMMDD-HHMMSS-N`. Existing projects are never overwritten. This is intentionally a **manual projects restore**, not replacement of the whole Alpine system, SSH permissions, ownership or symlinks.
- **Cursor in terminal**: Render a visible block cursor at the VT screen cursor position. Supports DEC cursor visibility (`CSI ?25h/l`) and reverse-video SGR 7/27 used by terminal UI programs. Correct `CSI 2J` behavior retains cursor position. Existing alternate-screen handling and colors are preserved.
- **Regression coverage**: Secure restoration and new VT behavior are exercised by the standalone Java tests; the Android app and bundled native PTY are still built in CI.
- **Signing limitation**: CI generates a fresh Android debug signature; it cannot be assumed to update a differently signed installation in place. **Do not uninstall an existing APK with valuable files** until an export outside app storage has been verified. Export ZIPs may include private tokens and SSH keys and are **not encrypted**.
- **Handset verification still required**: In particular check Android document-provider export/restore, interactive nano/vim/tmux cursor positioning, and PRoot background sessions. Passing CI alone is not proof of phone success.


## v0.11.0 — Term Agent AI coding runtime

An offline-bundled, standard-library Python coding agent runtime is added to the app's Alpine Linux. The app menu **🤖 وكيل البرمجة** opens diagnostics, tool list, task status and setup help. The agent supports OpenAI-compatible tool-calling API endpoints, project-scoped file actions, an opt-in bounded command runner, SQLite checkpoint/event history, per-file backups/rollback, iterative repair loops and independent verification commands. See [agent/README.md](agent/README.md). Optional Pi, Ralph, GitHub CLI and pytest installs require network and may need Alpine/Android compatibility checks. The APK does **not** bundle a language model or API access; real Android handset validation is still required.


## v0.11.1 — Coding tool bundle

Build packages pytest plus best-effort GitHub CLI, Tree-sitter CLI and ast-grep from signed Alpine ARM64 repositories. Adds bounded watchdog resume utility and removes API credential environment variables from child processes.
