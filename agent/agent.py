#!/usr/bin/env python3
"""Resumable AI coding loop for the app's Alpine Linux. Python stdlib only."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import shutil
import sqlite3
import subprocess
import sys
import time
import urllib.parse
import urllib.request
import uuid

# Portable, tested continuity and durable task checkpoint modules imported
# from the last verified advanced NewAl-Cloud build.
sys.path.insert(0, str(Path(__file__).resolve().parent))
from newal import continuity, task_state
from provider_routes import order as gateway_order

STATE = ".term-agent"
COMMANDS = {"python","python3","node","npm","npx","git","gh","pytest","vitest",
 "gradle","java","javac","bash","sh","rg","grep","ls","find","cat","pwd",
 "eslint","tsc","ast-grep","tree-sitter","cargo","make","cmake"}
TOOLS = [
 {"type":"function","function":{"name":"list_files","description":"List a directory inside the project.","parameters":{"type":"object","properties":{"path":{"type":"string"}}}}},
 {"type":"function","function":{"name":"read_file","description":"Read a project text file.","parameters":{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}}},
 {"type":"function","function":{"name":"search_code","description":"Bounded literal ripgrep search in project files.","parameters":{"type":"object","properties":{"query":{"type":"string"},"path":{"type":"string"}},"required":["query"]}}},
 {"type":"function","function":{"name":"git_status","description":"Show actual Git working-tree changes.","parameters":{"type":"object","properties":{}}}},
 {"type":"function","function":{"name":"write_file","description":"Write UTF-8 project text with automatic backup.","parameters":{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}},"required":["path","content"]}}},
 {"type":"function","function":{"name":"run_command","description":"Run a bounded command after user approves execution.","parameters":{"type":"object","properties":{"command":{"type":"string"}},"required":["command"]}}},
 {"type":"function","function":{"name":"finish","description":"Request independent verification of goal.","parameters":{"type":"object","properties":{"summary":{"type":"string"}},"required":["summary"]}}}
]
SYSTEM = Path(__file__).with_name("system_prompt.md").read_text(encoding="utf-8")

def load(path, default):
    try:
        return json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return default

def save(path, value):
    path = Path(path)
    temp = path.with_suffix(path.suffix + ".tmp")
    temp.write_text(json.dumps(value, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
    temp.chmod(0o600)
    temp.replace(path)

def workspace(project):
    root = Path(project).expanduser().resolve()
    if not root.is_dir():
        raise ValueError("Missing project directory: "+str(root))
    state = root/STATE
    state.mkdir(exist_ok=True, mode=0o700)
    if not (state/"checks.json").exists():
        save(state/"checks.json", [])
    return root, state

def safe_path(root, value, write=False):
    if not isinstance(value,str) or not value or "\x00" in value:
        raise ValueError("Invalid path")
    relative = Path(value)
    if relative.is_absolute() or ".." in relative.parts or (relative.parts and relative.parts[0] in (STATE, ".git")):
        raise ValueError("Restricted project path")
    candidate = (root/relative).resolve()
    if not candidate.is_relative_to(root) or (write and candidate == root):
        raise ValueError("Path escapes project")
    for restricted in (root/STATE, root/".git"):
        if candidate == restricted or candidate.is_relative_to(restricted):
            raise ValueError("Restricted project metadata")
    if write and (candidate.is_symlink() or candidate.is_dir()):
        raise ValueError("Cannot overwrite symlink or directory")
    return candidate

def history(state):
    db = sqlite3.connect(str(state/"events.sqlite3"))
    db.execute("CREATE TABLE IF NOT EXISTS events(id INTEGER PRIMARY KEY, time REAL, kind TEXT, data TEXT)")
    db.commit()
    return db

def log(db, kind, data):
    db.execute("INSERT INTO events(time,kind,data) VALUES(?,?,?)",
               (time.time(),kind,json.dumps(data,ensure_ascii=False)[:16000]))
    db.commit()

def recent(db):
    rows = db.execute("SELECT kind,data FROM events ORDER BY id DESC LIMIT 10").fetchall()
    return "\n".join(kind+": "+data[:2000] for kind,data in reversed(rows))

def backup(state, path, relative):
    folder = state/"backups"
    folder.mkdir(exist_ok=True)
    name = uuid.uuid4().hex
    exists = path.exists()
    if exists:
        if path.stat().st_size > 5000000:
            raise ValueError("Large file: manual backup required (>5 MB)")
        (folder/name).write_bytes(path.read_bytes())
    with (state/"backups.jsonl").open("a",encoding="utf-8") as stream:
        stream.write(json.dumps({"path":relative,"name":name,"exists":exists})+"\n")

def command(root, cmd, approved, timeout=90):
    if not approved:
        return "Execution blocked; add --allow-exec if you trust this project."
    try:
        args = shlex.split(cmd)
    except ValueError as error:
        return "Invalid command: "+str(error)
    if not args or "/" in args[0] or args[0] not in COMMANDS:
        return "Executable not allowed. Allowed: "+", ".join(sorted(COMMANDS))
    try:
        # Hide provider tokens and common secrets from untrusted project processes.
        # Commands can still read user project files: this is NOT a sandbox.
        child_env={k:v for k,v in os.environ.items()
                   if not (k.upper().endswith(("_KEY","_TOKEN","_SECRET","_PASSWORD"))
                           or k.upper() in ("TERM_AGENT_API_KEY","AWS_ACCESS_KEY_ID",
                                            "AWS_SECRET_ACCESS_KEY","GH_TOKEN","GITHUB_TOKEN"))}
        run=subprocess.run(args,cwd=root,text=True,input="",capture_output=True,
                           timeout=timeout,env=child_env)
        return json.dumps({"exit_code":run.returncode,"stdout":run.stdout[-7000:],
                           "stderr":run.stderr[-4000:]},ensure_ascii=False)
    except subprocess.TimeoutExpired:
        return "Command timed out"
    except OSError as error:
        return "Execution error: "+str(error)

def execute(root, state, name, args, approved):
    try:
        if name == "list_files":
            path=safe_path(root,args.get("path","."))
            return "\n".join(sorted(p.name+("/" if p.is_dir() else "")
                        for p in path.iterdir() if p.name not in (".git",STATE))[:250])
        if name == "read_file":
            path=safe_path(root,args["path"])
            if path.stat().st_size > 250000:
                return "File too large, use rg."
            return path.read_text(encoding="utf-8")[:50000]
        if name == "search_code":
            query=args["query"]
            if not isinstance(query,str) or not query or len(query)>240:
                return "Invalid literal search"
            path=safe_path(root,args.get("path","."))
            p=subprocess.run(["rg","-F","-n","--max-count","20","--",query,str(path)],
                             cwd=root,capture_output=True,text=True,timeout=20)
            return p.stdout[:8000] if p.returncode in (0,1) else "Search error: "+p.stderr[:250]
        if name == "git_status":
            p=subprocess.run(["git","status","--short"],cwd=root,
                             capture_output=True,text=True,timeout=20)
            return p.stdout[:5000] if p.returncode==0 else "Git error: "+p.stderr[:250]
        if name == "write_file":
            path=safe_path(root,args["path"],write=True)
            data=args["content"]
            if not isinstance(data,str) or len(data.encode("utf-8"))>500000:
                return "Invalid write or file larger than 500KB"
            backup(state,path,args["path"])
            path.parent.mkdir(parents=True,exist_ok=True)
            path.write_text(data,encoding="utf-8")
            return "Wrote "+args["path"]
        if name == "run_command":
            return command(root,args["command"],approved)
        if name == "finish":
            return "VERIFY"
        return "Unknown tool "+name
    except (ValueError,OSError,KeyError,UnicodeError) as error:
        return "Tool error: "+str(error)

def endpoint():
    routes=gateway_order(os.environ.get("M4_GATEWAY_FAMILY","freellmpool"))
    selected=routes[0]
    return selected.base_url.rstrip("/")+"/chat/completions",selected.model

def ask(url,model,messages):
    # Only fixed catalog routes. Local services are never reached over a LAN.
    errors=[]
    for route in gateway_order(os.environ.get("M4_GATEWAY_FAMILY","freellmpool")):
        try:
            payload=json.dumps({"model":route.model,"messages":messages,
                                "tools":TOOLS,"tool_choice":"auto"},
                               ensure_ascii=False).encode("utf-8")
            req=urllib.request.Request(route.base_url.rstrip("/")+"/chat/completions",
                                       payload,{"Content-Type":"application/json"},method="POST")
            with urllib.request.urlopen(req,timeout=4 if route.local else 50) as response:
                reply=json.load(response)["choices"][0]["message"]
                if not isinstance(reply,dict):
                    raise ValueError("Invalid model reply")
                return reply
        except (OSError,KeyError,ValueError,IndexError,TypeError) as error:
            errors.append(route.name+": "+str(error)[:110])
    raise RuntimeError("No available tool-call gateway: "+"; ".join(errors[-4:]))

def verify(root,state,approved):
    checks=load(state/"checks.json",[])
    if not isinstance(checks,list) or not checks:
        return False,"No checks configured: term-agent check-add 'python3 -m unittest discover -s agent_tests -v'"
    if not approved:
        return False,"Use --allow-exec to verify"
    outputs=[]
    passed=True
    for check in checks[:8]:
        result=command(root,check,True,120)
        outputs.append({"command":check,"result":result[:5000]})
        try:
            if json.loads(result).get("exit_code")!=0:
                passed=False
        except ValueError:
            passed=False
    return passed,json.dumps(outputs,ensure_ascii=False)

def loop(root,state,goal,max_steps,approved):
    path=state/"state.json"
    current=load(path,{})
    goal=goal or current.get("goal")
    if not goal:
        raise ValueError("Provide the goal to start a new task")
    if current.get("goal")!=goal:
        current={"goal":goal,"step":0,"phase":"new","last":"","repeat":0}
        checkpoint=task_state.checkpoint(
            str(root),objective=goal,progress="Goal received",
            next_step="Inspect the project and implement the first verifiable change")
        current["newal_checkpoint"]=checkpoint["id"]
    url,model=endpoint()
    current["phase"]="running"
    save(path,current)
    db=history(state)
    print("GOAL:",goal,"MODEL:",model,flush=True)
    try:
        dialog=[{"role":"system","content":SYSTEM},
                {"role":"user","content":"GOAL: "+goal+"\nProject: "+str(root)+
                  "\nPrevious work:\n"+recent(db)}]
        for _ in range(max_steps):
            current["step"]+=1
            reply=ask(url,model,dialog)
            calls=(reply.get("tool_calls") or [])[:4]
            for pos,call in enumerate(calls):
                call.setdefault("id","m4-step-"+str(current["step"])+"-"+str(pos))
                call.setdefault("type","function")
            if calls:
                dialog.append({"role":"assistant","content":reply.get("content") or None,
                               "tool_calls":calls})
            signature=hashlib.sha256(json.dumps(calls,sort_keys=True).encode()).hexdigest()
            if not calls:
                msg=str(reply.get("content") or "")[:1200]
                log(db,"assistant",msg)
                if continuity.lost_task_reply(msg,goal):
                    log(db,"continuity",continuity.correction(goal))
                    print("Recovered original goal after model lost task",flush=True)
                print("No tool call:",msg,flush=True)
            for item in calls:
                fn=item.get("function") or {}
                name=fn.get("name","")
                try:
                    args=json.loads(fn.get("arguments") or "{}")
                    if not isinstance(args,dict):
                        args={}
                except ValueError:
                    args={}
                output=execute(root,state,name,args,approved)
                details={"tool":name,"args":({k:v for k,v in args.items() if k!="content"}),
                         "result":output[:7000]}
                log(db,"tool",details)
                dialog.append({"role":"tool","tool_call_id":item["id"],"content":output[:7000]})
                print("Step",current["step"],name,output[:300],flush=True)
                if name=="finish":
                    passed,report=verify(root,state,approved)
                    log(db,"verification",report)
                    if passed:
                        current["phase"]="complete"
                        current["summary"]=str(args.get("summary",""))
                        if current.get("newal_checkpoint"):
                            task_state.complete(str(root),current["newal_checkpoint"],
                                                current["summary"])
                        save(path,current)
                        print("COMPLETE: verification passed",flush=True)
                        return 0
                    if report.startswith("No checks"):
                        current["phase"]="needs-checks"
                        save(path,current)
                        print("BLOCKED:",report,flush=True)
                        return 2
                    print("Verification failed; repairing...",report[:1000],flush=True)
            if len(dialog)>16:
                dialog=dialog[:2]+dialog[-14:]
            if current.get("newal_checkpoint"):
                task_state.checkpoint(
                    str(root),task_id=current["newal_checkpoint"],
                    progress="Finished verified action cycle "+str(current["step"]),
                    next_step="Continue working and run independent verification",
                    evidence="Recorded events in .term-agent/events.sqlite3")
            current["repeat"]=(current.get("repeat",0)+1 if signature==current.get("last") else 0)
            current["last"]=signature
            if current["repeat"]>=3:
                current["phase"]="stuck"
                save(path,current)
                print("STUCK: identical repeated actions",flush=True)
                return 3
            save(path,current)
        current["phase"]="paused-step-limit"
        save(path,current)
        print("PAUSED: step budget; resume with term-agent resume",flush=True)
        return 2
    except (OSError,ValueError,RuntimeError) as error:
        current["phase"]="blocked"
        current["error"]=str(error)
        save(path,current)
        print("BLOCKED:",error,file=sys.stderr)
        return 4
    finally:
        db.close()

def rollback(root,state,relative):
    path=safe_path(root,relative,write=True)
    ledger=state/"backups.jsonl"
    if not ledger.exists():
        raise ValueError("No backups")
    for line in reversed(ledger.read_text().splitlines()):
        item=json.loads(line)
        if item["path"]==relative:
            if item["exists"]:
                source=state/"backups"/item["name"]
                if not source.is_file():
                    raise ValueError("Missing backup")
                path.parent.mkdir(parents=True,exist_ok=True)
                path.write_bytes(source.read_bytes())
            else:
                path.unlink(missing_ok=True)
            print("Rolled back",relative)
            return
    raise ValueError("No backup found for "+relative)

def main():
    os.umask(0o077)
    p=argparse.ArgumentParser(description="Offline-installed AI coding agent host (provider required)")
    p.add_argument("--project",default=".")
    sub=p.add_subparsers(dest="action",required=True)
    sub.add_parser("init")
    r=sub.add_parser("run")
    r.add_argument("goal",nargs="?")
    r.add_argument("--steps",type=int,default=30)
    r.add_argument("--allow-exec",action="store_true")
    re=sub.add_parser("resume")
    re.add_argument("--steps",type=int,default=30)
    re.add_argument("--allow-exec",action="store_true")
    sub.add_parser("status")
    sub.add_parser("tasks")
    sub.add_parser("doctor")
    sub.add_parser("tools")
    a=sub.add_parser("check-add")
    a.add_argument("check")
    v=sub.add_parser("verify")
    v.add_argument("--allow-exec",action="store_true")
    b=sub.add_parser("rollback")
    b.add_argument("path")
    args=p.parse_args()
    if args.action=="doctor":
        print("M4 Arabic Terminal Agent | gateway:",os.environ.get("M4_GATEWAY_FAMILY","freellmpool"))
        for item in ("python3","node","git","tmux","rg","gh"):
            print(item,shutil.which(item) or "not installed")
        return 0
    if args.action=="tools":
        for entry in TOOLS:
            print("[built-in]",entry["function"]["name"],"—",entry["function"]["description"])
        return 0
    root,state=workspace(args.project)
    if args.action=="init":
        print("Initialized",root,"; configure checks with term-agent check-add")
        return 0
    if args.action=="status":
        print(json.dumps(load(state/"state.json",{"phase":"new"}),ensure_ascii=False,indent=2))
        return 0
    if args.action=="tasks":
        print(json.dumps(task_state.list_tasks(str(root)),ensure_ascii=False,indent=2))
        return 0
    if args.action=="rollback":
        rollback(root,state,args.path)
        return 0
    if args.action=="check-add":
        check=shlex.split(args.check)
        if not check or check[0] not in COMMANDS:
            raise ValueError("Check executable not permitted")
        checks=load(state/"checks.json",[])
        if args.check not in checks:
            checks.append(args.check)
        save(state/"checks.json",checks[:8])
        print("Added:",args.check)
        return 0
    if args.action=="verify":
        ok,msg=verify(root,state,args.allow_exec)
        print(msg)
        return 0 if ok else 2
    if args.steps<1 or args.steps>500:
        raise ValueError("--steps must be between 1 and 500")
    return loop(root,state,getattr(args,"goal",None),args.steps,args.allow_exec)

if __name__=="__main__":
    try:
        sys.exit(main())
    except (OSError,ValueError) as error:
        print("ERROR:",error,file=sys.stderr)
        sys.exit(2)
