package io.musab.arabicterminal;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Owns native PTY processes independently of MainActivity.
 * A visible foreground-service notification keeps the user's interactive work
 * eligible to continue when the UI is not visible. Android can still stop it
 * due to battery restrictions, force-stop, reboot or resource pressure.
 */
public final class TerminalService extends Service {
    private static final String CHANNEL="arabic_terminal_sessions";
    private static final int NOTIFICATION=6305;
    public static final String ACTION_STOP="io.musab.arabicterminal.STOP";
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ArrayList<Session> sessions=new ArrayList<>();
    private final LocalBinder binder=new LocalBinder();
    private Runnable listener;
    private final ArrayList<Runnable> observers=new ArrayList<>();
    private com.musabgpt.m4.runtime.M4BridgeServer bridge;
    private Session selected;
    private int sequence=1;
    public final class LocalBinder extends Binder {
        public TerminalService getService(){return TerminalService.this;}
    }
    public static final class Session {
        final Object mutex=new Object(),pendingLock=new Object();
        final StringBuilder pending=new StringBuilder();
        final TerminalScreen display=new TerminalScreen();
        final ExecutorService writer=Executors.newSingleThreadExecutor(r->{
            Thread thread=new Thread(r,"terminal-write");
            thread.setDaemon(true);
            return thread;
        });
        final long handle;
        final String name;
        final boolean root,linux;
        volatile boolean finished;
        boolean flushScheduled;
        Session(long h,String n,boolean root,boolean linux){
            handle=h;name=n;this.root=root;this.linux=linux;
        }
        public String getName(){return name;}
        public boolean isLinux(){return linux;}
        public boolean isFinished(){return finished;}
        public TerminalScreen.Frame frame(){return display.frame();}
    }
    @Override public void onCreate(){
        super.onCreate();
        NotificationManager manager=getSystemService(NotificationManager.class);
        if(manager!=null && Build.VERSION.SDK_INT>=26){
            NotificationChannel channel=new NotificationChannel(CHANNEL,
                "جلسات الطرفية",NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("إظهار جلسات لينكس التي تعمل في الخلفية");
            manager.createNotificationChannel(channel);
        }
    }
    // Retired as an Android service: M4 uses Termux RUN_COMMAND exclusively.
    // Kept only as transitional Java source while callsites are migrated.
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        stopSelf();
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent){return binder;}
    public List<Session> sessions(){return sessions;}
    public Session selected(){return selected;}
    public void select(Session s){if(sessions.contains(s)){selected=s;inform();}}
    public void setListener(Runnable callback){listener=callback;if(callback!=null)callback.run();}
    public void addListener(Runnable callback){if(!observers.contains(callback))observers.add(callback);callback.run();}
    public void removeListener(Runnable callback){observers.remove(callback);}
    private void inform(){
        if(listener!=null)listener.run();
        for(Runnable callback:new ArrayList<>(observers))callback.run();
    }
    public Session create(boolean root,boolean linux,int rows,int cols) throws IOException {
        if(sessions.size()>=6)throw new IOException("الحد الأقصى ست جلسات");
        if(linux && (!LinuxEnvironment.installed(this) || !LinuxEnvironment.runtimeAvailable(this)))
            throw new IOException("بيئة Alpine ليست جاهزة");
        if(linux && bridge==null){
            com.musabgpt.m4.runtime.M4BridgeServer candidate=
                new com.musabgpt.m4.runtime.M4BridgeServer(this);
            try{candidate.start();bridge=candidate;}
            catch(Exception failure){candidate.close();}
        }
        String libdir=getApplicationInfo().nativeLibraryDir;
        long handle=NativePty.start(root,getFilesDir().getAbsolutePath(),
            getCacheDir().getAbsolutePath(),rows,cols,
            linux?LinuxEnvironment.rootfs(this).getAbsolutePath():null,
            linux?libdir+"/libproot.so":null,
            linux?libdir+"/libproot-loader.so":null);
        if(handle==0)throw new IOException("فشل إنشاء جلسة PTY");
        Session session=new Session(handle,"جلسة "+sequence++,root,linux);
        session.display.resize(rows,cols);
        sessions.add(session);
        selected=session;
        inform(); startNotification();
        new Thread(()->readLoop(session),"arabic-pty-read-"+session.name).start();
        return session;
    }
    public void close(Session session){
        if(session==null || !sessions.remove(session))return;
        synchronized(session.mutex){
            if(!session.finished)NativePty.terminate(session.handle);
        }
        session.writer.shutdown();
        if(selected==session)selected=sessions.isEmpty()?null:sessions.get(sessions.size()-1);
        inform();startNotification();
    }
    public void resize(Session session,int rows,int cols){
        if(session==null)return;
        session.display.resize(rows,cols);
        synchronized(session.mutex){
            if(!session.finished)NativePty.resize(session.handle,rows,cols);
        }
    }
    /**
     * A single writer queue per PTY prevents copy-paste blocks from interleaving
     * with later keystrokes. Each newline is processed in order by the shell.
     */
    public void write(Session session,String text){
        if(session==null||session.finished||text==null||text.isEmpty())return;
        byte[] bytes=text.getBytes(StandardCharsets.UTF_8);
        try {
            session.writer.execute(()->{
                synchronized(session.mutex){
                    if(!session.finished)NativePty.write(session.handle,bytes,bytes.length);
                }
            });
        } catch(java.util.concurrent.RejectedExecutionException ignored){}
    }
    public void clear(Session session){
        if(session!=null){session.display.clear();inform();}
    }
    public void info(Session session,String message){
        if(session!=null)enqueue(session,"\r\n« "+message+" »\r\n");
    }
    private void enqueue(Session session,String text){
        synchronized(session.pendingLock){
            if(session.pending.length()>180000)
                session.pending.delete(0,session.pending.length()-120000);
            session.pending.append(text);
            if(session.flushScheduled)return;
            session.flushScheduled=true;
        }
        main.postDelayed(()->flush(session),35);
    }
    private void flush(Session session){
        String payload;
        synchronized(session.pendingLock){
            payload=session.pending.toString();
            session.pending.setLength(0);
            session.flushScheduled=false;
        }
        if(payload.isEmpty())return;
        session.display.append(payload);
        String reply=session.display.drainResponse();
        if(!reply.isEmpty())write(session,reply);
        if(selected==session)inform();
    }
    private void readLoop(Session s){
        try(Reader reader=new InputStreamReader(new InputStream(){
            @Override public int read()throws IOException {
                byte[] b=new byte[1];int n=read(b,0,1);
                return n<0?-1:b[0]&255;
            }
            @Override public int read(byte[] into,int off,int count)throws IOException {
                if(count==0)return 0;
                byte[] b=new byte[Math.min(count,4096)];
                int n=NativePty.read(s.handle,b);
                if(n<0)return -1;
                System.arraycopy(b,0,into,off,n);
                return n;
            }
        },StandardCharsets.UTF_8)){
            char[] c=new char[4096];int n;
            while((n=reader.read(c))>=0)
                if(n>0)enqueue(s,new String(c,0,n));
        } catch(IOException ex){
            enqueue(s,"\r\nتعذرت قراءة الطرفية: "+ex.getMessage()+"\r\n");
        } finally {
            synchronized(s.mutex){
                s.finished=true;
                NativePty.destroy(s.handle);
            }
            s.writer.shutdown();
            main.post(()->{if(selected==s)inform();});
        }
    }
    public void stopAll(){
        for(Session session:new ArrayList<>(sessions))close(session);
        sessions.clear();selected=null;
        inform();
    }
    @Override public void onDestroy(){
        stopAll();
        if(bridge!=null){bridge.close();bridge=null;}
        listener=null;
        observers.clear();
        super.onDestroy();
    }
}
