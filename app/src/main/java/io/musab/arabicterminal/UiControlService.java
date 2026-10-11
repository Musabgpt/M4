package io.musab.arabicterminal;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.List;

/**
 * Phone access is separate from the retired Arabic terminal.
 * No floating widget, overlay, command composer or blocking side window.
 * User explicitly enables this accessibility service in Android Settings.
 */
public final class UiControlService extends AccessibilityService {
    public static final int BACK=GLOBAL_ACTION_BACK,HOME=GLOBAL_ACTION_HOME,
        RECENTS=GLOBAL_ACTION_RECENTS,NOTIFICATIONS=GLOBAL_ACTION_NOTIFICATIONS,
        QUICK_SETTINGS=GLOBAL_ACTION_QUICK_SETTINGS;
    private static volatile UiControlService instance;
    public static UiControlService current(){return instance;}
    @Override public void onServiceConnected(){super.onServiceConnected();instance=this;}
    @Override public void onAccessibilityEvent(AccessibilityEvent event){
        // Do not capture passwords or screen events. Commands are user-initiated.
    }
    @Override public void onInterrupt(){com.musabgpt.m4.runtime.AgentControl.stop();}
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
        super.onDestroy();
    }
}
