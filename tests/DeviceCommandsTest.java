import io.musab.arabicterminal.ArabicCommandRouter;
public final class DeviceCommandsTest {
    private static int total;
    private static void assertCommand(String given,ArabicCommandRouter.Kind expected,String arg){
        ArabicCommandRouter.Parsed p=ArabicCommandRouter.parse(given);
        if(p.kind!=expected||(!arg.isEmpty()&&!p.command.equals(arg))){
            throw new AssertionError("Expected "+expected+" "+arg+" got "+p.kind+" "+p.command+" for "+given);
        }
        total++;
    }
    public static void main(String[] a){
        assertCommand("فحص الشبكة",ArabicCommandRouter.Kind.NETWORK_DIAG,"");
        assertCommand("فحص لينكس",ArabicCommandRouter.Kind.LINUX_DIAG,"");
        assertCommand("فحص apk",ArabicCommandRouter.Kind.LINUX_DIAG,"");
        assertCommand("افتح واتساب",ArabicCommandRouter.Kind.OPEN_APP,"واتساب");
        assertCommand("افتح تطبيق YouTube",ArabicCommandRouter.Kind.OPEN_APP,"YouTube");
        assertCommand("شغّل تلغرام",ArabicCommandRouter.Kind.OPEN_APP,"تلغرام");
        assertCommand("اعرض التطبيقات",ArabicCommandRouter.Kind.LIST_APPS,"");
        assertCommand("الرئيسية",ArabicCommandRouter.Kind.HOME,"");
        assertCommand("ارجع",ArabicCommandRouter.Kind.BACK,"");
        assertCommand("التطبيقات الأخيرة",ArabicCommandRouter.Kind.RECENTS,"");
        assertCommand("افتح الإشعارات",ArabicCommandRouter.Kind.NOTIFICATIONS,"");
        assertCommand("مرّر للأعلى",ArabicCommandRouter.Kind.SWIPE_UP,"");
        assertCommand("مرر للأسفل",ArabicCommandRouter.Kind.SWIPE_DOWN,"");
        assertCommand("اضغط على إرسال",ArabicCommandRouter.Kind.TAP_TEXT,"إرسال");
        assertCommand("اضغط عند 100 250",ArabicCommandRouter.Kind.TAP_POINT,"100 250");
        assertCommand("تفعيل التحكم",ArabicCommandRouter.Kind.ACCESSIBILITY_SETTINGS,"");
        assertCommand("افتح الكاميرا",ArabicCommandRouter.Kind.OPEN_SYSTEM_APP,"camera");
        assertCommand("افتح الواي فاي",ArabicCommandRouter.Kind.OPEN_SETTINGS_PAGE,"wifi");
        assertCommand("اتصل بالرقم 0123456",ArabicCommandRouter.Kind.DIAL,"0123456");
        assertCommand("افتح رابط https://example.org",ArabicCommandRouter.Kind.OPEN_URL,"https://example.org");
        assertCommand("شارك النص أهلاً",ArabicCommandRouter.Kind.SHARE,"أهلاً");
        assertCommand("!ls -a",ArabicCommandRouter.Kind.SHELL,"ls -a");
        assertCommand("افتح الإعدادات",ArabicCommandRouter.Kind.SETTINGS,"");
        assertCommand("الملفات",ArabicCommandRouter.Kind.SHELL,"ls -la");
        assertCommand("أنشئ مجلد abc",ArabicCommandRouter.Kind.SHELL,"mkdir -p 'abc'");
        System.out.println("PASS: "+total+" phone control commands");
    }
}
