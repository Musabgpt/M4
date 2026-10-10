package io.musab.arabicterminal;

import java.util.Locale;

/**
 * قواعد عربية حتمية تعمل محلياً بلا ذكاء اصطناعي سحابي.
 * الأوامر الموجّهة للهاتف لا تُرسل إلى صدفة لينكس.
 */
public final class ArabicCommandRouter {
    public enum Kind {
        SHELL, HELP, PHONE, BATTERY, MEMORY, STORAGE, IDENTITY,
        SETTINGS, CHOOSE_FILE, CLEAR, ROOT, UNKNOWN, CONFIRM_DELETE,
        LIST_APPS, OPEN_APP, OPEN_SYSTEM_APP, OPEN_SETTINGS_PAGE,
        OPEN_URL, DIAL, SHARE, ACCESSIBILITY_SETTINGS,
        HOME, BACK, RECENTS, NOTIFICATIONS, QUICK_SETTINGS,
        SWIPE_UP, SWIPE_DOWN, TAP_TEXT, TAP_POINT, NETWORK_DIAG, LINUX_DIAG,
        PACKAGES, PACKAGE_UPDATE, PACKAGE_INSTALLED, PACKAGE_SEARCH,
        PACKAGE_DETAILS, PACKAGE_INSTALL, PACKAGE_REMOVE
    }

