package io.musab.arabicterminal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** محاكي محلي لشاشة VT، مع ANSI SGR و256/24-bit color وحفظ السجل. */
public final class TerminalScreen {
    public static final int DEFAULT_FG = 0xE7EFF9, DEFAULT_BG = 0x08111D;
    private static final int MAX_HISTORY = 500;
    private static final class Cell {
        String glyph;
        int fg, bg;
        boolean bold;
        Cell(String glyph, int fg, int bg, boolean bold) {
            this.glyph = glyph; this.fg = fg; this.bg = bg; this.bold = bold;
        }
    }
    public static final class Frame {
        public final String text;
        public final int[] foreground, background;
        /** Index into text of the visible terminal cursor; -1 when hidden. */
        public final int cursorIndex;
        Frame(String text, int[] foreground, int[] background, int cursorIndex) {
            this.text = text; this.foreground = foreground; this.background = background;
            this.cursorIndex = cursorIndex;
        }
    }
    private Cell[][] cells;
    private int rows=24, cols=80, row, col, savedRow, savedCol, top, bottom;
    private int fg=DEFAULT_FG, bg=DEFAULT_BG;
    private boolean bold, inverse, alternate, pendingWrap, cursorVisible=true;
    private Cell[][] primary;
    private int originalRow, originalCol;
    private int state;
    private final StringBuilder control = new StringBuilder();
    private final StringBuilder response = new StringBuilder();
    private final ArrayDeque<String> history = new ArrayDeque<>();
    private char highSurrogate;

