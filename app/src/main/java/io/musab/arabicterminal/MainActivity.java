package io.musab.arabicterminal;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.text.InputType;
import android.text.Selection;
import android.widget.Toast;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** واجهة الطرفية العربية: جلسات PTY متعددة، شاشة ألوان، وأوامر تعمل محليًا. */
public final class MainActivity extends Activity {
    private static final int BG=0xFF0B1422, PANEL=0xFF1C2A3C,
            ACCENT=0xFF54DCAD, FG=0xFFECF3FC, MUTED=0xFFB1C3D5;
    private static final int FILE_PICKER=2026, EXPORT_PICKER=2027, RESTORE_PICKER=2028, MAX_SESSIONS=6;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private List<TerminalService.Session> sessions=java.util.Collections.emptyList();
    private TerminalService.Session active;
    private TerminalService service;
    private boolean bound;
    private boolean preparingLinux;
    private final ServiceConnection connection=new ServiceConnection(){
        @Override public void onServiceConnected(ComponentName name,IBinder binder){
            service=((TerminalService.LocalBinder)binder).getService();
            bound=true;sessions=service.sessions();active=service.selected();
            service.setListener(()->{if(service!=null){active=service.selected();render();}});
            if(sessions.isEmpty())prepareLinux();
            else render();
        }
        @Override public void onServiceDisconnected(ComponentName name){
            service=null;bound=false;active=null;
            sessions=java.util.Collections.emptyList();
        }
    };
    private TextView console, status;
    private ScrollView viewport;
    private EditText entry;
    private Button modeButton;
    private int rows=24, columns=75, nextId=1;
    private boolean rawMode;
    private boolean useLinux=true;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        buildUi();
    }
    @Override protected void onStart(){
        super.onStart();
        Intent intent=new Intent(this,TerminalService.class);
        try{
            startForegroundService(intent);
            bindService(intent,connection,Context.BIND_AUTO_CREATE);
        }catch(Exception ex){
            Toast.makeText(this,"تعذر تشغيل الخدمة: "+ex.getMessage(),Toast.LENGTH_LONG).show();
        }
        if(Build.VERSION.SDK_INT>=33 &&
          checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=
              android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},810);
    }
    @Override protected void onStop(){
        if(bound&&service!=null){
            service.setListener(null);
            unbindService(connection);
            service=null;bound=false;
        }
        super.onStop();
    }
    private int dp(float n){return (int)(getResources().getDisplayMetrics().density*n+0.5f);}
    private TextView label(String s,int size,int color){
        TextView v=new TextView(this);
        v.setText(s);v.setTextColor(color);v.setTextSize(size);
        v.setGravity(Gravity.CENTER_VERTICAL|Gravity.RIGHT);
        return v;
    }
    private Button button(String s,View.OnClickListener action){
        Button b=new Button(this);
        b.setAllCaps(false);b.setText(s);b.setTextSize(11);b.setTextColor(FG);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(PANEL));
        b.setMinWidth(0);b.setMinimumWidth(0);b.setOnClickListener(action);
        return b;
    }
    private LinearLayout row(){
        LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);return l;
    }
    private void key(LinearLayout container,String title,String control){
        container.addView(button(title,v->write(active,control)));
    }
    private void buildUi(){
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF070A0F);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        root.setPadding(dp(5),dp(2),dp(5),dp(2));
        setContentView(root);
        LinearLayout header=row();
        status=label("Alpine Linux • تشغيل...",12,ACCENT);
        status.setGravity(Gravity.CENTER_VERTICAL|Gravity.LEFT);
        header.addView(status,new LinearLayout.LayoutParams(0,dp(34),1));
        header.addView(button("⋮",this::openMenu));
        root.addView(header);
        viewport=new ScrollView(this);
        viewport.setFillViewport(true);
        viewport.setBackgroundColor(0xFF070A0F);
        console=label("",14,FG);
        console.setTypeface(Typeface.MONOSPACE);
        console.setGravity(Gravity.LEFT|Gravity.TOP);
        console.setTextDirection(View.TEXT_DIRECTION_LTR);
        console.setPadding(dp(4),dp(4),dp(4),dp(4));
        console.setTextIsSelectable(true);
        viewport.addView(console);
        root.addView(viewport,new LinearLayout.LayoutParams(-1,0,1));
        viewport.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,oright,ob)->{
            int newCols=Math.max(20,Math.min(200,
                (int)((r-l-dp(12))/Math.max(7,console.getPaint().measureText("M")))));
            int newRows=Math.max(8,Math.min(100,(b-t-dp(12))/Math.max(1,console.getLineHeight())));
            if(newCols==columns&&newRows==rows)return;
            columns=newCols;rows=newRows;
            if(service!=null)for(TerminalService.Session session:sessions)
                service.resize(session,rows,columns);
            render();
        });
        HorizontalScrollView bar=new HorizontalScrollView(this);
        bar.setHorizontalScrollBarEnabled(false);
        LinearLayout keys=row();
        key(keys,"ESC","\u001b");key(keys,"TAB","\t");
        key(keys,"CTRL+A","\u0001");key(keys,"CTRL+E","\u0005");
        key(keys,"CTRL+L","\u000c");key(keys,"CTRL+R","\u0012");
        key(keys,"CTRL+C","\u0003");key(keys,"CTRL+D","\u0004");
        key(keys,"CTRL+Z","\u001a");
        key(keys,"↑","\u001b[A");key(keys,"↓","\u001b[B");
        key(keys,"←","\u001b[D");key(keys,"→","\u001b[C");
        key(keys,"↵","\r");key(keys,"⌫","\u007f");
        bar.addView(keys);root.addView(bar);
        LinearLayout input=row();
        input.setGravity(Gravity.CENTER_VERTICAL);
        TextView prompt=label("❯",20,ACCENT);
        prompt.setGravity(Gravity.CENTER);
        input.addView(prompt,new LinearLayout.LayoutParams(dp(28),dp(47)));
        entry=new EditText(this);
        entry.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        entry.setSingleLine(false);
        entry.setMaxLines(7);entry.setMinLines(1);entry.setMaxHeight(dp(175));
        entry.setHorizontallyScrolling(false);
        entry.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
        entry.setTextSize(15);
        entry.setTypeface(Typeface.MONOSPACE);
        entry.setTextColor(FG);entry.setHintTextColor(MUTED);
        entry.setBackgroundColor(0xFF121C2A);
        entry.setPadding(dp(8),0,dp(8),0);
        entry.setHint("أمر لينكس أو أمر عربي...");
        entry.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        entry.setImeOptions(EditorInfo.IME_ACTION_SEND|EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        entry.setOnEditorActionListener((v,id,event)->{
            if(id==EditorInfo.IME_ACTION_SEND||(event!=null &&
                event.getKeyCode()==KeyEvent.KEYCODE_ENTER &&
                event.getAction()==KeyEvent.ACTION_DOWN)){
                submit();return true;
            }
            return false;
        });
        input.addView(entry,new LinearLayout.LayoutParams(0,-2,1));
        input.addView(button("لصق",v->pasteClipboard()));
        input.addView(button("▶",v->submit()));
        root.addView(input);
        if(Build.VERSION.SDK_INT>=30){
            root.setOnApplyWindowInsetsListener((view,insets)->{
                int ime=insets.getInsets(android.view.WindowInsets.Type.ime()).bottom;
                int nav=insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom;
                int top=insets.getInsets(android.view.WindowInsets.Type.statusBars()).top;
                root.setPadding(dp(5),top+dp(2),dp(5),Math.max(ime,nav)+dp(2));
                return insets;
            });
        }
    }
    private void openMenu(View anchor){
        android.widget.PopupMenu popup=new android.widget.PopupMenu(this,anchor);
        String[] names={"لينكس: جلسة جديدة","صدفة أندرويد","صلاحيات Root",
            "الجلسة السابقة","إغلاق الجلسة","تبديل وضع الإدخال",
            "صلاحيات التحكم بالهاتف","استيراد ملف","تصدير مشاريع /root","استعادة ZIP إلى /root","نسخ الشاشة","لصق الحافظة",
            "نسخ الأمر المكتوب","تحديث العرض","إدارة حزم Alpine","مساعدة","🤖 وكيل البرمجة"};
        for(int i=0;i<names.length;i++)popup.getMenu().add(0,i+1,i,names[i]);
        popup.setOnMenuItemClickListener(item->{
            switch(item.getItemId()){
                case 1:useLinux=true;createSession(false);break;
                case 2:useLinux=false;createSession(false);break;
                case 3:confirmRoot();break;
                case 4:previousSession();break;
                case 5:closeActive();break;
                case 6:rawMode=!rawMode;entry.setHint(rawMode?
                    "إرسال مباشر للبرنامج التفاعلي":"أمر لينكس أو أمر عربي...");break;
                case 7:phoneControlSetup();break;
                case 8:selectDocument();break;
                case 9:exportRoot();break;
                case 10:chooseRestore();break;
                case 11:copyText();break;
                case 12:pasteClipboard();break;
                case 13:copyInput();break;
                case 14:
                    CharSequence current=console.getText();
                    if(current instanceof android.text.Spannable)
                        Selection.removeSelection((android.text.Spannable)current);
                    render();break;
                case 15:openPackageManager();break;
                case 16:help();break;
                case 17:openAgentMenu();break;
                default:return false;
            }
            return true;
        });
        popup.show();
    }
    private void prepareLinux(){
        if(service==null||preparingLinux)return;
        preparingLinux=true;
        status.setText("تهيئة Alpine Linux المحلية...");
        new Thread(()->{
            String error=null;
            try{LinuxEnvironment.install(getApplicationContext());}
            catch(Exception exception){error=exception.getMessage();}
            final String failure=error;
            ui.post(()->{
                preparingLinux=false;
                if(isFinishing()||isDestroyed()||service==null)return;
                if(!sessions.isEmpty()){render();return;}
                if(failure!=null){
                    useLinux=false;
                    createSession(false);
                    notice("فشل إعداد لينكس: "+failure+
                        "\nهذه جلسة أندرويد احتياطية وليست توزيعة Linux.");
                }else{
                    useLinux=true;
                    createSession(false);
                    notice("Alpine Linux: اكتب cat /etc/os-release أو apk --version");
                }
            });
        },"linux-offline-installer").start();
    }
    private void createSession(boolean root){
        if(service==null){notice("خدمة الطرفية غير جاهزة");return;}
        try{
            boolean linux=!root&&useLinux&&LinuxEnvironment.installed(this)
                && LinuxEnvironment.runtimeAvailable(this);
            if(!root&&useLinux&&!linux){notice("Alpine غير جاهز");return;}
            active=service.create(root,linux,rows,columns);
            render();
        }catch(Exception|UnsatisfiedLinkError ex){notice("تعذر فتح الجلسة: "+ex.getMessage());}
    }
    private void previousSession(){
        if(service==null||sessions.isEmpty())return;
        int current=sessions.indexOf(active);
        service.select(sessions.get((current+sessions.size()-1)%sessions.size()));
        active=service.selected();render();
    }
    private void closeActive(){
        if(service==null||active==null)return;
        service.close(active);
        active=service.selected();
        if(active==null)createSession(false);
        else render();
    }
    private void write(TerminalService.Session session,String bytes){
        if(service!=null)service.write(session,bytes);
    }
    private void enqueue(TerminalService.Session session,String text){
        if(service!=null)service.info(session,text);
    }
    private void pasteClipboard(){
        ClipboardManager clipboard=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
        if(clipboard==null||!clipboard.hasPrimaryClip()){notice("حافظة الهاتف فارغة");return;}
        ClipData data=clipboard.getPrimaryClip();
        if(data==null||data.getItemCount()==0)return;
        CharSequence value=data.getItemAt(0).coerceToText(this);
        if(value==null||value.length()==0)return;
        if(value.length()>250000){notice("النص المنسوخ كبير جدًا");return;}
        entry.getText().insert(Math.max(0,entry.getSelectionStart()),value);
        entry.requestFocus();
    }
    private void copyValue(String value){
        ClipboardManager clipboard=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
        if(clipboard!=null)clipboard.setPrimaryClip(
            ClipData.newPlainText("الطرفية العربية",value));
        Toast.makeText(this,"تم النسخ إلى الحافظة",Toast.LENGTH_SHORT).show();
    }
    private void copyInput(){
        String value=entry.getText().toString();
        if(value.isEmpty()){notice("مربع الإدخال فارغ");return;}
        int from=entry.getSelectionStart(),to=entry.getSelectionEnd();
        if(from>=0&&to>from)copyValue(value.substring(from,to));
        else copyValue(value);
    }
    private void submit(){
        if(active==null)return;
        String text=entry.getText().toString();
        if(text.trim().isEmpty())return;
        entry.setText("");
        if(rawMode){write(active,text);return;}
        if(text.indexOf('\n')>=0 || text.indexOf('\r')>=0){
            // The persistent shell runs the whole pasted script sequentially.
            // Preserve here-documents, loops and 'cd' in one PTY session.
            write(active,CommandBatch.toShell(text));
            return;
        }
        ArabicCommandRouter.Parsed parsed=ArabicCommandRouter.parse(text);
        switch(parsed.kind){
            case SHELL:write(active,parsed.command+"\n");break;
            case CONFIRM_DELETE:
                new AlertDialog.Builder(this).setTitle("تأكيد الحذف")
                    .setMessage("سيُحذف العنصر: "+parsed.description)
                    .setNegativeButton("إلغاء",null)
                    .setPositiveButton("حذف",(d,w)->write(active,parsed.command+"\n")).show();
                break;
            case PACKAGES:openPackageManager();break;
            case PACKAGE_UPDATE:runPackage(PackageCommands.Action.UPDATE,null);break;
            case PACKAGE_INSTALLED:runPackage(PackageCommands.Action.INSTALLED,null);break;
            case PACKAGE_SEARCH:runPackage(PackageCommands.Action.SEARCH,parsed.command);break;
            case PACKAGE_DETAILS:runPackage(PackageCommands.Action.DETAILS,parsed.command);break;
            case PACKAGE_INSTALL:runPackage(PackageCommands.Action.INSTALL,parsed.command);break;
            case PACKAGE_REMOVE:runPackage(PackageCommands.Action.REMOVE,parsed.command);break;
            case HELP:help();break;
            case PHONE:notice("الشركة: "+Build.MANUFACTURER+"\nالطراز: "+Build.MODEL+
                    "\nأندرويد: "+Build.VERSION.RELEASE+"\nواجهة API: "+Build.VERSION.SDK_INT);break;
            case BATTERY:battery();break;
            case MEMORY:memory();break;
            case STORAGE:storage();break;
            case IDENTITY:write(active,"id\n");break;
            case SETTINGS:startActivity(new Intent(Settings.ACTION_SETTINGS));break;
            case CHOOSE_FILE:selectDocument();break;
            case CLEAR:if(service!=null)service.clear(active);render();break;
            case ROOT:confirmRoot();break;
            case LIST_APPS:case OPEN_APP:case OPEN_SYSTEM_APP:case OPEN_SETTINGS_PAGE:
            case OPEN_URL:case DIAL:case SHARE:case ACCESSIBILITY_SETTINGS:
            case HOME:case BACK:case RECENTS:case NOTIFICATIONS:case QUICK_SETTINGS:
            case SWIPE_UP:case SWIPE_DOWN:case TAP_TEXT:case TAP_POINT:
                notice(PhoneController.run(this,parsed));break;
            case NETWORK_DIAG:
                notice("جارٍ اختبار اتصال أندرويد وDNS وشهادات HTTPS...");
                new Thread(()->{
                    String report=NetworkDiagnostics.check(getApplicationContext());
                    ui.post(()->notice(report));
                },"network-diagnostics").start();
                break;
            case LINUX_DIAG:
                if(!active.linux){
                    notice("فحص لينكس متاح في جلسة Alpine Linux، وليس Android Shell.");
                } else {
                    notice("يبدأ الآن فحص DNS والاتصال والكتابة داخل Alpine، دون تعديل الإعدادات.");
                    write(active,LinuxDiagnostics.shellCommand());
                }
                break;
            default:notice("الأمر غير معروف. اكتب «مساعدة» للاطلاع على الدليل.");
        }
    }
    private void render(){
        if(console==null)return;
        if(active==null){console.setText("");status.setText("لا توجد جلسة");return;}
        status.setText((active.linux?"Alpine Linux":(active.root?"Android Root":"Android Shell"))+
                " • "+active.name+(active.finished?" • متوقفة":""));
        // Do not replace TextView text while Android's selection handles are
        // active. The service keeps buffering output until selection is gone.
        CharSequence selectedText=console.getText();
        int selectionStart=Selection.getSelectionStart(selectedText);
        int selectionEnd=Selection.getSelectionEnd(selectedText);
        if(selectionStart>=0 && selectionEnd>selectionStart)return;
        TerminalScreen.Frame frame=active.display.frame();
        SpannableStringBuilder shown=new SpannableStringBuilder(frame.text);
        int length=frame.text.length();
        for(int i=0;i<length;){
            int fg=frame.foreground[i],bg=frame.background[i],next=i+1;
            while(next<length && frame.foreground[next]==fg && frame.background[next]==bg)next++;
            if(fg!=TerminalScreen.DEFAULT_FG)
                shown.setSpan(new ForegroundColorSpan(0xFF000000|fg),i,next,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if(bg!=TerminalScreen.DEFAULT_BG)
                shown.setSpan(new BackgroundColorSpan(0xFF000000|bg),i,next,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            i=next;
        }
        // Draw a visible block cursor for Bash, nano, vim and tmux. The
        // terminal parser tracks DEC private-mode ?25l/?25h visibility.
        if(frame.cursorIndex>=0 && frame.cursorIndex<length){
            int from=frame.cursorIndex, to=from+1;
            if(Character.isHighSurrogate(frame.text.charAt(from)) &&
                    to<length && Character.isLowSurrogate(frame.text.charAt(to)))to++;
            shown.setSpan(new BackgroundColorSpan(ACCENT),from,to,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            shown.setSpan(new ForegroundColorSpan(Color.BLACK),from,to,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        console.setText(shown);
        if(viewport.getScrollY()+viewport.getHeight()>=console.getHeight()-dp(80))
            viewport.post(()->viewport.fullScroll(View.FOCUS_DOWN));
    }
    private void notice(String content){
        if(service!=null&&active!=null)service.info(active,content);
        else Toast.makeText(this,content,Toast.LENGTH_LONG).show();
    }
    private void battery(){
        Intent i=registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if(i==null){notice("البطارية غير متاحة");return;}
        int level=i.getIntExtra(BatteryManager.EXTRA_LEVEL,-1);
        int scale=i.getIntExtra(BatteryManager.EXTRA_SCALE,100);
        boolean charging=i.getIntExtra(BatteryManager.EXTRA_STATUS,0)==BatteryManager.BATTERY_STATUS_CHARGING;
        notice("البطارية: "+(scale>0?level*100/scale:level)+"% — الشحن: "+(charging?"نعم":"لا"));
    }
    private void memory(){
        ActivityManager manager=(ActivityManager)getSystemService(Context.ACTIVITY_SERVICE);
        if(manager==null){notice("الذاكرة غير متاحة");return;}
        ActivityManager.MemoryInfo info=new ActivityManager.MemoryInfo();
        manager.getMemoryInfo(info);
        notice("الذاكرة الكلية: "+gb(info.totalMem)+" غيغابايت\nالمتاحة: "+gb(info.availMem)+" غيغابايت");
    }
    private String gb(long n){return String.format(Locale.US,"%.2f",n/1073741824.0);}
    private void storage(){
        StatFs disk=new StatFs(getFilesDir().getAbsolutePath());
        notice("القسم: "+gb(disk.getTotalBytes())+" غيغابايت\nالمتاح: "+
                gb(disk.getAvailableBytes())+" غيغابايت\nمسار العمل: "+getFilesDir());
    }
    private void confirmRoot(){
        new AlertDialog.Builder(this).setTitle("طلب صلاحيات الجذر")
            .setMessage("الجذر غير مضمون؛ يجب أن يكون su متاحًا وأن يوافق مدير الجذر. "+
                "قد تتمكن برامج الجذر من تغيير ملفات النظام. هل تريد بدء جلسة جديدة؟")
            .setNegativeButton("إلغاء",null)
            .setPositiveButton("طلب الجذر",(d,w)->createSession(true)).show();
    }
    private void phoneControlSetup(){
        new AlertDialog.Builder(this)
            .setTitle("التحكم بالهاتف")
            .setMessage("يمكنك فتح التطبيقات من الطرفية مباشرة بدون أذونات خاصة. "+
                "أما الرجوع والرئيسية والنقر والتمرير فوق التطبيقات الأخرى فتحتاج تفعيل "+
                "خدمة «تحكم الطرفية العربية» من إعدادات إمكانية الوصول. "+
                "ستظهر لوحة عائمة صغيرة تستطيع إخفاءها. الخدمة لا تحفظ محتوى الشاشة ولا ترسل بيانات للإنترنت. "+
                "فعّلها فقط إذا رغبت ويمكن إيقافها في أي وقت.")
            .setNegativeButton("إلغاء",null)
            .setPositiveButton("إعدادات إمكانية الوصول",(dialog,which)->
                notice(PhoneController.run(this,ArabicCommandRouter.parse("تفعيل التحكم"))))
            .show();
    }

    /** Run package operations only in the existing Alpine Linux session. */
    private boolean packageReady(){
        if(service==null||active==null||active.finished||!active.linux){
            notice("إدارة الحزم متاحة فقط داخل جلسة Alpine Linux نشطة.");
            return false;
        }
        if(!new File(LinuxEnvironment.rootfs(this),"usr/local/bin/apk-v2").isFile()){
            notice("apk-v2 غير موجود. استخدم الإصدار الذي يتضمن مدير الحزم المتوافق.");
            return false;
        }
        return true;
    }
    private void runPackage(PackageCommands.Action action,String name){
        if(!packageReady())return;
        final String command;
        try{command=PackageCommands.command(action,name);}
        catch(IllegalArgumentException ex){
            Toast.makeText(this,ex.getMessage(),Toast.LENGTH_LONG).show();
            return;
        }
        // Avoid targeting a different session if the user switches tabs before
        // confirming the dialog.
        final TerminalService.Session target=active;
        if(action==PackageCommands.Action.INSTALL||action==PackageCommands.Action.REMOVE){
            String verb=action==PackageCommands.Action.INSTALL?"تثبيت":"حذف";
            String warning=action==PackageCommands.Action.REMOVE?
                "قد تُحذف أيضًا تبعيات لم تعد مطلوبة. ":"";
            new AlertDialog.Builder(this)
                .setTitle("تأكيد "+verb+" حزمة")
                .setMessage("الحزمة: "+PackageCommands.checkedName(name)+"\n"+warning+
                    "سيجري فحص محاكاة وإنشاء نسخة احتياطية من قاعدة الحزم قبل التنفيذ.\n"+
                    "اخرج من nano أو أي برنامج تفاعلي قبل الموافقة.")
                .setNegativeButton("إلغاء",null)
                .setPositiveButton(verb,(d,w)->write(target,command))
                .show();
        }else{
            write(target,command);
        }
    }
    private void openPackageManager(){
        if(!packageReady())return;
        LinearLayout panel=new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12),dp(7),dp(12),dp(5));
        TextView guide=label("مدير apk-v2\n"+
            "البحث والتثبيت والحذف، مع محاكاة ونسخة احتياطية قبل أي تعديل. "+
            "الترقية الشاملة غير متاحة. أغلق البرامج التفاعلية قبل الاستخدام.",12,MUTED);
        panel.addView(guide);
        EditText packageName=new EditText(this);
        packageName.setSingleLine(true);
        packageName.setTextDirection(View.TEXT_DIRECTION_LTR);
        packageName.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        packageName.setHint("مثال: tree أو python3");
        panel.addView(packageName,new LinearLayout.LayoutParams(-1,dp(55)));
        AlertDialog dialog=new AlertDialog.Builder(this)
            .setTitle("إدارة حزم Alpine")
            .setView(panel)
            .setNegativeButton("إغلاق",null)
            .create();
        LinearLayout inspect=row();
        panel.addView(inspect);
        packageButton(inspect,"بحث",dialog,packageName,PackageCommands.Action.SEARCH);
        packageButton(inspect,"تفاصيل",dialog,packageName,PackageCommands.Action.DETAILS);
        packageButton(inspect,"المثبتة",dialog,packageName,PackageCommands.Action.INSTALLED);
        LinearLayout change=row();
        panel.addView(change);
        packageButton(change,"تحديث",dialog,packageName,PackageCommands.Action.UPDATE);
        packageButton(change,"تثبيت",dialog,packageName,PackageCommands.Action.INSTALL);
        packageButton(change,"حذف",dialog,packageName,PackageCommands.Action.REMOVE);
        dialog.show();
    }
    private void packageButton(LinearLayout parent,String title,AlertDialog dialog,
                               EditText input,PackageCommands.Action action){
        Button b=button(title,v->{
            String name=input.getText().toString();
            try{PackageCommands.command(action,name);}
            catch(IllegalArgumentException ex){input.setError(ex.getMessage());return;}
            dialog.dismiss();
            runPackage(action,name);
        });
        parent.addView(b,new LinearLayout.LayoutParams(0,dp(47),1));
    }

    private void openAgentMenu(){
        if(active==null||!active.linux){
            notice("افتح جلسة Alpine Linux أولاً لاستخدام الوكيل.");return;
        }
        String[] entries={"فحص بيئة الوكيل","الأدوات المثبتة والإضافات","حالة المهمة الحالية","تعليمات البدء"};
        new AlertDialog.Builder(this).setTitle("🤖 Term Agent — بيئة البرمجة")
            .setItems(entries,(dialog,index)->{
                switch(index){
                    case 0:write(active,"term-agent doctor\n");break;
                    case 1:write(active,"term-agent tools\n");break;
                    case 2:write(active,"term-agent status\n");break;
                    case 3:
                        notice("لبدء مشروع:\nmkdir -p /root/projects/demo\ncd /root/projects/demo\nterm-agent init"+
                        "\nterm-agent check-add 'python3 -V'"+
                        "\nثم اضبط TERM_AGENT_API_BASE وTERM_AGENT_MODEL"+
                        "\nوشغّل: term-agent run 'هدفك' --allow-exec"+
                        "\nيمكنك إضافة Pi وRalph عبر term-agent-addons help");
                        break;
                }
            }).setNegativeButton("إغلاق",null).show();
    }
    private void help(){
        new AlertDialog.Builder(this).setTitle("دليل الطرفية العربية")
            .setMessage("الملفات — قائمة الملفات\nأين أنا — مجلد العمل\nالهاتف — معلومات الهاتف"+
                "\nالبطارية — نسبة الشحن\nالرام — الذاكرة\nالتخزين — المساحة"+
                "\nأنشئ مجلد اسم\nأنشئ ملف اسم\nاذهب إلى مسار\nاقرأ ملف"+
                "\nاحذف ملف اسم — مع تأكيد\nالهوية — صلاحيات العملية"+
                "\nالعمليات، النواة، الوقت، مسح، روت، استيراد ملف"+
                "\n\nالتحكم بالجوال: افتح واتساب، افتح يوتيوب، شغل تلغرام، اعرض التطبيقات."+
                "\nالكاميرا، الصور، المتصفح، افتح الواي فاي، افتح البلوتوث."+
                "\nافتح رابط https://example.org، اتصل بالرقم 12345، شارك النص مرحبا."+
                "\nأوامر الواجهة (بعد تفعيل خدمة التحكم): الرئيسية، رجوع، التطبيقات الأخيرة، الإشعارات، مرر للأعلى، مرر للأسفل، اضغط على إرسال، اضغط عند 100 250."+
                "\nاكتب «تفعيل التحكم» لإظهار إعدادات الخدمة. ستظهر لوحة عائمة فوق التطبيقات الأخرى."+

                "\n\nمدير الحزم: قائمة إعداد حزم Alpine، البحث والتثبيت والحذف."+
                "\nأوامر: تحديث الحزم، الحزم المثبتة، ابحث عن حزمة tree، ثبت حزمة tree، احذف حزمة tree."+
                "\nيستخدم التطبيق apk-v2 المتوافق. لا تنفذ ترقية شاملة للحزم حاليًا."+ 
                "\nللتأكد من النظام: cat /etc/os-release. الجذر داخل PRoot لا يمنح Root للجهاز."+
                "\nفحص الشبكة: اختبار أندرويد. فحص لينكس: تشخيص الاتصال من Alpine."+ 
                "\n\nالأوامر الإنجليزية مثل ls وpython تُرسل مباشرة للصدفة إن توفرت."+
                "\n! يرسل الأمر كما هو.\nالوضع التفاعلي يرسل النص بلا Enter."+
                "\nأزرار التحكم ترسل أحرفًا خامًا مباشرة إلى PTY."+
                "\nلا تملك الطرفية صلاحية تجاوز عزل أندرويد.")
            .setPositiveButton("مفهوم",null).show();
    }
    /**
     * A SAF-created ZIP survives app removal and debug-signature changes.
     * Only Alpine /root is exported, not system packages or Android app data.
     */
    private void exportRoot(){
        File home=new File(LinuxEnvironment.rootfs(this),"root");
        if(!home.isDirectory()){
            Toast.makeText(this,"مجلد Alpine /root غير موجود",Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle("تصدير مشاريع Linux")
            .setMessage("سيتم إنشاء ZIP داخل مجلد تختاره بنفسك، خارج بيانات التطبيق. "+
                "أوقف أي برامج تعدّل الملفات أثناء النسخ لضمان اتساق المحتويات. "+
                "الروابط الرمزية لن تُنسخ لأسباب أمنية. "+
                "ملف ZIP غير مشفّر وقد يحتوي مفاتيح SSH أو بيانات خاصة؛ احفظه في مكان آمن. "+
                "احتفظ بالنسخة قبل حذف التطبيق؛ تحديث APK لا يتطلب حذف بياناته.")
            .setNegativeButton("إلغاء",null)
            .setPositiveButton("اختيار مكان الحفظ",(d,w)->{
                Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/zip");
                String stamp=new java.text.SimpleDateFormat("yyyyMMdd-HHmm",
                    Locale.US).format(new java.util.Date());
                intent.putExtra(Intent.EXTRA_TITLE,"ArabicTerminal-root-"+stamp+".zip");
                try{startActivityForResult(intent,EXPORT_PICKER);}
                catch(android.content.ActivityNotFoundException ex){
                    Toast.makeText(this,"لا يوجد تطبيق لإدارة الملفات",Toast.LENGTH_LONG).show();
                }
            }).show();
    }
    private void writeRootBackup(Uri uri){
        final File home=new File(LinuxEnvironment.rootfs(getApplicationContext()),"root");
        new Thread(()->{
            String result;
            try{
                OutputStream out=getContentResolver().openOutputStream(uri,"w");
                if(out==null)throw new IOException("تعذر فتح ملف الوجهة");
                RootArchive.Summary summary=RootArchive.export(home.toPath(),out);
                result="تم تصدير /root: "+summary.files+" ملف، "+
                    summary.directories+" مجلد، "+summary.bytes+" بايت."+
                    (summary.skipped>0?" تم تخطي "+summary.skipped+
                        " رابط أو ملف خاص.":"");
            }catch(Exception ex){
                result="فشل تصدير /root: "+ex.getMessage()+
                    ". قد يكون الملف الناتج غير مكتمل؛ لا تستخدمه كنسخة احتياطية.";
            }
            final String message=result;
            ui.post(()->{
                if(!isDestroyed()){
                    Toast.makeText(this,message,Toast.LENGTH_LONG).show();
                    if(service!=null&&active!=null)service.info(active,message);
                }
            });
        },"root-archive-export").start();
    }
    /** Import a previous ZIP into an isolated recovery folder; never overwrite /root. */
    private void chooseRestore(){
        if(!LinuxEnvironment.installed(this)){
            Toast.makeText(this,"جهّز Alpine Linux قبل الاستعادة",Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle("استعادة ملفات Linux")
            .setMessage("اختر ملف ZIP محفوظًا سابقًا. سيتم فحص مساراته واستعادته "+
                "في مجلد جديد تحت /root/Recovered-* دون استبدال أي ملفات موجودة. "+
                "افتح المجلد وراجع الملفات قبل نقلها إلى مشاريعك الأصلية. "+
                "لا تستعد أرشيفات من جهات غير موثوقة.")
            .setNegativeButton("إلغاء",null)
            .setPositiveButton("اختيار ZIP",(d,w)->{
                Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("application/zip");
                i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{
                    "application/zip","application/x-zip-compressed","application/octet-stream"
                });
                try{startActivityForResult(i,RESTORE_PICKER);}
                catch(android.content.ActivityNotFoundException ex){
                    Toast.makeText(this,"لا يوجد تطبيق لاختيار الملفات",Toast.LENGTH_LONG).show();
                }
            }).show();
    }
    private void restoreRootBackup(Uri uri){
        final Context app=getApplicationContext();
        final TerminalService.Session target=active;
        Toast.makeText(this,"جارٍ فحص واستعادة الأرشيف...",Toast.LENGTH_LONG).show();
        new Thread(()->{
            String result;
            try(InputStream source=getContentResolver().openInputStream(uri)){
                if(source==null)throw new IOException("تعذر قراءة أرشيف ZIP");
                File home=new File(LinuxEnvironment.rootfs(app),"root");
                RootArchiveImporter.Result restored=
                    RootArchiveImporter.restore(source,home.toPath());
                result="نجحت الاستعادة: "+restored.files+" ملف و"+
                    restored.directories+" مجلد في /root/"+
                    restored.destination.getFileName().toString();
            }catch(Exception ex){
                result="فشلت الاستعادة بأمان: "+ex.getMessage()+
                    ". لم يتم دمج ملفات ناقصة مع مشاريعك.";
            }
            final String message=result;
            ui.post(()->{
                if(!isDestroyed()){
                    Toast.makeText(this,message,Toast.LENGTH_LONG).show();
                    if(service!=null&&target!=null)service.info(target,message);
                }
            });
        },"root-archive-restore").start();
    }
    private void selectDocument(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");
        startActivityForResult(i,FILE_PICKER);
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==EXPORT_PICKER && result==RESULT_OK && data!=null && data.getData()!=null){
            writeRootBackup(data.getData());
            return;
        }
        if(request==RESTORE_PICKER && result==RESULT_OK &&
                data!=null && data.getData()!=null){
            restoreRootBackup(data.getData());
            return;
        }
        if(request==FILE_PICKER && result==RESULT_OK && data!=null && data.getData()!=null){
            Uri uri=data.getData();
            new AlertDialog.Builder(this).setTitle("استيراد الملف")
                .setMessage(LinuxEnvironment.installed(this)?
                    "نسخ الملف إلى /root/Imports داخل Alpine دون تغيير الأصل؟":
                    "نسخ الملف إلى مجلد التطبيق دون تغيير الأصل؟")
                .setNegativeButton("إلغاء",null)
                .setPositiveButton("نسخ",(d,w)->importFile(uri)).show();
        }
    }
    private void importFile(Uri uri){
        String name="مستورد";
        try(Cursor cursor=getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
            if(cursor!=null && cursor.moveToFirst()&&!cursor.isNull(0))name=cursor.getString(0);
        }catch(Exception ignored){}
        final String suggested=name;
        final TerminalService.Session target=active;
        final Context app=getApplicationContext();
        new Thread(()->{
            String result;
            try(InputStream in=getContentResolver().openInputStream(uri)){
                if(in==null)throw new IOException("تعذر فتح الملف");
                boolean alpine=LinuxEnvironment.installed(app);
                File dir=alpine?
                    new File(LinuxEnvironment.rootfs(app),"root/Imports"):
                    new File(getFilesDir(),"المستوردات");
                java.nio.file.Path dest=SafeFileImporter.copy(in,dir.toPath(),suggested);
                result="تم الاستيراد: "+(alpine?
                    "/root/Imports/"+dest.getFileName().toString():dest.toString());
            }catch(Exception error){
                result="فشل الاستيراد: "+error.getMessage();
            }
            final String message=result;
            ui.post(()->{
                if(!isDestroyed()){
                    Toast.makeText(this,message,Toast.LENGTH_LONG).show();
                    if(service!=null&&target!=null)service.info(target,message);
                }
            });
        },"file-import").start();
    }
    private void copyText(){
        if(active==null)return;
        CharSequence shown=console.getText();
        int from=Selection.getSelectionStart(shown);
        int to=Selection.getSelectionEnd(shown);
        if(from>=0&&to>from&&to<=shown.length())
            copyValue(shown.subSequence(from,to).toString());
        else copyValue(active.display.render());
    }
    @Override public void onConfigurationChanged(Configuration change){super.onConfigurationChanged(change);}
    @Override protected void onDestroy(){
        // Native sessions are owned by the foreground service, not this screen.
        super.onDestroy();
    }
}
