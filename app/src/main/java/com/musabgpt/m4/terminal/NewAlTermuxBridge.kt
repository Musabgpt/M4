package com.musabgpt.m4.terminal

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import com.musabgpt.m4.runtime.CapabilityBroker

/**
 * Reuses the authenticated NewAl Code agent already running in Termux.
 * Unlike NewAl-Cloud's WebBridge, never imports the daemon's pairing key into
 * Android: all authentication stays within Termux's own Linux account.
 * NewAl's /api/sessions contract, tool loop and GitHub integrations are reused.
 *
 * No fallback to Alpine/Arabic terminal or a mock model occurs on failure.
 */
object NewAlTermuxBridge {
    private val worker = """
import base64,json,os,sys,time,urllib.request,urllib.error,subprocess
owner,payload,mode=sys.argv[1:4]
phase=sys.argv[4] if len(sys.argv)>4 else 'start'
decision_id=sys.argv[5] if len(sys.argv)>5 else ''
baseline=int(sys.argv[6]) if len(sys.argv)>6 else 0
allow=(sys.argv[7]=='allow') if len(sys.argv)>7 else False
if phase not in ('start','resume','setup'): raise ValueError('Unknown transport phase')
if not owner.replace('-','').isalnum() or len(owner)>80: raise ValueError('Invalid conversation id')
message=base64.b64decode(payload).decode('utf-8')
if len(message)>24000: raise ValueError('Message too long')
home=os.path.expanduser('~')
# M4 owns the Termux integration. A pairing key must NEVER be a manual
# prerequisite: keep it inside Termux and provision it on first real use.
state_dir=os.path.join(home,'.newal-code','termux')
os.makedirs(state_dir,mode=0o700,exist_ok=True)
os.chmod(state_dir,0o700)
keyfile=os.path.join(state_dir,'key')
keys=[os.path.join(home,'.newal-cloud','key'),keyfile,os.path.join(home,'.newal-code','server-key')]
key=next((open(p,encoding='utf-8').read().strip() for p in keys if os.path.isfile(p)),None)
if not key:
    import secrets
    key=secrets.token_urlsafe(32)
    try:
        with os.fdopen(os.open(keyfile,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600),'w') as fh:
            fh.write(key)
    except FileExistsError:
        with open(keyfile,encoding='utf-8') as fh:
            key=fh.read().strip()
if len(key)<16: raise RuntimeError('M4_BOOTSTRAP_INVALID_KEY')
base='http://127.0.0.1:8791'
def api(path,body=None):
    method='POST' if body is not None else 'GET'
    data=json.dumps(body,ensure_ascii=False).encode('utf-8') if body is not None else None
    request=urllib.request.Request(base+path, data=data, method=method,headers={'X-NewAl-Key':key,'Content-Type':'application/json'})
    with urllib.request.urlopen(request,timeout=15) as response:
        raw=response.read(2100000)
        if len(raw)>2000000: raise RuntimeError('Server response exceeded size limit')
        return json.loads(raw)
def ready():
    try: return isinstance(api('/api/state'),dict)
    except Exception: return False
if not ready():
    # Do not install a separate app or wait for a manual NewAl pairing wizard.
    # Install the user's OWN NewAl source at a fixed immutable Git commit.
    import io,zipfile,tempfile,shutil,stat,socket
    app_dir=os.path.join(state_dir,'app')
    source='https://codeload.github.com/Musabgpt/NewAl/zip/abfa91ee0644b27ba1957567ab484d4073d71efd'
    if not os.path.isfile(os.path.join(app_dir,'newal_code','__main__.py')):
        try:
            with urllib.request.urlopen(urllib.request.Request(source,
                    headers={'User-Agent':'M4-Termux-NewAl-Installer/1'}),timeout=25) as response:
                archive=response.read(12500001)
            if len(archive)>12500000: raise ValueError('Archive exceeds safe size')
            staging=tempfile.mkdtemp(prefix='newal-staged-',dir=state_dir)
            try:
                count=0
                total=0
                with zipfile.ZipFile(io.BytesIO(archive)) as pkg:
                    for info in pkg.infolist():
                        marker='/desktop/newal_code/'
                        if marker not in info.filename: continue
                        rel=info.filename.split(marker,1)[1]
                        if not rel or info.is_dir(): continue
                        pieces=rel.split('/')
                        if any(p in ('','.','..') for p in pieces): raise ValueError('Unsafe archive path')
                        if stat.S_ISLNK(info.external_attr>>16): raise ValueError('Symlink in archive')
                        count+=1
                        total+=info.file_size
                        if count>200 or total>7500000 or info.file_size>1500000:
                            raise ValueError('Package size limit exceeded')
                        target=os.path.join(staging,'newal_code',*pieces)
                        os.makedirs(os.path.dirname(target),exist_ok=True)
                        with pkg.open(info) as src, open(target,'wb') as dst:
                            shutil.copyfileobj(src,dst)
                if not os.path.isfile(os.path.join(staging,'newal_code','__main__.py')):
                    raise ValueError('NewAl Python package absent in pinned archive')
                # Replace only an incomplete prior installation; validated new
                # archive contents are staged before old bytes are touched.
                if os.path.isdir(app_dir):
                    shutil.rmtree(app_dir)
                os.replace(staging,app_dir)
            finally:
                if os.path.isdir(staging): shutil.rmtree(staging)
        except Exception as error:
            raise RuntimeError('M4_BOOTSTRAP_DOWNLOAD_FAILED: '+type(error).__name__) from None
    if not ready():
        # If another process holds the loopback port, never bypass auth or
        # silently start NewAl on an unexpected port.
        with socket.socket() as probe:
            probe.settimeout(0.6)
            if probe.connect_ex(('127.0.0.1',8791))==0:
                raise RuntimeError('M4_BOOTSTRAP_PORT_BUSY')
        env=os.environ.copy()
        env['NEWAL_SERVER_KEY']=key
        env['NEWAL_CODE_HOME']=os.path.join(home,'.newal-code')
        env['PYTHONPATH']=app_dir+os.pathsep+env.get('PYTHONPATH','')
        log=os.path.join(state_dir,'server.log')
        with open(log,'ab',buffering=0) as out:
            proc=subprocess.Popen([sys.executable,'-m','newal_code','app',
                    '--port','8791','--no-browser'],cwd=home,env=env,
                    stdin=subprocess.DEVNULL,stdout=out,stderr=subprocess.STDOUT,
                    start_new_session=True)
        for wait in range(60):
            if ready(): break
            if proc.poll() is not None:
                raise RuntimeError('M4_BOOTSTRAP_SERVER_FAILED: see Termux server.log')
            time.sleep(0.35)
        if not ready(): raise RuntimeError('M4_BOOTSTRAP_SERVER_TIMEOUT')
if phase=='setup':
    print(json.dumps({'status':'succeeded',
        'content':'تم تجهيز خادم NewAl داخل Termux. أرسل طلبك الآن؛ إذا لم يكن هناك نموذج متاح، ستحتاج لاختيار نموذج أو مزود.'},ensure_ascii=False))
    sys.exit(0)
# NewAl runs in an explicit project workspace; do not use the whole
# Termux home containing private pairing keys as the agent's project root.
workspace=os.path.join(home,'projects','M4')
os.makedirs(workspace,mode=0o700,exist_ok=True)
sessions_path=os.path.join(home,'.m4-newal-sessions.json')
try:
    sessions=json.load(open(sessions_path,encoding='utf-8'))
    if not isinstance(sessions,dict): sessions={}
except (OSError,ValueError): sessions={}
sid=sessions.get(owner)
if sid:
    try:
        old_info=api('/api/sessions/'+sid)
        old_root=(old_info.get('meta') or {}).get('root')
        if old_root and os.path.realpath(old_root)!=os.path.realpath(workspace): sid=None
    except Exception: sid=None
safe_modes={'plan_only':'read-only','ask_always':'ask',
            'accept_edits':'auto-edit','auto_project':'auto-edit'}
if mode not in safe_modes: raise ValueError('Unknown M4 permission mode')
desired=safe_modes[mode]
if not sid:
    if phase=='resume': raise RuntimeError('NewAl session was lost before approval')
    created=api('/api/sessions',{'root':workspace,'mode':desired,'warm':False})
    sid=created['id']
    sessions[owner]=sid
    temporary=sessions_path+'.tmp'
    with open(temporary,'w',encoding='utf-8') as fh: json.dump(sessions,fh)
    os.chmod(temporary,0o600)
    os.replace(temporary,sessions_path)
# A resumed approval never resends the user's text. Doing so would duplicate
# edits, messages or external actions while the original turn is still running.
if phase=='resume':
    import re
    if not re.fullmatch(r'[a-zA-Z0-9_.:-]{1,120}',decision_id):
        raise ValueError('Malformed approval id')
    confirmed=api('/api/approvals/'+decision_id,
                  {'answer':'once' if allow else 'deny'})
    if not confirmed.get('ok'): raise RuntimeError('NewAl approval expired or was not found')
    prior_turn=baseline
else:
    # Reapply the user's actual M4 permission level on each new turn.
    api('/api/sessions/'+sid+'/settings',{'mode':desired})
    prior=api('/api/sessions/'+sid)
    prior_turn=int((prior.get('meta') or {}).get('turn') or 0)
    sent=api('/api/sessions/'+sid+'/send',{'text':message,'lang':'ar'})
    if not sent.get('started'):
        print(json.dumps({'status':'observed','content':str(sent.get('reply',''))},ensure_ascii=False))
        sys.exit(0)
deadline=time.monotonic()+100
answer=None
while time.monotonic()<deadline:
    current=api('/api/sessions/'+sid)
    approvals=current.get('approvals') or []
    if approvals:
        pending=approvals[0]
        print(json.dumps({'status':'approval_required','approval_id':pending['id'],
            'tool':str(pending.get('tool','tool'))[:120],
            'details':str(pending.get('input',pending.get('args','')))[:3000],
            'prior_turn':prior_turn,
            'content':'موافقة مستخدم مطلوبة لتنفيذ أداة NewAl'},ensure_ascii=False))
        sys.exit(0)
    if not current.get('busy'):
        for ev in reversed(current.get('events') or []):
            # Old turn_end receipts may still be in persisted session events.
            # Match only the actual new turn, never report a stale answer.
            if ev.get('type')=='turn_end' and int(ev.get('turn') or 0)>prior_turn:
                answer=ev.get('answer','')
                if ev.get('error'): raise RuntimeError(str(ev['error'])[:500])
                break
        if answer is not None: break
    time.sleep(0.35)
if answer is None: raise TimeoutError('NewAl Code did not return a finished turn')
# Publish only verified current-turn project files, never the private
# Termux home, credentials, logs, or a stale prior-turn artifact.
published=[]
try:
    import re
    sys.path.insert(0,os.path.join(state_dir,'app'))
    from newal_code.session import Session
    turn=int((current.get('meta') or {}).get('turn') or 0)
    session=Session.load(sid)
    candidates=session.changes(turn)
    for file in candidates:
        if len(published)>=2: break
        if file.get('status') not in ('added','modified'): continue
        rel=file.get('path','')
        absolute=os.path.realpath(os.path.join(workspace,rel))
        if not absolute.startswith(os.path.realpath(workspace)+os.sep): continue
        name=os.path.basename(absolute)
        if not re.fullmatch(r'[\\w. -]{1,120}',name,re.UNICODE) or name.startswith('.'): continue
        if re.search(r'secret|token|passwd|credential|api.?key|\\.env',name,re.I): continue
        if os.path.splitext(name)[1].lower() not in ('.html','.md','.txt','.py','.sh','.json'): continue
        if not os.path.isfile(absolute) or os.path.islink(absolute): continue
        if os.path.getsize(absolute) not in range(1,14001): continue
        with open(absolute,'rb') as input_file: data=input_file.read(14001)
        if len(data)>14000: continue
        try: data.decode('utf-8','strict')
        except UnicodeDecodeError: continue
        published.append({'name':name,'content_b64':base64.b64encode(data).decode('ascii')})
except Exception:
    # The model answer is real even if artifact transfer fails.
    pass
print(json.dumps({'status':'succeeded','content':answer,
                  'backend':'termux-newal','files':published},ensure_ascii=False))
""".trimIndent()

