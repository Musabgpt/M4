import io.musab.arabicterminal.PackageCommands;
import io.musab.arabicterminal.PackageCommands.Action;
public final class PackageCommandsTest {
    private static int count;
    private static void expect(boolean ok,String why){count++;if(!ok)throw new AssertionError(why);}
    private static void reject(Action a,String s){
        try{PackageCommands.command(a,s);throw new AssertionError("Accepted unsafe name: "+s);}
        catch(IllegalArgumentException good){count++;}
    }
    public static void main(String[] args){
        String update=PackageCommands.command(Action.UPDATE,null);
        expect(update.contains("apk-v2 update"),"verified compatibility manager");
        expect(update.contains("PACKAGE_EXIT_CODE"),"return status");
        expect(!update.contains("upgrade"),"never upgrade");
        expect(PackageCommands.command(Action.INSTALLED,null).contains("apk-v2 info"),"list");
        expect(PackageCommands.command(Action.SEARCH,"TREE").contains("apk-v2 search -v '*tree*'"),"search");
        expect(PackageCommands.command(Action.DETAILS,"tree").contains("apk-v2 info -a 'tree'"),"details");
        String install=PackageCommands.command(Action.INSTALL,"tree");
        expect(install.contains("apk-v2 add --simulate 'tree'"),"simulate first");
        expect(install.contains("&& apk-v2 add 'tree'"),"gated install");
        expect(install.contains("mktemp -d"),"unique backup per change");
        expect(install.contains("if mkdir '/root/.arabicterminal-apk-manager.lock'"),
            "atomic lock protects package install");
        expect(install.contains("trap 'rmdir /root/.arabicterminal-apk-manager.lock"),
            "owner clears lock on completion");
        expect(install.contains("Package manager busy or stale lock"),
            "busy process produces an error");
        expect(update.contains("if mkdir '/root/.arabicterminal-apk-manager.lock'"),
            "index update also serialized");
        expect(!PackageCommands.command(Action.SEARCH,"tree").contains("if mkdir "),
            "read-only package search does not require lock");
        expect(install.contains("cp -a /lib/apk/db"),"backup database");
        expect(install.contains("cp -a /etc/apk/world"),"backup world");
        String remove=PackageCommands.command(Action.REMOVE,"tree");
        expect(remove.contains("apk-v2 del --simulate 'tree'"),"simulate removal");
        expect(remove.contains("&& apk-v2 del 'tree'"),"gated removal");
        expect(PackageCommands.command(Action.INSTALL,"python3-dev").contains("'python3-dev'"),"hyphen");
        String[] invalid={""," ","-f","--allow-untrusted","tree;rm -rf /","tree && whoami",
            "abc\nrm -rf /","$(id)","a'b","مجلد","../etc/passwd","a b","a*","a/b","a|echo"};
        for(String s:invalid){reject(Action.INSTALL,s);reject(Action.SEARCH,s);}
        reject(Action.INSTALL,null);
        for(String s:new String[]{"apk-tools","apk-tools-static","musl","busybox","alpine-base"})
            reject(Action.REMOVE,s);
        reject(Action.INSTALL,"apk-tools");
        expect(io.musab.arabicterminal.ArabicCommandRouter.parse("مدير الحزم").kind==
            io.musab.arabicterminal.ArabicCommandRouter.Kind.PACKAGES,"Arabic package manager");
        expect(io.musab.arabicterminal.ArabicCommandRouter.parse("تحديث الحزم").kind==
            io.musab.arabicterminal.ArabicCommandRouter.Kind.PACKAGE_UPDATE,"Arabic update");
        expect(io.musab.arabicterminal.ArabicCommandRouter.parse("ثبت حزمة tree").kind==
            io.musab.arabicterminal.ArabicCommandRouter.Kind.PACKAGE_INSTALL,"Arabic install");
        expect(io.musab.arabicterminal.ArabicCommandRouter.parse("احذف حزمة tree").kind==
            io.musab.arabicterminal.ArabicCommandRouter.Kind.PACKAGE_REMOVE,"Arabic removal");
        expect(io.musab.arabicterminal.ArabicCommandRouter.parse("ابحث عن حزمة git").kind==
            io.musab.arabicterminal.ArabicCommandRouter.Kind.PACKAGE_SEARCH,"Arabic search");
        System.out.println("PASS: "+count+" package manager safety tests");
    }
}