    public TerminalScreen() { cells = new Cell[rows][cols]; bottom = rows - 1; }
    public int rows() { return rows; }
    public int columns() { return cols; }
    public String drainResponse() { String s=response.toString(); response.setLength(0); return s; }
    public void clear() {
        cells = new Cell[rows][cols];
        history.clear(); row=col=0; pendingWrap=false;
    }
    public void resize(int requestedRows, int requestedCols) {
        int r=Math.max(4, Math.min(100, requestedRows));
        int c=Math.max(12, Math.min(240, requestedCols));
        if(r==rows && c==cols) return;
        Cell[][] resized=new Cell[r][c];
        for(int y=0; y<Math.min(r,rows); y++)
            System.arraycopy(cells[y],0,resized[y],0,Math.min(c,cols));
        // Resize BOTH buffers so leaving a full-screen editor never destroys
        // the primary shell text after IME/rotation changes terminal geometry.
        if(alternate && primary!=null){
            Cell[][] resizedPrimary=new Cell[r][c];
            for(int y=0;y<Math.min(r,primary.length);y++)
                System.arraycopy(primary[y],0,resizedPrimary[y],0,
                    Math.min(c,primary[y].length));
            primary=resizedPrimary;
            originalRow=Math.min(originalRow,r-1);
            originalCol=Math.min(originalCol,c-1);
        }
        cells=resized;
        rows=r; cols=c; row=Math.min(row,r-1); col=Math.min(col,c-1);
        top=0; bottom=r-1; pendingWrap=false;
    }
    private static int width(int cp) {
        if (cp==0) return 0;
        int type=Character.getType(cp);
        if(type==Character.NON_SPACING_MARK || type==Character.ENCLOSING_MARK
                || type==Character.COMBINING_SPACING_MARK) return 0;
        if((cp>=0x1100 && cp<=0x115f) || (cp>=0x2329 && cp<=0x232a)
                || (cp>=0x2e80 && cp<=0xa4cf) || (cp>=0xac00 && cp<=0xd7a3)
                || (cp>=0xf900 && cp<=0xfaff) || (cp>=0xfe10 && cp<=0xfe6f)
                || (cp>=0xff01 && cp<=0xff60) || (cp>=0xffe0 && cp<=0xffe6)
                || (cp>=0x1f300 && cp<=0x1faff) || (cp>=0x20000 && cp<=0x3fffd))
            return 2;
        return 1;
    }
    private void put(int cp) {
        int width=width(cp);
        if(width==0) {
            int x=Math.max(0,col-1);
            if(cells[row][x]!=null) cells[row][x].glyph+=new String(Character.toChars(cp));
            return;
        }
        if(pendingWrap || col+width>cols) { newline(); col=0; pendingWrap=false; }
        int front=inverse?bg:fg, back=inverse?fg:bg;
        cells[row][col]=new Cell(new String(Character.toChars(cp)),front,back,bold);
        if(width==2 && col+1<cols) cells[row][col+1]=new Cell("",front,back,bold);
        col+=width;
        if(col>=cols){ col=cols-1; pendingWrap=true; }
    }
    private String rowText(Cell[] line) {
        int end=line.length;
        while(end>0 && (line[end-1]==null || line[end-1].glyph.isEmpty())) end--;
        StringBuilder out=new StringBuilder();
        for(int x=0;x<end;x++) out.append(line[x]==null?" ":line[x].glyph);
        return out.toString();
    }
    private void newline() {
        if(row==bottom) { scrollUp(1); }
        else row=Math.min(rows-1,row+1);
        pendingWrap=false;
    }
    private void scrollUp(int count) {
        for(int n=0;n<count;n++) {
            if(!alternate && top==0) {
                history.addLast(rowText(cells[top]));
                if(history.size()>MAX_HISTORY) history.removeFirst();
            }
            for(int y=top;y<bottom;y++) cells[y]=cells[y+1];
            cells[bottom]=new Cell[cols];
        }
    }
    private void scrollDown(int count) {
        for(int n=0;n<count;n++) {
            for(int y=bottom;y>top;y--) cells[y]=cells[y-1];
            cells[top]=new Cell[cols];
        }
    }
    private void alternate(boolean enable) {
        if(enable && !alternate) {
            primary=cells; originalRow=row; originalCol=col;
            cells=new Cell[rows][cols]; row=col=0; alternate=true;
        } else if(!enable && alternate) {
            if(primary!=null && primary.length==rows && primary[0].length==cols) cells=primary;
            else cells=new Cell[rows][cols];
            primary=null; row=Math.min(originalRow,rows-1); col=Math.min(originalCol,cols-1);
            alternate=false;
        }
        pendingWrap=false;
    }
    private static int[] numbers(String s) {
        String[] parts=s.split(";",-1);
        int[] result=new int[Math.max(1,Math.min(parts.length,32))];
        for(int i=0;i<result.length;i++) try {
            result[i]=Integer.parseInt(parts[i].replaceAll("[^0-9]",""));
        } catch(NumberFormatException e) { result[i]=0; }
        return result;
    }
    private static int param(int[] p,int index,int fallback){
        return index<p.length && p[index]>0?p[index]:fallback;
    }
    private void move(int y,int x) {
        row=Math.max(0,Math.min(rows-1,y));
        col=Math.max(0,Math.min(cols-1,x));
        pendingWrap=false;
    }
    private void eraseLine(int mode) {
        int start=mode==0?col:0, end=mode==1?col+1:cols;
        for(int x=start;x<Math.min(end,cols);x++) cells[row][x]=null;
    }
    private void eraseDisplay(int mode) {
        if(mode==3) { history.clear(); return; }
        if(mode==2) {
            // CSI 2 J erases the display but MUST preserve the cursor position.
            cells=new Cell[rows][cols]; pendingWrap=false; return;
        }
        if(mode==0) {
            eraseLine(0);
            for(int y=row+1;y<rows;y++) cells[y]=new Cell[cols];
        } else if(mode==1) {
            eraseLine(1);
            for(int y=0;y<row;y++) cells[y]=new Cell[cols];
        }
    }
    private static int palette(int n) {
        int[] basic={0x151923,0xd75050,0x43bd8b,0xe9ba64,0x729ded,0xc48dec,0x73c7ce,0xe1e8ef,
            0x6a7585,0xff7777,0x69e4a4,0xffd78b,0x95b7ff,0xe3b7ff,0xa3f0ff,0xffffff};
        if(n<16) return basic[Math.max(n,0)];
        if(n>=232){int v=8+(n-232)*10;return (v<<16)|(v<<8)|v;}
        int v=n-16;int[] cube={0,95,135,175,215,255};
        return (cube[v/36]<<16)|(cube[(v/6)%6]<<8)|cube[v%6];
    }
    private void colors(int[] p) {
        if(p.length==1 && p[0]==0) {fg=DEFAULT_FG;bg=DEFAULT_BG;bold=false;inverse=false;return;}
        for(int i=0;i<p.length;i++){
            int c=p[i];
            if(c==0){fg=DEFAULT_FG;bg=DEFAULT_BG;bold=false;inverse=false;}
            else if(c==1) bold=true;
            else if(c==22) bold=false;
            else if(c==7) inverse=true;
            else if(c==27) inverse=false;
            else if(c==39) fg=DEFAULT_FG;
            else if(c==49) bg=DEFAULT_BG;
            else if(c>=30 && c<=37) fg=palette(c-30);
            else if(c>=90 && c<=97) fg=palette(c-90+8);
            else if(c>=40 && c<=47) bg=palette(c-40);
            else if(c>=100 && c<=107) bg=palette(c-100+8);
            else if((c==38 || c==48) && i+1<p.length) {
                boolean front=c==38;
                int next=p[++i], color=-1;
                if(next==5 && i+1<p.length) color=palette(Math.min(255,p[++i]));
                else if(next==2 && i+3<p.length) {
                    int r=Math.min(255,p[++i]),g=Math.min(255,p[++i]),b=Math.min(255,p[++i]);
                    color=(r<<16)|(g<<8)|b;
                }
                if(color>=0){if(front)fg=color;else bg=color;}
            }
        }
    }
    private void csi(char op,String input){
        boolean privateMode=input.startsWith("?");
        int[] p=numbers(input);
        int n=param(p,0,1);
        if(privateMode) {
            if(op=='h'||op=='l') for(int code:p){
                if(code==1049 || code==47 || code==1047) alternate(op=='h');
                else if(code==25) cursorVisible=op=='h';
            }
            return;
        }
        switch(op){
            case 'm': colors(p);break;
            case 'A': move(row-n,col);break;
            case 'B': move(row+n,col);break;
            case 'C': move(row,col+n);break;
            case 'D': move(row,col-n);break;
            case 'E': move(row+n,0);break;
            case 'F': move(row-n,0);break;
            case 'G': move(row,n-1);break;
            case 'd': move(n-1,col);break;
            case 'H':case 'f':move(n-1,param(p,1,1)-1);break;
            case 'J':eraseDisplay(p[0]);break;
            case 'K':eraseLine(p[0]);break;
            case 'X':
                for(int x=col;x<Math.min(cols,col+n);x++)cells[row][x]=null;
                break;
            case 'P':
                for(int x=col;x<cols;x++)cells[row][x]=x+n<cols?cells[row][x+n]:null;
                break;
            case '@':
                for(int x=cols-1;x>=col;x--)cells[row][x]=x-n>=col?cells[row][x-n]:null;
                break;
            case 'L':
                if(row>=top && row<=bottom){
                    int save=top;top=row;scrollDown(Math.min(n,bottom-top+1));top=save;
                }break;
            case 'M':
                if(row>=top && row<=bottom){
                    int save=top;top=row;scrollUp(Math.min(n,bottom-top+1));top=save;
                }break;
            case 'S':scrollUp(Math.min(n,bottom-top+1));break;
            case 'T':scrollDown(Math.min(n,bottom-top+1));break;
            case 'r':
                top=Math.min(rows-1,n-1);bottom=Math.min(rows-1,param(p,1,rows)-1);
                if(top>=bottom){top=0;bottom=rows-1;}
                move(0,0);break;
            case 's':savedRow=row;savedCol=col;break;
            case 'u':move(savedRow,savedCol);break;
            case 'n':if(p[0]==6)response.append("\u001b[").append(row+1).append(';').append(col+1).append('R');
                     else if(p[0]==5)response.append("\u001b[0n");
                     break;
            case 'c':response.append("\u001b[?1;2c");break;
            default:break;
        }
    }
    public void append(String s) {
        for(int i=0;i<s.length();i++){
            char ch=s.charAt(i);
            if(state==1){
                state=0;
                if(ch=='['){state=2;control.setLength(0);}
                else if(ch==']'){state=3;control.setLength(0);}
                else if(ch=='7'){savedRow=row;savedCol=col;}
                else if(ch=='8'){move(savedRow,savedCol);}
                else if(ch=='D')newline();
                else if(ch=='M'){if(row==top)scrollDown(1);else move(row-1,col);}
                else if(ch=='c'){clear();fg=DEFAULT_FG;bg=DEFAULT_BG;
                    bold=false;inverse=false;cursorVisible=true;}
                continue;
            }
            if(state==2){
                if(ch>='@' && ch<='~'){csi(ch,control.toString());state=0;}
                else if(control.length()<96)control.append(ch);else state=0;
                continue;
            }
            if(state==3){
                if(ch==7)state=0;else if(ch==27)state=4;
                continue;
            }
            if(state==4){state=ch=='\\'?0:3;continue;}
            if(ch==27){state=1;continue;}
            if(ch=='\r'){col=0;pendingWrap=false;continue;}
            if(ch=='\n'){newline();continue;}
            if(ch=='\b'){move(row,col-1);continue;}
            if(ch=='\t'){int next=Math.min(cols-1,(col/8+1)*8);move(row,next);continue;}
            if(ch<32 || ch==127)continue;
            if(highSurrogate!=0){
                if(Character.isLowSurrogate(ch)){
                    int cp=Character.toCodePoint(highSurrogate,ch);
                    highSurrogate=0;put(cp);continue;
                }
                put(0xfffd);highSurrogate=0;
            }
            if(Character.isHighSurrogate(ch)){highSurrogate=ch;continue;}
            put(Character.isLowSurrogate(ch)?0xfffd:ch);
        }
    }
    public Frame frame() {
        StringBuilder text=new StringBuilder();
        List<Integer> f=new ArrayList<>(),b=new ArrayList<>();
        if(!alternate) for(String line:history)appendLine(text,f,b,line);
        int last=rows-1;
        // Editors/tmux paint the entire alternate-screen grid. Do not collapse
        // its blank rows, which would shift the cursor and status lines.
        if(!alternate) {
            while(last>0 && rowText(cells[last]).isEmpty())last--;
            if(cursorVisible) last=Math.max(last,row);
        }
        int cursorIndex=-1;
        for(int y=0;y<=last;y++){
            if(text.length()>0){text.append('\n');f.add(DEFAULT_FG);b.add(DEFAULT_BG);}
            int end=cols;
            if(!alternate) {
                while(end>0 && (cells[y][end-1]==null ||
                        cells[y][end-1].glyph.isEmpty()))end--;
                if(cursorVisible && y==row) end=Math.max(end,col+1);
            }
            for(int x=0;x<end;x++){
                if(cursorVisible && y==row && x==col) cursorIndex=text.length();
                Cell cell=cells[y][x];
                String glyph=cell==null?" ":cell.glyph;
                int color=cell==null?DEFAULT_FG:cell.fg;
                int back=cell==null?DEFAULT_BG:cell.bg;
                text.append(glyph);
                for(int z=0;z<glyph.length();z++){f.add(color);b.add(back);}
            }
        }
        int[] foreground=new int[f.size()],background=new int[b.size()];
        for(int i=0;i<f.size();i++){foreground[i]=f.get(i);background[i]=b.get(i);}
        return new Frame(text.toString(),foreground,background,cursorIndex);
    }
    private static void appendLine(StringBuilder text,List<Integer> f,List<Integer>b,String line){
        if(text.length()>0){text.append('\n');f.add(DEFAULT_FG);b.add(DEFAULT_BG);}
        text.append(line);
        for(int j=0;j<line.length();j++){f.add(DEFAULT_FG);b.add(DEFAULT_BG);}
    }
    public String render(){return frame().text;}
}
