"""Authenticated Termux execution service; persistent subprocesses and MCP stdio."""
import argparse, codecs, hmac, json, os, pty, queue, select, signal, subprocess, threading, time, uuid, sys
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

class Session:
    def __init__(self, owner, command, cwd, tty=False):
        self.owner, self.id, self.tty = owner, uuid.uuid4().hex, tty
        self.lock, self.chunks, self.sequence, self.size = threading.Lock(), [], 0, 0
        if tty:
            master, slave = pty.openpty()
            try:
                self.process = subprocess.Popen([sys.executable,'-c',"import fcntl,termios,os,sys; fcntl.ioctl(0,termios.TIOCSCTTY,0); os.execvp('bash',['bash','-lc',sys.argv[1]])",command], cwd=cwd, stdin=slave, stdout=slave, stderr=slave, start_new_session=True)
            finally: os.close(slave)
            self.input = os.fdopen(os.dup(master),'wb',buffering=0)
            self.readers = [self.reader(os.fdopen(master,'rb',buffering=0),'stdout')]
        else:
            self.process = subprocess.Popen(['bash','-lc',command],cwd=cwd,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,start_new_session=True)
            self.input = self.process.stdin
            self.readers = [self.reader(self.process.stdout,'stdout'),self.reader(self.process.stderr,'stderr')]
    def append(self, channel, value):
        if not value: return
        with self.lock:
            self.sequence += 1; self.chunks.append((self.sequence,channel,value)); self.size += len(value)
            while self.size>512000: self.size -= len(self.chunks.pop(0)[2])
    def reader(self, stream, channel):
        def consume():
            decoder=codecs.getincrementaldecoder('utf-8')('replace')
            try:
                while True:
                    data=os.read(stream.fileno(),4096)
                    if not data: break
                    self.append(channel,decoder.decode(data))
            except OSError: pass
            finally: self.append(channel,decoder.decode(b'',final=True)); stream.close()
        thread=threading.Thread(target=consume,daemon=True); thread.start(); return thread
    def output(self,cursor=0,wait=0):
        deadline=time.monotonic()+min(max(wait,0),10000)/1000
        while time.monotonic()<deadline and self.process.poll() is None: time.sleep(.025)
        code=self.process.poll()
        if code is not None:
            for reader in self.readers: reader.join(.5)
        with self.lock:
            selected=[r for r in self.chunks if r[0]>cursor]; rows=[]; count=0
            for row in selected:
                if count+len(row[2])>64000: break
                rows.append(row); count+=len(row[2])
            return dict(session_id=self.id,status='running' if code is None else 'completed',exit_code=code,
                stdout=''.join(r[2] for r in rows if r[1]=='stdout'),stderr=''.join(r[2] for r in rows if r[1]=='stderr'),
                tty=self.tty,next_cursor=rows[-1][0] if rows else cursor,has_more=len(selected)>len(rows),
                truncated=bool(self.chunks and cursor<self.chunks[0][0]-1))
    def stop(self):
        try: os.killpg(self.process.pid,signal.SIGTERM)
        except ProcessLookupError: pass
        try: self.process.wait(timeout=2)
        except subprocess.TimeoutExpired:
            try: os.killpg(self.process.pid,signal.SIGKILL)
            except ProcessLookupError: pass
            self.process.wait(timeout=2)
        try: self.input.close()
        except OSError: pass

