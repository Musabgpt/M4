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
if phase not in ('start','resume'): raise ValueError('Unknown transport phase')
if not owner.replace('-','').isalnum() or len(owner)>80: raise ValueError('Invalid conversation id')
message=base64.b64decode(payload).decode('utf-8')
if len(message)>24000: raise ValueError('Message too long')
home=os.path.expanduser('~')
keys=[os.path.join(home,'.newal-cloud','key'),os.path.join(home,'.newal-code','termux','key'),os.path.join(home,'.newal-code','server-key')]
key=next((open(p,encoding='utf-8').read().strip() for p in keys if os.path.isfile(p)),None)
if not key: raise RuntimeError('NewAl Code pairing key not found in Termux; link NewAl Code first')
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
    for command in ('newal-termux',):
        exe=os.path.join(os.environ.get('PREFIX','/data/data/com.termux/files/usr'),'bin',command)
        if os.path.exists(exe):
            subprocess.run([exe,'start'],capture_output=True,timeout=20)
            break
    if not ready(): raise RuntimeError('NewAl Code server is not running in Termux on port 8791')
sessions_path=os.path.join(home,'.m4-newal-sessions.json')
try:
    sessions=json.load(open(sessions_path,encoding='utf-8'))
    if not isinstance(sessions,dict): sessions={}
except (OSError,ValueError): sessions={}
sid=sessions.get(owner)
if sid:
    try: api('/api/sessions/'+sid)
    except Exception: sid=None
safe_modes={'plan_only':'read-only','ask_always':'ask',
            'accept_edits':'auto-edit','auto_project':'auto-edit'}
if mode not in safe_modes: raise ValueError('Unknown M4 permission mode')
desired=safe_modes[mode]
if not sid:
    if phase=='resume': raise RuntimeError('NewAl session was lost before approval')
    created=api('/api/sessions',{'root':home,'mode':desired,'warm':False})
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
print(json.dumps({'status':'succeeded','content':answer,'backend':'termux-newal'},ensure_ascii=False))
""".trimIndent()

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
                .put("arguments", args).put("timeout", 15), owner
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
        if (receipt.optString("status") != "succeeded")
            return JSONObject().put("status", receipt.optString("status", "failed"))
                .put("content", receipt.optString("error",
                    receipt.optString("stderr", "لم يكتمل تنفيذ أمر Termux")))
        if (receipt.optBoolean("truncated"))
            return JSONObject().put("status", "failed")
                .put("content", "نتيجة Termux تجاوزت حجم السجل المسموح؛ لا يمكن تأكيد الرد")
        val stdout = receipt.optString("stdout").trim()
        return runCatching { JSONObject(stdout.substringAfterLast('\n')) }.getOrElse {
            JSONObject().put("status", "failed")
                .put("content", "خادم NewAl لم يُعد JSON صالحاً: " + stdout.takeLast(300))
        }
    }
}