    /** Set up a real NewAl agent in Termux without sending an AI prompt. */
    suspend fun setup(context: Context, owner: String): JSONObject = withContext(Dispatchers.IO) {
        invokeWorker(context, owner, "", "plan_only", "setup", "", 0, false)
    }

    /**
     * The entire user journey stays inside M4: NewAl tool approvals appear in
     * the existing Compose CapabilityBroker dialog, never a second browser.
     * Any denial is explicitly posted back to the original NewAl session.
     */
    suspend fun chat(context: Context, owner: String, message: String, mode: String): JSONObject =
        withContext(Dispatchers.IO) {
            var result = invokeWorker(context, owner, message, mode, "start", "", 0, false)
            repeat(5) {
                if (result.optString("status") != "approval_required") return@withContext result
                val approvalId = result.optString("approval_id")
                val tool = result.optString("tool", "tool")
                val details = result.optString("details").take(3000)
                if (!approvalId.matches(Regex("[a-zA-Z0-9_.:-]{1,120}")))
                    return@withContext JSONObject().put("status", "blocked")
                        .put("content", "وصل معرّف موافقة غير صالح من NewAl")
                val approvalArgs = JSONObject().put("tool", tool).put("details", details)
                val permitted = CapabilityBroker.request(owner, "NewAl: " + tool, approvalArgs)
                result = invokeWorker(
                    context, owner, message, mode, "resume", approvalId,
                    result.optInt("prior_turn", 0), permitted
                )
                if (!permitted && result.optString("status") != "approval_required") {
                    return@withContext JSONObject().put("status", "denied")
                        .put("content", "رفضت تنفيذ أداة NewAl. لم تُمنح الموافقة.")
                }
            }
            JSONObject().put("status", "blocked").put("content",
                "تجاوزت جلسة NewAl حد طلبات الموافقة؛ أوقفت المتابعة لحماية المستخدم.")
        }