    public static final class Parsed {
        public final Kind kind;
        public final String command;
        public final String description;
        Parsed(Kind kind, String command, String description) {
            this.kind=kind; this.command=command; this.description=description;
        }
    }
    private ArabicCommandRouter(){}
    private static Parsed action(Kind kind){return new Parsed(kind,"","");}
    private static Parsed argument(Kind kind,String arg){return new Parsed(kind,arg,arg);}
    private static Parsed shell(String cmd){return new Parsed(Kind.SHELL,cmd,cmd);}
    static String quote(String value){return "'"+value.replace("'","'\\''")+"'";}
    static String normalize(String s){
        return s.replaceAll("[\\u064B-\\u065F\\u0670\\u06D6-\\u06ED]","")
            .replace('أ','ا').replace('إ','ا').replace('آ','ا')
            .replace('ى','ي').replace('ـ',' ').toLowerCase(Locale.ROOT).trim()
            .replaceAll("\\s+"," ");
    }
    private static boolean eq(String s,String... variants){
        for(String v:variants)if(s.equals(v))return true;
        return false;
    }
    private static Parsed pathCommand(String normalized,String original,String prefix,
                                      String shellPrefix,boolean destructive){
        if(!normalized.startsWith(prefix)||normalized.length()<=prefix.length())return null;
        String path=original.substring(prefix.length()).trim();
        if(path.isEmpty())return action(Kind.UNKNOWN);
        return new Parsed(destructive?Kind.CONFIRM_DELETE:Kind.SHELL,
                shellPrefix+quote(path),path);
    }
    private static String tail(String s,String... starts){
        for(String start:starts)if(s.startsWith(start)&&s.length()>start.length())
            return s.substring(start.length()).trim();
        return null;
    }
    private static String originalTail(String original,String normalized,String normalizedTail){
        // Preserve original Arabic diacritics and capitalization in the argument.
        String prefix=normalized.substring(0,normalized.length()-normalizedTail.length()).trim();
        int count=prefix.isEmpty()?0:prefix.split(" ").length;
        String[] tokens=original.trim().split("\\s+",count+1);
        return tokens.length>count?tokens[count].trim():normalizedTail;
    }
    public static Parsed parse(String value){
        if(value==null||value.trim().isEmpty())return action(Kind.UNKNOWN);
        String original=value.trim().replaceAll("\\s+"," ");
        String s=normalize(original);
        if(eq(s,"مساعدة","ساعدني","الاوامر","دليل","؟"))return action(Kind.HELP);
        if(eq(s,"الهاتف","الجهاز","معلومات الهاتف","معلومات الجهاز"))return action(Kind.PHONE);
        if(eq(s,"البطارية","حالة البطارية","بطارية"))return action(Kind.BATTERY);
        if(eq(s,"الرام","الذاكرة","الذاكره","استخدام الذاكرة"))return action(Kind.MEMORY);
        if(eq(s,"التخزين","المساحة","مساحة التخزين"))return action(Kind.STORAGE);
        if(eq(s,"الهوية","من انا","صلاحياتي"))return action(Kind.IDENTITY);
        if(eq(s,"الاعدادات","افتح الاعدادات"))return action(Kind.SETTINGS);
        if(eq(s,"اختر ملف","استيراد ملف","تصفح الملفات"))return action(Kind.CHOOSE_FILE);
        if(eq(s,"مسح","نظف الشاشة","امسح الشاشة"))return action(Kind.CLEAR);
        if(eq(s,"روت","جذر","صلاحيات الجذر"))return action(Kind.ROOT);
        if(eq(s,"فحص الشبكة","افحص الشبكة","تشخيص الشبكة","اختبار الانترنت"))return action(Kind.NETWORK_DIAG);
        if(eq(s,"مدير الحزم","ادارة الحزم","الحزم","متجر لينكس"))return action(Kind.PACKAGES);
        if(eq(s,"تحديث الحزم","حدث الحزم","تحديث المستودعات"))return action(Kind.PACKAGE_UPDATE);
        if(eq(s,"الحزم المثبتة","قائمة الحزم","اعرض الحزم"))return action(Kind.PACKAGE_INSTALLED);
        if(eq(s,"فحص لينكس","تشخيص لينكس","فحص apk","فحص الحزم","تشخيص المستودعات"))
            return action(Kind.LINUX_DIAG);
        if(eq(s,"التطبيقات","قائمة التطبيقات","اعرض التطبيقات","التطبيقات المثبتة","برامج الهاتف"))
            return action(Kind.LIST_APPS);
        if(eq(s,"تحكم","تفعيل التحكم","اذن التحكم","صلاحية التحكم","امكانية الوصول","تفعيل امكانية الوصول"))
            return action(Kind.ACCESSIBILITY_SETTINGS);
        if(eq(s,"الرئيسية","الرئيسيه","الرئيسية الآن","الشاشة الرئيسية","اذهب للرئيسية","اذهب الى الرئيسية","هوم"))
            return action(Kind.HOME);
        if(eq(s,"رجوع","ارجع","للخلف","عودة"))return action(Kind.BACK);
        if(eq(s,"التطبيقات الاخيرة","التطبيقات المفتوحة","التطبيقات الحديثة"))
            return action(Kind.RECENTS);
        if(eq(s,"الاشعارات","افتح الاشعارات"))return action(Kind.NOTIFICATIONS);
        if(eq(s,"الاختصارات","الاعدادات السريعة","اللوحة السريعة"))return action(Kind.QUICK_SETTINGS);
        if(eq(s,"اسحب للاعلي","مرر للاعلي","تمرير للاعلي","انزل في الصفحة"))
            return action(Kind.SWIPE_UP);
        if(eq(s,"اسحب للاسفل","مرر للاسفل","تمرير للاسفل","اصعد في الصفحة"))
            return action(Kind.SWIPE_DOWN);
        if(eq(s,"الكاميرا","افتح الكاميرا","شغل الكاميرا"))return argument(Kind.OPEN_SYSTEM_APP,"camera");
        if(eq(s,"الهاتف للاتصال","افتح الاتصال","افتح الهاتف للاتصال","لوحة الاتصال"))
            return argument(Kind.OPEN_SYSTEM_APP,"dialer");
        if(eq(s,"المعرض","الصور","افتح الصور","افتح المعرض"))return argument(Kind.OPEN_SYSTEM_APP,"gallery");
        if(eq(s,"المتصفح","افتح المتصفح"))return argument(Kind.OPEN_SYSTEM_APP,"browser");
        if(eq(s,"الواي فاي","افتح الواي فاي","اعدادات الواي فاي"))
            return argument(Kind.OPEN_SETTINGS_PAGE,"wifi");
        if(eq(s,"البلوتوث","افتح البلوتوث","اعدادات البلوتوث"))
            return argument(Kind.OPEN_SETTINGS_PAGE,"bluetooth");
        if(eq(s,"اعدادات التطبيقات","معلومات التطبيقات"))
            return argument(Kind.OPEN_SETTINGS_PAGE,"applications");
        if(eq(s,"اعدادات البطارية"))
            return argument(Kind.OPEN_SETTINGS_PAGE,"battery");
        if(eq(s,"اعدادات الشاشة"))
            return argument(Kind.OPEN_SETTINGS_PAGE,"display");
        if(eq(s,"الملفات","اعرض الملفات","قائمة الملفات"))return shell("ls -la");
        if(eq(s,"اين انا","مكاني","المجلد الحالي"))return shell("pwd");
        if(eq(s,"العمليات","البرامج الجارية"))return shell("ps -A");
        if(eq(s,"التاريخ","الوقت","الساعة"))return shell("date");
        if(eq(s,"النواة","اصدار النظام"))return shell("uname -a");
        if(eq(s,"الشبكة","واجهات الشبكة"))return shell("ip addr");
        if(eq(s,"الملفات المخفية"))return shell("ls -la");
        if(eq(s,"مساعدة النظام"))return shell("help");
        String packageName=tail(s,"ابحث عن حزمة ","ابحث عن برنامج ");
        if(packageName!=null)return argument(Kind.PACKAGE_SEARCH,originalTail(original,s,packageName));
        packageName=tail(s,"تفاصيل حزمة ","معلومات حزمة ");
        if(packageName!=null)return argument(Kind.PACKAGE_DETAILS,originalTail(original,s,packageName));
        packageName=tail(s,"ثبت حزمة ","ثبت برنامج ","تثبيت حزمة ","نزل حزمة ");
        if(packageName!=null)return argument(Kind.PACKAGE_INSTALL,originalTail(original,s,packageName));
        packageName=tail(s,"احذف حزمة ","ازل حزمة ");
        if(packageName!=null)return argument(Kind.PACKAGE_REMOVE,originalTail(original,s,packageName));
        String arg=tail(s,"افتح رابط ","افتح الموقع ","رابط ","تصفح ");
        if(arg!=null)return argument(Kind.OPEN_URL,originalTail(original,s,arg));
        arg=tail(s,"اتصل بالرقم ","اتصل ب ","اتصل ","اطلب ");
        if(arg!=null)return argument(Kind.DIAL,arg);
        arg=tail(s,"شارك النص ","مشاركة ","شارك ");
        if(arg!=null)return argument(Kind.SHARE,originalTail(original,s,arg));
        arg=tail(s,"اضغط علي ","انقر علي ","المس ");
        if(arg!=null)return argument(Kind.TAP_TEXT,originalTail(original,s,arg));
        arg=tail(s,"اضغط عند ","المس عند ");
        if(arg!=null)return argument(Kind.TAP_POINT,arg);
        arg=tail(s,"افتح تطبيق ","شغل تطبيق ","افتح برنامج ","شغل برنامج ",
                   "افتح ","شغل ","شغّل ");
        if(arg!=null)return argument(Kind.OPEN_APP,originalTail(original,s,arg));
        String[][] verbs={
            {"اذهب الي ","cd "},{"ادخل ","cd "},
            {"اقرا ","cat "},{"اعرض ملف ","cat "},
            {"انشئ مجلد ","mkdir -p "},{"انشئ ملف ","touch "},
            {"احذف ملف ","rm -- "},{"احذف مجلد ","rmdir -- "},
            {"اطبع ","printf '%s\\n' "}
        };
        for(String[] v:verbs){
            Parsed p=pathCommand(s,original,v[0],v[1],v[0].startsWith("احذف"));
            if(p!=null)return p;
        }
        if(original.startsWith("!")){
            String cmd=original.substring(1).trim();
            return cmd.isEmpty()?action(Kind.UNKNOWN):shell(cmd);
        }
        if(original.matches("(?s)^[A-Za-z_./~$].*"))return shell(original);
        return action(Kind.UNKNOWN);
    }
}
