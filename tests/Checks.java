import io.musab.arabicterminal.ArabicCommandRouter;
import io.musab.arabicterminal.TerminalScreen;
public final class Checks {
    private static int count=0;
    private static void check(boolean yes,String title){count++;if(!yes)throw new AssertionError(title);}
    public static void main(String[] args){
        check(ArabicCommandRouter.parse("الملفات").command.equals("ls -la"),"الملفات");
        check(ArabicCommandRouter.parse("أين أنا").command.equals("pwd"),"المجلد");
        check(ArabicCommandRouter.parse("البطارية").kind==ArabicCommandRouter.Kind.BATTERY,"البطارية");
        check(ArabicCommandRouter.parse("الهاتف").kind==ArabicCommandRouter.Kind.PHONE,"الهاتف");
        check(ArabicCommandRouter.parse("!echo مرحبا").command.equals("echo مرحبا"),"shell");
        check(ArabicCommandRouter.parse("احذف ملف مثال").kind==ArabicCommandRouter.Kind.CONFIRM_DELETE,"تأكيد الحذف");
        check(ArabicCommandRouter.parse("أنشئ ملف x;rm -rf x").command.equals("touch 'x;rm -rf x'"),"escape");
        check(ArabicCommandRouter.parse("أنشئ مجلد اسم'ملف").command.equals("mkdir -p 'اسم'\\''ملف'"),"quote");
        check(ArabicCommandRouter.parse("نظّف الشاشة").kind==ArabicCommandRouter.Kind.CLEAR,"تشكيل");
        TerminalScreen screen=new TerminalScreen();
        screen.append("مرحبا\r\nعالم");
        check(screen.render().contains("عالم"),"UTF8 Arabic");
        screen.append("\u001b[2J\u001b[Hهاتف");
        check(screen.render().startsWith("هاتف"),"clear");
        screen.append("\rباب");
        check(screen.render().startsWith("باب"),"carriage return");
        screen.append("\u001b[?1049hبديل\u001b[?1049l");
        check(!screen.render().contains("بديل"),"alt screen");
        screen.append("\u001b[31mأحمر\u001b[0m");
        TerminalScreen.Frame frame=screen.frame();
        check(frame.foreground.length==frame.text.length(),"colors length");
        check(frame.foreground[frame.text.indexOf("أحمر")]==0xd75050,"SGR 31");
        screen.append("\u001b[38;2;11;22;33mX");
        frame=screen.frame();
        check(frame.foreground[frame.text.lastIndexOf('X')]==0x0b1621,"RGB truecolor");
        screen.append("\u001b[6n");
        check(screen.drainResponse().contains("R"),"cursor response");
        screen.resize(35,120);
        check(screen.rows()==35 && screen.columns()==120,"resize");
        check(screen.drainResponse().isEmpty(),"response drained");
        screen.append("\u001b[2J😀");
        check(screen.render().contains("😀"),"emoji codepoint");
        screen.append("\u001b[2J\u001b[20;10H\u001b[2Kس");
        check(screen.render().contains("س"),"cursor positioning");
        TerminalScreen editor=new TerminalScreen();
        editor.resize(24,80);
        editor.append("PRIMARY_SESSION_MARKER\r\n");
        editor.append("\u001b[?1049h");
        editor.append("\u001b[10;10Hnano status");
        TerminalScreen.Frame alt=editor.frame();
        check(alt.text.split("\\n",-1).length==24,"alternate screen must retain all rows");
        check(alt.foreground.length==alt.text.length(),"alternate foreground indices");
        editor.resize(30,100);
        editor.append("\u001b[?1049l");
        check(editor.render().contains("PRIMARY_SESSION_MARKER"),
            "resize of nano/tmux must preserve original shell buffer");
        editor.append("\u001b[?1049h\u001b[30;3Htmux footer");
        check(editor.frame().text.contains("tmux footer"),"tmux footer at last row");
        editor.append("\u001b[?1049l");
        // VT compatibility: ED2 does not reposition cursor; the GUI draws
        // the cursor at the actual visible cell instead of at the end of text.
        TerminalScreen vt=new TerminalScreen();
        vt.append("abc");
        TerminalScreen.Frame caret=vt.frame();
        check(caret.cursorIndex==3, "cursor follows printed text");
        check(caret.text.charAt(caret.cursorIndex)==' ', "cursor has a paintable cell");
        vt.append("\u001b[?25l");
        check(vt.frame().cursorIndex==-1, "DEC private mode hides cursor");
        vt.append("\u001b[?25h");
        check(vt.frame().cursorIndex==3, "DEC private mode restores cursor");
        vt.append("\u001b[9;7H\u001b[2J\u001b[6n");
        check(vt.drainResponse().equals("\u001b[9;7R"), "ED2 preserves cursor position");
        check(vt.frame().cursorIndex>=0, "cursor remains on cleared screen");
        vt.append("\u001b[H\u001b[31;44;7mA\u001b[27mB");
        TerminalScreen.Frame reversed=vt.frame();
        int a=reversed.text.indexOf("AB");
        check(a>=0, "test cells located");
        check(reversed.foreground[a]==0x729ded &&
            reversed.background[a]==0xd75050, "SGR inverse colors");
        check(reversed.foreground[a+1]==0xd75050 &&
            reversed.background[a+1]==0x729ded, "SGR 27 disables inverse");
        vt.append("\u001b[0mC");
        TerminalScreen.Frame reset=vt.frame();
        int c=reset.text.indexOf("ABC")+2;
        check(reset.foreground[c]==TerminalScreen.DEFAULT_FG, "SGR zero resets colors");
        vt.append("\u001b[?1049;25h\u001b[?25l");
        check(vt.frame().cursorIndex==-1, "multi-parameter DEC mode and hidden cursor");
        vt.append("\u001b[?1049l\u001b[?25h");
        check(vt.frame().text.contains("ABC"), "primary shell buffer preserved");
        System.out.println("PASS: "+count+" standalone engine/Arabic grammar tests");
    }
}