    private suspend fun invokeWorker(
        context: Context, owner: String, message: String, mode: String,
        phase: String, approvalId: String, baseline: Int, allow: Boolean
    ): JSONObject {
        val encoded = Base64.encodeToString(message.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val args = JSONArray(listOf("-c", worker, owner, encoded, mode, phase,
            approvalId, baseline.toString(), if (allow) "allow" else "deny"))
        val first = TermuxClient.run(
            context, JSONObject().put("executable", "python")
                .put("arguments", args).put("timeout", 110), owner
        )
        val id = first.optString("command_id")
        val receipt = if (first.optString("status") == "pending_result" && id.isNotBlank()) {
            withTimeout(115000L) {
                var response: JSONObject
                do {
                    delay(400)
                    response = TermuxClient.output(context, owner, id)
                } while (response.optString("status") == "pending_result")
                response
            }
        } else first
        if (receipt.optString("status") != "succeeded") {
            val diagnostic = receipt.optString("stderr") + " " + receipt.optString("error")
            return JSONObject().put("status", "failed")
                .put("content", friendlyFailure(diagnostic, receipt.optString("status")))
                .put("diagnostic_code", diagnosticCode(diagnostic))
        }
        if (receipt.optBoolean("truncated"))
            return JSONObject().put("status", "failed")
                .put("content", "نتيجة Termux طويلة جداً؛ لم أعتبرها نجاحاً. افتح فحص المحرك.")
        val stdout = receipt.optString("stdout").trim()
        return runCatching { JSONObject(stdout.substringAfterLast('\n')) }.getOrElse {
            JSONObject().put("status", "failed")
                .put("content", "المحرك لم يُرجع نتيجة يمكن قراءتها. افتح فحص Termux بدلاً من عرض تفاصيل Python.")
        }
    }
    // Technical stderr stays private in TermuxClient.result.json; chat receives
    // a task-specific human sentence instead of a Python traceback.
    private fun diagnosticCode(raw: String): String = when {
        "M4_BOOTSTRAP_DOWNLOAD_FAILED" in raw -> "bootstrap_network"
        "M4_BOOTSTRAP_PORT_BUSY" in raw -> "port_in_use"
        "M4_BOOTSTRAP_SERVER_FAILED" in raw -> "server_start"
        "M4_BOOTSTRAP_SERVER_TIMEOUT" in raw -> "server_timeout"
        "No available model" in raw || "No model" in raw -> "model_missing"
        else -> "termux_execution"
    }

    private fun friendlyFailure(raw: String, status: String): String = when {
        "M4_BOOTSTRAP_DOWNLOAD_FAILED" in raw ->
            "تعذّر تجهيز وكيل NewAl تلقائياً من مستودعك. تأكد من اتصال الإنترنت ثم أعد المحاولة. لا حاجة لمفتاح اقتران يدوي."
        "M4_BOOTSTRAP_PORT_BUSY" in raw ->
            "المنفذ المحلي 8791 مستخدم بخادم آخر. أوقف نسخة NewAl القديمة في Termux ثم جرّب مجدداً."
        "M4_BOOTSTRAP_SERVER_FAILED" in raw || "M4_BOOTSTRAP_SERVER_TIMEOUT" in raw ->
            "تم تجهيز ملفات NewAl لكن خادمه لم يبدأ. افتح فحص Termux لمراجعة سجل التشغيل المحلي."
        "M4_BOOTSTRAP_INVALID_KEY" in raw ->
            "تعذّر تجهيز مفتاح NewAl الآمن. لن أتجاوز التحقق الأمني."
        "No available model" in raw || "model not configured" in raw.lowercase() ->
            "NewAl بدأ، لكنه يحتاج مزوّد ذكاء اصطناعي يعمل. افتح إعدادات النموذج داخل التطبيق."
        status == "permission_required" ->
            "يلزم منح M4 صلاحية RUN_COMMAND لتشغيل Termux."
        status == "pending_result" ->
            "ما زال Termux ينفّذ الأمر؛ لم أصل إلى رد مؤكّد بعد."
        status == "denied" -> "تم رفض تشغيل الأمر."
        else -> "تعذّر تنفيذ الوكيل داخل Termux. لم يُنفّذ طلبك بنجاح؛ جرّب فحص المحرك."
    }

}
