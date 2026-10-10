package io.musab.arabicterminal;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.MediaStore;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Launchers and user-consented UI controls through public Android interfaces. */
public final class PhoneController {
    private PhoneController(){}
    public static final class App {
        public final String label,packageName,className;
        App(String label,String packageName,String className){
            this.label=label;this.packageName=packageName;this.className=className;
        }
    }
    public static List<App> launchers(Context context){
        PackageManager pm=context.getPackageManager();
        Intent query=new Intent(Intent.ACTION_MAIN);
        query.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved=pm.queryIntentActivities(query,0);
        List<App> result=new ArrayList<>();
        for(ResolveInfo info:resolved){
            if(info.activityInfo==null)continue;
            CharSequence label=info.loadLabel(pm);
            result.add(new App(label==null?info.activityInfo.packageName:label.toString(),
                    info.activityInfo.packageName,info.activityInfo.name));
        }
        Collections.sort(result,Comparator.comparing(a->ArabicCommandRouter.normalize(a.label)));
        return result;
    }
    public static String run(Context context,ArabicCommandRouter.Parsed command){
        try {
            switch(command.kind) {
                case LIST_APPS: return showApps(context);
                case OPEN_APP: return openApp(context,command.command);
                case OPEN_SYSTEM_APP: return openSystem(context,command.command);
                case OPEN_SETTINGS_PAGE:return settings(context,command.command);
                case OPEN_URL:return url(context,command.command);
                case DIAL:return dial(context,command.command);
                case SHARE:return share(context,command.command);
                case ACCESSIBILITY_SETTINGS:
                    start(context,new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    return "إعدادات إمكانية الوصول: فعّل «تحكم الطرفية العربية» يدويًا، وستظهر لوحة التحكم العائمة.";
                case HOME: return global(UiControlService.HOME,"الرئيسية");
                case BACK: return global(UiControlService.BACK,"الرجوع");
                case RECENTS: return global(UiControlService.RECENTS,"التطبيقات الأخيرة");
                case NOTIFICATIONS: return global(UiControlService.NOTIFICATIONS,"الإشعارات");
                case QUICK_SETTINGS: return global(UiControlService.QUICK_SETTINGS,"الاختصارات السريعة");
                case SWIPE_UP: return swipe(true);
                case SWIPE_DOWN: return swipe(false);
                case TAP_TEXT: return tapText(command.command);
                case TAP_POINT:return tapPoint(command.command);
                default:return "هذا الأمر ليس من أوامر الهاتف.";
            }
        }catch(SecurityException e) {
            return "رفض أندرويد العملية بسبب نقص الصلاحيات: "+e.getMessage();
        }catch(Exception e){
            return "تعذر تنفيذ العملية: "+e.getMessage();
        }
    }
    private static void start(Context context,Intent i){
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(i);
    }
    private static String showApps(Context context){
        List<App> apps=launchers(context);
        StringBuilder out=new StringBuilder("التطبيقات القابلة للفتح ("+apps.size()+"):\n");
        int shown=0;
        for(App a:apps){
            out.append("• ").append(a.label).append(" — ").append(a.packageName).append('\n');
            if(++shown>=100){out.append("...عرض أول 100 تطبيق فقط");break;}
        }
        return out.toString();
    }
    private static String alias(String raw){
        String norm=ArabicCommandRouter.normalize(raw);
        if(norm.startsWith("تطبيق "))norm=norm.substring(6).trim();
        if(norm.startsWith("برنامج "))norm=norm.substring(6).trim();
        switch(norm){
            case "واتساب":case "الواتساب":case "واتس اب":case "واتس":
            case "whatsapp":return "com.whatsapp";
            case "واتساب بزنس":case "whatsapp business":return "com.whatsapp.w4b";
            case "تيليجرام":case "تلغرام":case "تليجرام":case "telegram":return "org.telegram.messenger";
            case "يوتيوب":case "اليوتيوب":case "youtube":return "com.google.android.youtube";
            case "جيميل":case "gmail":return "com.google.android.gm";
            case "كروم":case "chrome":case "جوجل كروم":return "com.android.chrome";
            case "خرائط":case "الخرائط":case "خرائط جوجل":case "maps":return "com.google.android.apps.maps";
            case "جوجل":case "google":return "com.google.android.googlequicksearchbox";
            case "بلاي ستور":case "متجر بلاي":case "play store":return "com.android.vending";
            case "شات جي بي تي":case "شات جيبت":case "chatgpt":return "com.openai.chatgpt";
            case "تيرمكس":case "ترمكس":case "termux":return "com.termux";
            case "ديسكورد":case "discord":return "com.discord";
            case "انستغرام":case "انستا":case "instagram":return "com.instagram.android";
            case "فيسبوك":case "facebook":return "com.facebook.katana";
            case "تيك توك":case "tiktok":return "com.zhiliaoapp.musically";
            case "سبوتيفاي":case "spotify":return "com.spotify.music";
            default:return norm;
        }
    }
    private static String openApp(Context ctx,String name){
        String target=ArabicCommandRouter.normalize(name);
        String alias=alias(name);
        List<App> apps=launchers(ctx);
        List<App> exact=new ArrayList<>(),partial=new ArrayList<>();
        for(App a:apps){
            String label=ArabicCommandRouter.normalize(a.label);
            String pkg=a.packageName.toLowerCase(Locale.ROOT);
            if(label.equals(target)||pkg.equals(alias)||label.equals(alias))exact.add(a);
            else if(label.contains(target)||pkg.contains(target))partial.add(a);
        }
        List<App> candidates=exact.isEmpty()?partial:exact;
        if(candidates.isEmpty())
            return "لم أجد تطبيقًا باسم «"+name+"». اكتب «اعرض التطبيقات» لمعرفة الأسماء. قد يخفي النظام بعض التطبيقات.";
        // Avoid launching a wrong app when a query is ambiguous.
        App chosen=candidates.get(0);
        if(exact.isEmpty() && candidates.size()>1){
            StringBuilder options=new StringBuilder("وجدت أكثر من تطبيق، حدّد الاسم:\n");
            for(int k=0;k<Math.min(8,candidates.size());k++){
                App a=candidates.get(k);
                options.append("• ").append(a.label).append(" — ").append(a.packageName).append('\n');
            }
            return options.toString();
        }
        Intent launch=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        launch.setClassName(chosen.packageName,chosen.className);
        start(ctx,launch);
        return "تم إرسال أمر فتح «"+chosen.label+"» إلى أندرويد.";
    }
    private static String openSystem(Context ctx,String id){
        Intent i;
        switch(id){
            case "camera":i=new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);break;
            case "dialer":i=new Intent(Intent.ACTION_DIAL,Uri.parse("tel:"));break;
            case "gallery":i=new Intent(Intent.ACTION_GET_CONTENT).setType("image/*");
                i.addCategory(Intent.CATEGORY_OPENABLE);break;
            case "browser":i=new Intent(Intent.ACTION_VIEW,Uri.parse("about:blank"));break;
            default:return "أمر النظام غير معروف.";
        }
        start(ctx,i);
        return "تم إرسال أمر فتح التطبيق إلى أندرويد.";
    }
    private static String settings(Context ctx,String section){
        String action;
        switch(section){
            case "wifi":action=Settings.ACTION_WIFI_SETTINGS;break;
            case "bluetooth":action=Settings.ACTION_BLUETOOTH_SETTINGS;break;
            case "battery":action=Settings.ACTION_BATTERY_SAVER_SETTINGS;break;
            case "display":action=Settings.ACTION_DISPLAY_SETTINGS;break;
            case "applications":action=Settings.ACTION_APPLICATION_SETTINGS;break;
            default:action=Settings.ACTION_SETTINGS;
        }
        start(ctx,new Intent(action));
        return "تم فتح شاشة الإعدادات المطلوبة.";
    }
    private static String url(Context ctx,String address){
        String cleaned=address.trim();
        if(cleaned.startsWith("www."))cleaned="https://"+cleaned;
        if(!cleaned.startsWith("https://")&&!cleaned.startsWith("http://"))
            return "الروابط المدعومة تبدأ بـ https:// أو http://.";
        Uri parsed=Uri.parse(cleaned);
        if(parsed.getHost()==null || parsed.getHost().isEmpty())
            return "الرابط غير صالح.";
        start(ctx,new Intent(Intent.ACTION_VIEW,parsed).addCategory(Intent.CATEGORY_BROWSABLE));
        return "تم إرسال الرابط إلى المتصفح.";
    }
    private static String dial(Context ctx,String number){
        String cleaned=number.trim().replace(" ","");
        if(!cleaned.matches("[+0-9*#]{2,24}"))return "رقم الهاتف غير صالح.";
        start(ctx,new Intent(Intent.ACTION_DIAL,Uri.fromParts("tel",cleaned,null)));
        return "تم فتح شاشة الاتصال بالرقم، بدون إجراء اتصال تلقائي.";
    }
    private static String share(Context ctx,String text){
        Intent send=new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT,text);
        Intent chooser=Intent.createChooser(send,"مشاركة النص");
        start(ctx,chooser);
        return "تم فتح قائمة المشاركة.";
    }
    private static String global(int action,String name){
        UiControlService service=UiControlService.current();
        if(service==null)return missingAccess();
        return service.performGlobalAction(action)?"تم إرسال أمر «"+name+"».":"رفض النظام أمر «"+name+"».";
    }
    private static String swipe(boolean upward){
        UiControlService service=UiControlService.current();
        if(service==null)return missingAccess();
        return service.swipe(upward)?"تم إرسال حركة التمرير.":"تعذّر التمرير.";
    }
    private static String tapText(String label){
        UiControlService service=UiControlService.current();
        if(service==null)return missingAccess();
        return service.clickLabel(label)?"تم إرسال النقر على «"+label+"».":
            "لم أجد عنصرًا قابلًا للنقر بهذا النص على الشاشة الحالية.";
    }
    private static String tapPoint(String coordinates){
        UiControlService service=UiControlService.current();
        if(service==null)return missingAccess();
        String[] parts=coordinates.trim().split("[ ,،]+");
        if(parts.length!=2)return "اكتب: اضغط عند 200 500";
        try{
            int x=Integer.parseInt(parts[0]),y=Integer.parseInt(parts[1]);
            return service.tap(x,y)?"تم إرسال اللمس على الشاشة.":"النقطة خارج الشاشة أو تعذّر النقر.";
        }catch(NumberFormatException e){return "إحداثيات غير صحيحة. اكتب: اضغط عند 200 500";}
    }
    private static String missingAccess(){
        return "تحتاج هذه الوظيفة خدمة «تحكم الطرفية العربية». اكتب «تفعيل التحكم» ثم فعّل الخدمة بنفسك في إعدادات إمكانية الوصول.";
    }
}