class FilesystemMCP:
    def __init__(self,command,root):
        self.lock,self.messages,self.sequence=threading.Lock(),queue.Queue(maxsize=100),0
        self.process=subprocess.Popen(command+[str(root)],cwd=root,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,text=True,bufsize=1,start_new_session=True)
        def read():
            try:
                for line in iter(lambda:self.process.stdout.readline(1000001),''):
                    if len(line)>1000000: break
                    self.messages.put(json.loads(line),timeout=1)
            except (ValueError,queue.Full): pass
            finally:
                try: self.messages.put({'error':{'message':'Filesystem MCP exited'}},timeout=1)
                except queue.Full: pass
        self.reader=threading.Thread(target=read,daemon=True); self.reader.start()
        try:
            response=self.rpc('initialize',{'protocolVersion':'2024-11-05','capabilities':{},'clientInfo':{'name':'M4-Termux','version':'1.0'}})
            if response.get('protocolVersion') not in {'2024-11-05','2025-03-26','2025-06-18'}: raise ValueError('Unsupported MCP protocol')
            self.rpc('notifications/initialized',notification=True)
        except BaseException: self.close(); raise
    def rpc(self,method,params=None,notification=False):
        with self.lock:
            self.sequence+=1; message={'jsonrpc':'2.0','method':method,'params':params or {}}
            if not notification: message['id']=self.sequence
            self.process.stdin.write(json.dumps(message)+'\n'); self.process.stdin.flush()
            if notification: return {}
            deadline=time.monotonic()+30
            while time.monotonic()<deadline:
                response=self.messages.get(timeout=max(.01,deadline-time.monotonic()))
                if 'method' in response:
                    if 'id' in response:
                        self.process.stdin.write(json.dumps({'jsonrpc':'2.0','id':response['id'],'error':{'code':-32601,'message':'Client callbacks not supported'}})+'\n'); self.process.stdin.flush()
                    continue
                if response.get('id')!=self.sequence and not response.get('error'): continue
                if response.get('error'): raise ValueError(str(response['error'])[:1000])
                return response.get('result',{})
            raise TimeoutError('Filesystem MCP response timed out')
    def close(self):
        try: os.killpg(self.process.pid,signal.SIGTERM)
        except ProcessLookupError: pass
        try: self.process.wait(timeout=2)
        except subprocess.TimeoutExpired: os.killpg(self.process.pid,signal.SIGKILL); self.process.wait()
        self.reader.join(1)
        self.process.stdin.close(); self.process.stdout.close()

