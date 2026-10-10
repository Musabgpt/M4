package io.musab.arabicterminal;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * المستخدم يفعّل هذه الخدمة بإرادته من إعدادات أندرويد.
 * لا تسجّل أحداث الشاشة أو كلمات المرور ولا تستخدم الشبكة.
 * لوحة مصغرة فوق التطبيقات للتحكم المباشر من دون العودة للطرفية.
 */
public final class UiControlService extends AccessibilityService {
    public static final int BACK=GLOBAL_ACTION_BACK,HOME=GLOBAL_ACTION_HOME,
        RECENTS=GLOBAL_ACTION_RECENTS,NOTIFICATIONS=GLOBAL_ACTION_NOTIFICATIONS,
        QUICK_SETTINGS=GLOBAL_ACTION_QUICK_SETTINGS;
    private static volatile UiControlService instance;
    private WindowManager wm;
    private LinearLayout overlay;
    private WindowManager.LayoutParams params;
    private boolean expanded;

    static UiControlService current(){return instance;}
    @Override public void onServiceConnected(){
        super.onServiceConnected();
        instance=this;
        new Handler(Looper.getMainLooper()).post(this::installOverlay);
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event){
        // Do not capture or store event content.
    }
    @Override public void onInterrupt(){}
    private Button button(String name,Runnable click){
        Button b=new Button(this);
        b.setAllCaps(false);b.setText(name);b.setTextSize(12);b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setOnClickListener(v->click.run());return b;
    }
    private void installOverlay(){
        if(overlay!=null)return;
        wm=(WindowManager)getSystemService(Context.WINDOW_SERVICE);
        if(wm==null)return;
        overlay=new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setBackgroundColor(0xEF172535);
        overlay.setPadding(7,7,7,7);
        params=new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT);
        params.gravity=Gravity.TOP|Gravity.END;
        params.x=12;params.y=110;
        try{
            wm.addView(overlay,params);
            collapse();
        }catch(RuntimeException e){overlay=null;}
    }
    private void collapse(){
        if(overlay==null)return;
        expanded=false;
        overlay.removeAllViews();
        overlay.addView(button("⌨ عربي",this::expand));
        params.flags=WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        wm.updateViewLayout(overlay,params);
    }
    private void expand(){
        if(overlay==null)return;
        expanded=true;
        overlay.removeAllViews();
        LinearLayout header=new LinearLayout(this);
        header.addView(button("✕ إخفاء",this::collapse));
        header.addView(button("⌂ الرئيسية",()->runAction("الرئيسية")));
        header.addView(button("↩ رجوع",()->runAction("ارجع")));
        overlay.addView(header);
        LinearLayout shortcuts=new LinearLayout(this);
        shortcuts.addView(button("التطبيقات",()->runAction("التطبيقات الأخيرة")));
        shortcuts.addView(button("↑",()->runAction("مرر للأعلى")));
        shortcuts.addView(button("↓",()->runAction("مرر للأسفل")));
        overlay.addView(shortcuts);
        final EditText input=new EditText(this);
        input.setSingleLine(true);
        input.setTextSize(14);input.setTextColor(0xFFFFFFFF);
        input.setHintTextColor(0xFFB8C9DD);
        input.setHint("افتح واتساب / اضغط على إرسال");
        input.setMinimumWidth((int)(230*getResources().getDisplayMetrics().density));
        input.setImeOptions(EditorInfo.IME_ACTION_GO);
        overlay.addView(input);
        overlay.addView(button("نفّذ الأمر العربي",()->{
            String cmd=input.getText().toString().trim();
            if(cmd.isEmpty())return;
            String result=PhoneController.run(this,ArabicCommandRouter.parse(cmd));
            input.setText("");
            Toast.makeText(this,result.length()>170?result.substring(0,170):result,Toast.LENGTH_LONG).show();
        }));
        params.flags=WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        wm.updateViewLayout(overlay,params);
    }
    private void runAction(String cmd){
        String status=PhoneController.run(this,ArabicCommandRouter.parse(cmd));
        Toast.makeText(this,status,Toast.LENGTH_SHORT).show();
    }
    public boolean swipe(boolean upward){
        android.util.DisplayMetrics metrics=getResources().getDisplayMetrics();
        int x=metrics.widthPixels/2;
        int y1=(int)(metrics.heightPixels*(upward?0.77:0.25));
        int y2=(int)(metrics.heightPixels*(upward?0.25:0.77));
        return gesture(x,y1,x,y2,400);
    }
    public boolean tap(int x,int y){
        android.util.DisplayMetrics m=getResources().getDisplayMetrics();
        if(x<0||y<0||x>=m.widthPixels||y>=m.heightPixels)return false;
        return gesture(x,y,x,y,90);
    }
    private boolean gesture(int x,int y,int x2,int y2,long duration){
        Path path=new Path();path.moveTo(x,y);path.lineTo(x2,y2);
        GestureDescription.StrokeDescription stroke=
            new GestureDescription.StrokeDescription(path,0,duration);
        return dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(),null,null);
    }
    public boolean clickLabel(String expected){
        AccessibilityNodeInfo root=getRootInActiveWindow();
        if(root==null)return false;
        List<AccessibilityNodeInfo> found=root.findAccessibilityNodeInfosByText(expected);
        String requested=ArabicCommandRouter.normalize(expected);
        for(AccessibilityNodeInfo node:found){
            CharSequence t=node.getText(),desc=node.getContentDescription();
            String shown=t==null?"":ArabicCommandRouter.normalize(t.toString());
            String description=desc==null?"":ArabicCommandRouter.normalize(desc.toString());
            if(!shown.equals(requested)&&!description.equals(requested))continue;
            AccessibilityNodeInfo candidate=node;
            for(int depth=0;depth<5&&candidate!=null;depth++){
                if(candidate.isClickable()&&candidate.isEnabled())
                    return candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                candidate=candidate.getParent();
            }
        }
        return false;
    }
    @Override public void onDestroy(){
        instance=null;
        if(wm!=null&&overlay!=null){
            try{wm.removeView(overlay);}catch(RuntimeException ignored){}
        }
        overlay=null;
        super.onDestroy();
    }
}