class Engine:
    def __init__(self,root,mcp_command):
        self.root=Path(root).resolve(); self.root.mkdir(parents=True,exist_ok=True)
        self.mcp_command,self.sessions,self.mcp,self.lock=mcp_command,{},{},threading.Lock()
    def path(self,value='.'):
        candidate=Path(value); target=(self.root/candidate).resolve() if not candidate.is_absolute() else candidate.resolve()
        if not target.is_relative_to(self.root): raise ValueError('Path outside Termux project root')
        return target
    def call(self,owner,action,args):
        if not isinstance(owner,str) or not owner or len(owner)>80: raise ValueError('Invalid owner')
        if action=='status': return {'status':'ready','protocol':1,'root':str(self.root),'backend':'termux'}
        if action=='exec_command':
            command=args['command']
            if not isinstance(command,str) or len(command)>64000: raise ValueError('Invalid command')
            with self.lock:
                if len(self.sessions)>=32:
                    for identifier,session in list(self.sessions.items()):
                        if session.process.poll() is not None:
                            session.stop(); del self.sessions[identifier]
                            if len(self.sessions)<32: break
                if len(self.sessions)>=32: raise ValueError('Too many active sessions')
                session=Session(owner,command,self.path(args.get('workdir','.')),bool(args.get('tty',False))); self.sessions[session.id]=session
            return session.output(wait=args.get('yield_time_ms',1000))
        if action in {'read_output','write_stdin','terminate_process'}:
            session=self.sessions.get(args['session_id'])
            if session is None or session.owner!=owner: raise ValueError('Unknown session for this conversation')
            if action=='write_stdin':
                data=args.get('chars','').encode()
                if len(data)>64000: raise ValueError('Input too large')
                descriptor=session.input.fileno(); os.set_blocking(descriptor,False)
                end=time.monotonic()+3; offset=0
                while offset<len(data):
                    if time.monotonic()>=end: raise TimeoutError('stdin is not being consumed')
                    if select.select([], [descriptor], [], .1)[1]:
                        try: offset+=os.write(descriptor,data[offset:])
                        except BlockingIOError: pass
            if action=='terminate_process': session.stop()
            return session.output(args.get('cursor',0),args.get('yield_time_ms',0))
        if action in {'filesystem_tools','filesystem_call'}:
            with self.lock:
                if owner not in self.mcp:
                    if len(self.mcp)>=8: raise ValueError('Too many MCP clients')
                    self.mcp[owner]=FilesystemMCP(self.mcp_command,self.root)
                client=self.mcp[owner]
            if action=='filesystem_tools': return client.rpc('tools/list')
            name,arguments=args['name'],dict(args.get('arguments',{}))
            for key in ('path','source','destination'):
                if key in arguments: arguments[key]=str(self.path(arguments[key]))
            if 'paths' in arguments: arguments['paths']=[str(self.path(p)) for p in arguments['paths']]
            return client.rpc('tools/call',{'name':name,'arguments':arguments})
        if action=='github_api':
            endpoint=args['endpoint']; method=args.get('method','GET').upper()
            if not isinstance(endpoint,str) or endpoint.startswith(('-', '/')) or '://' in endpoint or len(endpoint)>2000: raise ValueError('Use a relative GitHub API endpoint')
            if method not in {'GET','POST','PATCH','PUT','DELETE'}: raise ValueError('Invalid GitHub API method')
            argv=['gh','api','--hostname','github.com','--method',method,endpoint]
            data=None
            if 'body' in args: argv+=['--input','-']; data=json.dumps(args['body'])
            result=subprocess.run(argv,input=data,text=True,cwd=self.root,capture_output=True,timeout=30)
            output=result.stdout[:512000]
            try: value=json.loads(output)
            except ValueError: value=output
            return {'status':'completed' if result.returncode==0 else 'failed','exit_code':result.returncode,'data':value,'stderr':result.stderr[:10000],'truncated':len(result.stdout)>512000}
        if action=='apply_patch':
            diff=args['patch']
            if len(diff)>500000: raise ValueError('Patch too large')
            cwd=self.path(args.get('workdir','.'))
            check=subprocess.run(['git','apply','--check','--'],input=diff,text=True,cwd=cwd,capture_output=True,timeout=20)
            result=check if check.returncode else subprocess.run(['git','apply','--'],input=diff,text=True,cwd=cwd,capture_output=True,timeout=20)
            return {'status':'completed' if result.returncode==0 else 'failed','exit_code':result.returncode,'stdout':result.stdout[:64000],'stderr':result.stderr[:64000]}
        if action=='close_owner':
            with self.lock:
                for identifier,session in list(self.sessions.items()):
                    if session.owner==owner: session.stop(); del self.sessions[identifier]
                if owner in self.mcp: self.mcp.pop(owner).close()
            return {'status':'closed'}
        raise ValueError('Unknown Termux operation')
    def close(self):
        for session in self.sessions.values(): session.stop()
        for client in self.mcp.values(): client.close()

def server(engine,token,port):
    class Handler(BaseHTTPRequestHandler):
        def setup(self):
            super().setup(); self.connection.settimeout(45)
        def log_message(self,*args): pass
        def do_POST(self):
            if self.path!='/rpc' or self.headers.get('Origin') or not hmac.compare_digest(self.headers.get('Authorization',''),'Bearer '+token): self.send_error(403); return
            try:
                size=int(self.headers.get('Content-Length','0'))
                if not 0<size<=1000000: raise ValueError('Invalid request size')
                data=json.loads(self.rfile.read(size)); result=engine.call(data['owner'],data['action'],data.get('arguments',{})); response={'ok':True,'result':result}
            except Exception as error: response={'ok':False,'error':str(error)[:2000]}
            body=json.dumps(response).encode(); self.send_response(200); self.send_header('Content-Type','application/json'); self.send_header('Content-Length',str(len(body))); self.end_headers(); self.wfile.write(body)
    return ThreadingHTTPServer(('127.0.0.1',port),Handler)

if __name__=='__main__':
    parser=argparse.ArgumentParser(); parser.add_argument('--config',required=True)
    config=json.loads(Path(parser.parse_args().config).read_text()); engine=Engine(config['root'],config['mcp_command']); http=server(engine,config['token'],config['port'])
    def stop(*_): raise KeyboardInterrupt()
    signal.signal(signal.SIGTERM,stop)
    try: http.serve_forever()
    except KeyboardInterrupt: pass
    finally: http.server_close(); engine.close()
