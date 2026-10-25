package dev.asl.app.terminal;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * A compact VT100/xterm-compatible terminal emulator core. Pure Java: no Android dependencies, so it is
 * unit-testable on the JVM.
 *
 * Supported: UTF-8, wide characters, cursor movement, erase/insert/delete, scroll regions, scrollback,
 * alternate screen, SGR (16/256/truecolor, bold, italic, underline, inverse, strike), DEC line drawing,
 * tab stops, OSC titles, bracketed paste and application-cursor mode flags, and the usual device reports.
 *
 * Not supported (documented limits): mouse reporting is only recorded as a mode, no reflow on resize,
 * zero-width/combining characters are dropped, no sixel/images.
 *
 * Thread-safety: all public methods are synchronized on this object; a renderer must hold the lock
 * ({@code synchronized (emulator) { ... }}) while reading rows.
 */
public final class TerminalEmulator {

    public interface Listener {
        /** Bytes the terminal wants to send back to the process (device reports). */
        void onWrite(byte[] data);

        void onTitleChanged(String title);

        void onBell();

        /** Called after each feed() batch. */
        void onScreenChanged();
    }

    public static final int FLAG_BOLD = 1;
    public static final int FLAG_ITALIC = 2;
    public static final int FLAG_UNDERLINE = 4;
    public static final int FLAG_INVERSE = 8;
    public static final int FLAG_DIM = 16;
    public static final int FLAG_STRIKE = 32;
    public static final int FLAG_WIDE = 64;
    public static final int FLAG_WIDE_TAIL = 128;

    /** 0 means "default colour" in fg/bg arrays; explicit colours are 0xFFRRGGBB. */
    public static final int COLOR_DEFAULT = 0;

    /** xterm 256-colour palette; the first 16 entries follow the Windows Terminal "Campbell" scheme. */
    public static final int[] PALETTE = buildPalette();

    private static int[] buildPalette() {
        int[] p = new int[256];
        int[] base = {
            0x0C0C0C, 0xC50F1F, 0x13A10E, 0xC19C00, 0x0037DA, 0x881798, 0x3A96DD, 0xCCCCCC,
            0x767676, 0xE74856, 0x16C60C, 0xF9F1A5, 0x3B78FF, 0xB4009E, 0x61D6D6, 0xF2F2F2,
        };
        for (int i = 0; i < 16; i++) p[i] = 0xFF000000 | base[i];
        int[] levels = {0, 95, 135, 175, 215, 255};
        for (int i = 0; i < 216; i++) {
            int r = levels[i / 36], g = levels[(i / 6) % 6], b = levels[i % 6];
            p[16 + i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        for (int i = 0; i < 24; i++) {
            int v = 8 + 10 * i;
            p[232 + i] = 0xFF000000 | (v << 16) | (v << 8) | v;
        }
        return p;
    }

    /** One screen line. Arrays are indexed by column. */
    public static final class Row {
        public final int[] cp;
        public final int[] fg;
        public final int[] bg;
        public final short[] fl;
        public boolean wrapped;

        Row(int cols, int bgColor) {
            cp = new int[cols];
            fg = new int[cols];
            bg = new int[cols];
            fl = new short[cols];
            Arrays.fill(cp, ' ');
            if (bgColor != 0) Arrays.fill(bg, bgColor);
        }

        private Row(Row src, int cols) {
            this(cols, 0);
            int n = Math.min(cols, src.cp.length);
            System.arraycopy(src.cp, 0, cp, 0, n);
            System.arraycopy(src.fg, 0, fg, 0, n);
            System.arraycopy(src.bg, 0, bg, 0, n);
            System.arraycopy(src.fl, 0, fl, 0, n);
            wrapped = src.wrapped;
        }

        void fill(int from, int to, int bgColor) {
            for (int i = Math.max(0, from); i < Math.min(to, cp.length); i++) {
                cp[i] = ' ';
                fg[i] = 0;
                bg[i] = bgColor;
                fl[i] = 0;
            }
        }

        boolean isBlank() {
            for (int i = 0; i < cp.length; i++) if (cp[i] != ' ' || bg[i] != 0) return false;
            return true;
        }

        public String text() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < cp.length; i++) {
                if ((fl[i] & FLAG_WIDE_TAIL) != 0) continue;
                sb.appendCodePoint(cp[i] == 0 ? ' ' : cp[i]);
            }
            int end = sb.length();
            while (end > 0 && sb.charAt(end - 1) == ' ') end--;
            sb.setLength(end);
            return sb.toString();
        }
    }

    // ---- state ------------------------------------------------------------------------------------

    private final Listener listener;
    private final int maxScrollback;
    private int cols, rows;
    private Row[] screen, mainScreen, altScreen;
    private boolean usingAlt;
    private final ArrayList<Row> scrollback = new ArrayList<>();

    private int cx, cy;
    private boolean pendingWrap;
    private int top, bottom;
    private int curFg, curBg, curFlags;
    private boolean originMode, autoWrap = true, insertMode, cursorVisible = true;
    private boolean appCursorKeys, bracketedPaste, appKeypad;
    private int cursorStyle; // 0 block, 1 underline, 2 bar
    private int mouseMode;
    private boolean mouseSgr;
    private boolean[] tabs;
    private int g0, g1, gl; // charset: 0 = ASCII, 1 = DEC graphics; gl selects g0/g1

    private int sCx, sCy, sFg, sBg, sFlags, sG0, sG1, sGl;
    private boolean sOrigin, sPending;

    // parser
    private static final int S_GROUND = 0, S_ESC = 1, S_ESC_INTER = 2, S_CSI = 3, S_OSC = 4, S_OSC_ESC = 5, S_STRING = 6, S_STRING_ESC = 7;
    private int state = S_GROUND;
    private final int[] params = new int[32];
    private int nParams, curParam;
    private boolean hasParam;
    private char csiPrefix, csiInter;
    private char escInter;
    private final StringBuilder osc = new StringBuilder();
    private int utf8Need, utf8Cp;
    private int lastPrinted;

    public TerminalEmulator(int cols, int rows, int maxScrollback, Listener listener) {
        this.cols = Math.max(2, cols);
        this.rows = Math.max(1, rows);
        this.maxScrollback = Math.max(0, maxScrollback);
        this.listener = listener;
        mainScreen = newScreen(this.rows, this.cols);
        altScreen = newScreen(this.rows, this.cols);
        screen = mainScreen;
        top = 0;
        bottom = this.rows - 1;
        resetTabs();
    }

    private Row[] newScreen(int r, int c) {
        Row[] s = new Row[r];
        for (int i = 0; i < r; i++) s[i] = new Row(c, 0);
        return s;
    }

    private void resetTabs() {
        tabs = new boolean[cols];
        for (int i = 8; i < cols; i += 8) tabs[i] = true;
    }

    // ---- public read API --------------------------------------------------------------------------

    public synchronized int getCols() { return cols; }
    public synchronized int getRows() { return rows; }
    public synchronized int getCursorCol() { return cx; }
    public synchronized int getCursorRow() { return cy; }
    public synchronized boolean isCursorVisible() { return cursorVisible; }
    public synchronized int getCursorStyle() { return cursorStyle; }
    public synchronized boolean isAppCursorKeys() { return appCursorKeys; }
    public synchronized boolean isBracketedPaste() { return bracketedPaste; }
    public synchronized boolean isAltScreen() { return usingAlt; }
    public synchronized int getMouseMode() { return mouseMode; }
    public synchronized boolean isMouseSgr() { return mouseSgr; }
    public synchronized int getScrollbackSize() { return scrollback.size(); }

    /** Absolute row index: 0 = oldest scrollback line, scrollbackSize = first screen row. Caller holds the lock. */
    public Row rowAt(int absolute) {
        int sb = scrollback.size();
        if (absolute < 0) return null;
        if (absolute < sb) return scrollback.get(absolute);
        int idx = absolute - sb;
        return idx < rows ? screen[idx] : null;
    }

    /** Text of the visible screen (for "copy screen"). */
    public synchronized String screenText() {
        StringBuilder sb = new StringBuilder();
        int last = rows - 1;
        while (last > 0 && screen[last].text().isEmpty()) last--;
        for (int i = 0; i <= last; i++) {
            sb.append(screen[i].text());
            if (i < last) sb.append('\n');
        }
        return sb.toString();
    }

    // ---- input ------------------------------------------------------------------------------------

    public synchronized void feed(byte[] buf, int off, int len) {
        for (int i = off; i < off + len; i++) feedByte(buf[i] & 0xFF);
        listener.onScreenChanged();
    }

    public void feed(String text) {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        feed(b, 0, b.length);
    }

    private void feedByte(int b) {
        if (utf8Need > 0) {
            if ((b & 0xC0) == 0x80) {
                utf8Cp = (utf8Cp << 6) | (b & 0x3F);
                if (--utf8Need == 0) consume(utf8Cp);
                return;
            }
            utf8Need = 0;
            consume(0xFFFD);
        }
        if (b < 0x80) consume(b);
        else if ((b & 0xE0) == 0xC0) { utf8Need = 1; utf8Cp = b & 0x1F; }
        else if ((b & 0xF0) == 0xE0) { utf8Need = 2; utf8Cp = b & 0x0F; }
        else if ((b & 0xF8) == 0xF0) { utf8Need = 3; utf8Cp = b & 0x07; }
        else consume(0xFFFD);
    }

    private void consume(int cp) {
        if (cp == 0x18 || cp == 0x1A) { state = S_GROUND; return; }
        if (cp == 0x1B) {
            if (state == S_OSC) state = S_OSC_ESC;
            else if (state == S_STRING) state = S_STRING_ESC;
            else state = S_ESC;
            return;
        }
        switch (state) {
            case S_GROUND:
                if (cp < 0x20) control(cp);
                else if (cp != 0x7F) print(cp);
                break;
            case S_ESC:
                if (cp < 0x20) control(cp); else escDispatch(cp);
                break;
            case S_ESC_INTER:
                designate(escInter, cp);
                state = S_GROUND;
                break;
            case S_CSI:
                if (cp < 0x20) control(cp); else csiByte(cp);
                break;
            case S_OSC:
                if (cp == 0x07) oscEnd();
                else if (osc.length() < 4096) osc.appendCodePoint(cp);
                break;
            case S_OSC_ESC:
                if (cp == '\\') oscEnd();
                else { state = S_ESC; escDispatch(cp); }
                break;
            case S_STRING:
                break;
            case S_STRING_ESC:
                state = cp == '\\' ? S_GROUND : S_STRING;
                break;
            default:
                state = S_GROUND;
        }
    }

    private void control(int c) {
        switch (c) {
            case 0x07: listener.onBell(); break;
            case 0x08: if (cx > 0) cx--; pendingWrap = false; break;
            case 0x09: cx = nextTab(cx); pendingWrap = false; break;
            case 0x0A: case 0x0B: case 0x0C: pendingWrap = false; index(); break;
            case 0x0D: cx = 0; pendingWrap = false; break;
            case 0x0E: gl = 1; break;
            case 0x0F: gl = 0; break;
            default: break;
        }
    }

    private int nextTab(int from) {
        for (int i = from + 1; i < cols; i++) if (tabs[i]) return i;
        return cols - 1;
    }

    private int prevTab(int from) {
        for (int i = from - 1; i > 0; i--) if (tabs[i]) return i;
        return 0;
    }

    // ---- printing ---------------------------------------------------------------------------------

    private static final int[] DEC_GRAPHICS = {
        0x00A0, 0x25C6, 0x2592, 0x2409, 0x240C, 0x240D, 0x240A, 0x00B0, 0x00B1, 0x2424, 0x240B, 0x2518, 0x2510, 0x250C,
        0x2514, 0x253C, 0x23BA, 0x23BB, 0x2500, 0x23BC, 0x23BD, 0x251C, 0x2524, 0x2534, 0x252C, 0x2502, 0x2264, 0x2265,
        0x03C0, 0x2260, 0x00A3, 0x00B7,
    }; // indices for 0x5F..0x7E

    static int charWidth(int cp) {
        if (cp < 0x300) return 1;
        int t = Character.getType(cp);
        if (t == Character.NON_SPACING_MARK || t == Character.ENCLOSING_MARK || t == Character.FORMAT) return 0;
        if ((cp >= 0x1100 && cp <= 0x115F) || (cp >= 0x2E80 && cp <= 0xA4CF) || (cp >= 0xAC00 && cp <= 0xD7A3)
            || (cp >= 0xF900 && cp <= 0xFAFF) || (cp >= 0xFE30 && cp <= 0xFE6F) || (cp >= 0xFF00 && cp <= 0xFF60)
            || (cp >= 0xFFE0 && cp <= 0xFFE6) || (cp >= 0x1F300 && cp <= 0x1F64F) || (cp >= 0x1F900 && cp <= 0x1F9FF)
            || (cp >= 0x20000 && cp <= 0x3FFFD)) return 2;
        return 1;
    }

    private void print(int cp) {
        int set = gl == 0 ? g0 : g1;
        if (set == 1 && cp >= 0x5F && cp <= 0x7E) cp = DEC_GRAPHICS[cp - 0x5F];
        int w = charWidth(cp);
        if (w == 0) return;
        if (pendingWrap && autoWrap) wrapLine();
        pendingWrap = false;
        if (w == 2 && cx == cols - 1) {
            if (autoWrap) wrapLine(); else return;
        }
        Row row = screen[cy];
        if (insertMode) insertChars(w);
        clearWideAt(row, cx);
        row.cp[cx] = cp;
        row.fg[cx] = curFg;
        row.bg[cx] = curBg;
        row.fl[cx] = (short) (curFlags | (w == 2 ? FLAG_WIDE : 0));
        if (w == 2) {
            clearWideAt(row, cx + 1);
            row.cp[cx + 1] = 0;
            row.fg[cx + 1] = curFg;
            row.bg[cx + 1] = curBg;
            row.fl[cx + 1] = (short) (curFlags | FLAG_WIDE_TAIL);
        }
        lastPrinted = cp;
        cx += w;
        if (cx >= cols) {
            cx = cols - 1;
            pendingWrap = true;
        }
    }

    /** Overwriting half of a wide character blanks the other half. */
    private void clearWideAt(Row row, int x) {
        if (x < 0 || x >= cols) return;
        if ((row.fl[x] & FLAG_WIDE) != 0 && x + 1 < cols) {
            row.cp[x + 1] = ' ';
            row.fl[x + 1] = 0;
        }
        if ((row.fl[x] & FLAG_WIDE_TAIL) != 0 && x > 0) {
            row.cp[x - 1] = ' ';
            row.fl[x - 1] = 0;
        }
    }

    private void wrapLine() {
        screen[cy].wrapped = true;
        cx = 0;
        pendingWrap = false;
        index();
    }

    private void index() {
        if (cy == bottom) scrollUp(1);
        else if (cy < rows - 1) cy++;
    }

    private void reverseIndex() {
        if (cy == top) scrollDown(1);
        else if (cy > 0) cy--;
    }

    private void scrollUp(int n) {
        n = Math.min(n, bottom - top + 1);
        boolean toScrollback = top == 0 && bottom == rows - 1 && !usingAlt;
        for (int k = 0; k < n; k++) {
            Row removed = screen[top];
            if (toScrollback && maxScrollback > 0) {
                scrollback.add(removed);
                while (scrollback.size() > maxScrollback) scrollback.remove(0);
            }
            for (int i = top; i < bottom; i++) screen[i] = screen[i + 1];
            screen[bottom] = new Row(cols, curBg);
        }
    }

    private void scrollDown(int n) {
        n = Math.min(n, bottom - top + 1);
        for (int k = 0; k < n; k++) {
            for (int i = bottom; i > top; i--) screen[i] = screen[i - 1];
            screen[top] = new Row(cols, curBg);
        }
    }

    // ---- ESC sequences ----------------------------------------------------------------------------

    private void escDispatch(int c) {
        state = S_GROUND;
        switch (c) {
            case '[': resetCsi(); state = S_CSI; break;
            case ']': osc.setLength(0); state = S_OSC; break;
            case 'P': case 'X': case '^': case '_': state = S_STRING; break;
            case '(': case ')': case '*': case '+': escInter = (char) c; state = S_ESC_INTER; break;
            case '#': case '%': case ' ': escInter = (char) c; state = S_ESC_INTER; break;
            case '7': saveCursor(); break;
            case '8': restoreCursor(); break;
            case 'D': pendingWrap = false; index(); break;
            case 'E': cx = 0; pendingWrap = false; index(); break;
            case 'M': pendingWrap = false; reverseIndex(); break;
            case 'H': if (cx < cols) tabs[cx] = true; break;
            case 'c': fullReset(); break;
            case '=': appKeypad = true; break;
            case '>': appKeypad = false; break;
            default: break;
        }
    }

    private void designate(char which, int fin) {
        if (which == '(') g0 = fin == '0' ? 1 : 0;
        else if (which == ')') g1 = fin == '0' ? 1 : 0;
        // '#', '%', ' ' and G2/G3: accepted and ignored
    }

    private void saveCursor() {
        sCx = cx; sCy = cy; sFg = curFg; sBg = curBg; sFlags = curFlags;
        sG0 = g0; sG1 = g1; sGl = gl; sOrigin = originMode; sPending = pendingWrap;
    }

    private void restoreCursor() {
        cx = Math.min(sCx, cols - 1);
        cy = Math.min(sCy, rows - 1);
        curFg = sFg; curBg = sBg; curFlags = sFlags;
        g0 = sG0; g1 = sG1; gl = sGl; originMode = sOrigin; pendingWrap = sPending;
    }

    private void fullReset() {
        state = S_GROUND;
        mainScreen = newScreen(rows, cols);
        altScreen = newScreen(rows, cols);
        usingAlt = false;
        screen = mainScreen;
        scrollback.clear();
        cx = cy = 0;
        pendingWrap = false;
        top = 0;
        bottom = rows - 1;
        curFg = curBg = curFlags = 0;
        originMode = false;
        autoWrap = true;
        insertMode = false;
        cursorVisible = true;
        appCursorKeys = bracketedPaste = appKeypad = false;
        mouseMode = 0;
        mouseSgr = false;
        g0 = g1 = gl = 0;
        cursorStyle = 0;
        resetTabs();
    }

    // ---- CSI --------------------------------------------------------------------------------------

    private void resetCsi() {
        nParams = 0;
        curParam = 0;
        hasParam = false;
        csiPrefix = 0;
        csiInter = 0;
    }

    private void pushParam() {
        if (nParams < params.length) params[nParams++] = hasParam ? curParam : -1;
        curParam = 0;
        hasParam = false;
    }

    private void csiByte(int cp) {
        if (cp >= '0' && cp <= '9') {
            hasParam = true;
            curParam = Math.min(curParam * 10 + (cp - '0'), 65535);
        } else if (cp == ';' || cp == ':') {
            pushParam();
        } else if (cp >= 0x3C && cp <= 0x3F) {
            csiPrefix = (char) cp;
        } else if (cp >= 0x20 && cp <= 0x2F) {
            csiInter = (char) cp;
        } else if (cp >= 0x40 && cp <= 0x7E) {
            if (hasParam || nParams > 0) pushParam();
            state = S_GROUND;
            csiDispatch((char) cp);
        } else {
            state = S_GROUND; // malformed
        }
    }

    private int p(int i, int def) {
        return i < nParams && params[i] > 0 ? params[i] : def;
    }

    private int p0(int i) { // allows an explicit 0
        return i < nParams && params[i] >= 0 ? params[i] : 0;
    }

    private void csiDispatch(char f) {
        if (csiInter != 0) {
            if (csiInter == ' ' && f == 'q') cursorStyle = p0(0) <= 2 ? 0 : (p0(0) <= 4 ? 1 : 2);
            return; // '!p' soft reset etc.: ignored
        }
        if (csiPrefix == '?') {
            if (f == 'h' || f == 'l') for (int i = 0; i < nParams; i++) privateMode(params[i], f == 'h');
            return;
        }
        if (csiPrefix == '>') {
            if (f == 'c') reply("\u001b[>0;276;0c");
            return;
        }
        if (csiPrefix != 0) return;

        switch (f) {
            case '@': insertChars(p(0, 1)); break;
            case 'A': cy = Math.max(cy - p(0, 1), originMode ? top : 0); pendingWrap = false; break;
            case 'B': case 'e': cy = Math.min(cy + p(0, 1), originMode ? bottom : rows - 1); pendingWrap = false; break;
            case 'C': case 'a': cx = Math.min(cx + p(0, 1), cols - 1); pendingWrap = false; break;
            case 'D': cx = Math.max(cx - p(0, 1), 0); pendingWrap = false; break;
            case 'E': cy = Math.min(cy + p(0, 1), rows - 1); cx = 0; pendingWrap = false; break;
            case 'F': cy = Math.max(cy - p(0, 1), 0); cx = 0; pendingWrap = false; break;
            case 'G': case '`': cx = clamp(p(0, 1) - 1, 0, cols - 1); pendingWrap = false; break;
            case 'H': case 'f': moveTo(p(0, 1) - 1, p(1, 1) - 1); break;
            case 'I': for (int i = p(0, 1); i > 0; i--) cx = nextTab(cx); pendingWrap = false; break;
            case 'J': eraseDisplay(p0(0)); break;
            case 'K': eraseLine(p0(0)); break;
            case 'L': insertLines(p(0, 1)); break;
            case 'M': deleteLines(p(0, 1)); break;
            case 'P': deleteChars(p(0, 1)); break;
            case 'S': scrollUp(p(0, 1)); break;
            case 'T': scrollDown(p(0, 1)); break;
            case 'X': eraseChars(p(0, 1)); break;
            case 'Z': for (int i = p(0, 1); i > 0; i--) cx = prevTab(cx); pendingWrap = false; break;
            case 'b': for (int i = Math.min(p(0, 1), 1000); i > 0 && lastPrinted != 0; i--) print(lastPrinted); break;
            case 'd': cy = clamp(p(0, 1) - 1 + (originMode ? top : 0), 0, rows - 1); pendingWrap = false; break;
            case 'g':
                if (p0(0) == 0 && cx < cols) tabs[cx] = false;
                else if (p0(0) == 3) Arrays.fill(tabs, false);
                break;
            case 'h': case 'l':
                for (int i = 0; i < nParams; i++) if (params[i] == 4) insertMode = f == 'h';
                break;
            case 'm': sgr(); break;
            case 'n':
                if (p0(0) == 5) reply("\u001b[0n");
                else if (p0(0) == 6) reply("\u001b[" + (cy - (originMode ? top : 0) + 1) + ";" + (cx + 1) + "R");
                break;
            case 'c': if (p0(0) == 0) reply("\u001b[?62;c"); break;
            case 'r': {
                int t = p(0, 1) - 1, b = p(1, rows) - 1;
                if (t < b && b < rows) { top = t; bottom = b; moveTo(0, 0); }
                break;
            }
            case 's': saveCursor(); break;
            case 'u': restoreCursor(); break;
            case 't': if (p0(0) == 18) reply("\u001b[8;" + rows + ";" + cols + "t"); break;
            default: break;
        }
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private void moveTo(int row, int col) {
        int base = originMode ? top : 0;
        int max = originMode ? bottom : rows - 1;
        cy = clamp(row + base, base, max);
        cx = clamp(col, 0, cols - 1);
        pendingWrap = false;
    }

    private void privateMode(int mode, boolean on) {
        switch (mode) {
            case 1: appCursorKeys = on; break;
            case 6: originMode = on; moveTo(0, 0); break;
            case 7: autoWrap = on; break;
            case 25: cursorVisible = on; break;
            case 9: case 1000: case 1002: case 1003: mouseMode = on ? mode : 0; break;
            case 1006: mouseSgr = on; break;
            case 47: case 1047: switchScreen(on, false, mode == 1047 && !on); break;
            case 1049: switchScreen(on, true, true); break;
            case 2004: bracketedPaste = on; break;
            default: break;
        }
    }

    private void switchScreen(boolean toAlt, boolean saveRestoreCursor, boolean clearAlt) {
        if (toAlt == usingAlt) return;
        if (toAlt) {
            if (saveRestoreCursor) saveCursor();
            usingAlt = true;
            altScreen = newScreen(rows, cols);
            screen = altScreen;
            top = 0;
            bottom = rows - 1;
        } else {
            if (clearAlt) altScreen = newScreen(rows, cols);
            usingAlt = false;
            screen = mainScreen;
            top = 0;
            bottom = rows - 1;
            if (saveRestoreCursor) restoreCursor();
        }
        pendingWrap = false;
    }

    private void sgr() {
        if (nParams == 0) { resetAttrs(); return; }
        for (int i = 0; i < nParams; i++) {
            int v = params[i] < 0 ? 0 : params[i];
            switch (v) {
                case 0: resetAttrs(); break;
                case 1: curFlags |= FLAG_BOLD; break;
                case 2: curFlags |= FLAG_DIM; break;
                case 3: curFlags |= FLAG_ITALIC; break;
                case 4: case 21: curFlags |= FLAG_UNDERLINE; break;
                case 7: curFlags |= FLAG_INVERSE; break;
                case 9: curFlags |= FLAG_STRIKE; break;
                case 22: curFlags &= ~(FLAG_BOLD | FLAG_DIM); break;
                case 23: curFlags &= ~FLAG_ITALIC; break;
                case 24: curFlags &= ~FLAG_UNDERLINE; break;
                case 27: curFlags &= ~FLAG_INVERSE; break;
                case 29: curFlags &= ~FLAG_STRIKE; break;
                case 38: i = extendedColor(i, true); break;
                case 48: i = extendedColor(i, false); break;
                case 39: curFg = 0; break;
                case 49: curBg = 0; break;
                default:
                    if (v >= 30 && v <= 37) curFg = PALETTE[v - 30];
                    else if (v >= 40 && v <= 47) curBg = PALETTE[v - 40];
                    else if (v >= 90 && v <= 97) curFg = PALETTE[v - 90 + 8];
                    else if (v >= 100 && v <= 107) curBg = PALETTE[v - 100 + 8];
                    break;
            }
        }
    }

    /** Parses 38/48 ; 5 ; n  or  38/48 ; 2 ; r ; g ; b (also the colon form with an empty colour-space id). */
    private int extendedColor(int i, boolean foreground) {
        int color = 0;
        if (i + 1 < nParams && params[i + 1] == 5 && i + 2 < nParams) {
            color = PALETTE[clamp(params[i + 2], 0, 255)];
            i += 2;
        } else if (i + 1 < nParams && params[i + 1] == 2) {
            int a = i + 2;
            if (nParams - a >= 4 && params[a] == -1) a++; // 38:2::r:g:b
            if (a + 2 < nParams) {
                color = 0xFF000000 | (clamp(params[a], 0, 255) << 16) | (clamp(params[a + 1], 0, 255) << 8) | clamp(params[a + 2], 0, 255);
                i = a + 2;
            } else {
                i = nParams;
            }
        } else {
            return i;
        }
        if (foreground) curFg = color; else curBg = color;
        return i;
    }

    private void resetAttrs() {
        curFg = curBg = 0;
        curFlags = 0;
    }

    // ---- editing ----------------------------------------------------------------------------------

    private void eraseDisplay(int mode) {
        switch (mode) {
            case 0:
                screen[cy].fill(cx, cols, curBg);
                for (int y = cy + 1; y < rows; y++) screen[y] = new Row(cols, curBg);
                break;
            case 1:
                for (int y = 0; y < cy; y++) screen[y] = new Row(cols, curBg);
                screen[cy].fill(0, cx + 1, curBg);
                break;
            case 2:
                for (int y = 0; y < rows; y++) screen[y] = new Row(cols, curBg);
                break;
            case 3:
                scrollback.clear();
                break;
            default: break;
        }
    }

    private void eraseLine(int mode) {
        Row r = screen[cy];
        if (mode == 0) r.fill(cx, cols, curBg);
        else if (mode == 1) r.fill(0, cx + 1, curBg);
        else if (mode == 2) r.fill(0, cols, curBg);
    }

    private void insertChars(int n) {
        Row r = screen[cy];
        n = Math.min(n, cols - cx);
        for (int i = cols - 1; i >= cx + n; i--) copyCell(r, i - n, i);
        r.fill(cx, cx + n, curBg);
    }

    private void deleteChars(int n) {
        Row r = screen[cy];
        n = Math.min(n, cols - cx);
        for (int i = cx; i < cols - n; i++) copyCell(r, i + n, i);
        r.fill(cols - n, cols, curBg);
    }

    private void eraseChars(int n) {
        screen[cy].fill(cx, Math.min(cols, cx + n), curBg);
    }

    private void copyCell(Row r, int from, int to) {
        r.cp[to] = r.cp[from];
        r.fg[to] = r.fg[from];
        r.bg[to] = r.bg[from];
        r.fl[to] = r.fl[from];
    }

    private void insertLines(int n) {
        if (cy < top || cy > bottom) return;
        n = Math.min(n, bottom - cy + 1);
        for (int k = 0; k < n; k++) {
            for (int i = bottom; i > cy; i--) screen[i] = screen[i - 1];
            screen[cy] = new Row(cols, curBg);
        }
        cx = 0;
        pendingWrap = false;
    }

    private void deleteLines(int n) {
        if (cy < top || cy > bottom) return;
        n = Math.min(n, bottom - cy + 1);
        for (int k = 0; k < n; k++) {
            for (int i = cy; i < bottom; i++) screen[i] = screen[i + 1];
            screen[bottom] = new Row(cols, curBg);
        }
        cx = 0;
        pendingWrap = false;
    }

    // ---- OSC / replies ------------------------------------------------------------------------------

    private void oscEnd() {
        state = S_GROUND;
        String s = osc.toString();
        int semi = s.indexOf(';');
        if (semi > 0) {
            String cmd = s.substring(0, semi);
            if (cmd.equals("0") || cmd.equals("2") || cmd.equals("1")) listener.onTitleChanged(s.substring(semi + 1));
        }
    }

    private void reply(String s) {
        listener.onWrite(s.getBytes(StandardCharsets.UTF_8));
    }

    // ---- resize -----------------------------------------------------------------------------------

    /** Resizes the terminal. No reflow: lines are truncated/extended; lines leaving the top go to scrollback. */
    public synchronized void resize(int newCols, int newRows) {
        newCols = Math.max(2, newCols);
        newRows = Math.max(1, newRows);
        if (newCols == cols && newRows == rows) return;

        if (newCols != cols) {
            for (int i = 0; i < rows; i++) {
                mainScreen[i] = new Row(mainScreen[i], newCols);
                altScreen[i] = new Row(altScreen[i], newCols);
            }
            ArrayList<Row> resized = new ArrayList<>();
            for (Row r : scrollback) resized.add(new Row(r, newCols));
            scrollback.clear();
            scrollback.addAll(resized);
        }

        if (newRows != rows) {
            if (usingAlt) {
                altScreen = resizeSimple(altScreen, newRows, newCols);
                mainScreen = resizeSimple(mainScreen, newRows, newCols);
                cy = Math.min(cy, newRows - 1);
                sCy = Math.min(sCy, newRows - 1);
            } else {
                altScreen = resizeSimple(altScreen, newRows, newCols);
                mainScreen = resizeMain(mainScreen, newRows, newCols);
            }
        }

        cols = newCols;
        rows = newRows;
        screen = usingAlt ? altScreen : mainScreen;
        top = 0;
        bottom = rows - 1;
        cx = Math.min(cx, cols - 1);
        cy = Math.min(cy, rows - 1);
        sCx = Math.min(sCx, cols - 1);
        pendingWrap = false;
        resetTabs();
        listener.onScreenChanged();
    }

    private Row[] resizeSimple(Row[] old, int newRows, int c) {
        Row[] n = new Row[newRows];
        for (int i = 0; i < newRows; i++) n[i] = i < old.length ? old[i] : new Row(c, 0);
        return n;
    }

    private Row[] resizeMain(Row[] old, int newRows, int c) {
        int oldRows = old.length;
        Row[] n = new Row[newRows];
        if (newRows < oldRows) {
            int remove = oldRows - newRows;
            int dropBottom = 0;
            for (int y = oldRows - 1; y > cy && dropBottom < remove && old[y].isBlank(); y--) dropBottom++;
            int dropTop = remove - dropBottom;
            for (int i = 0; i < dropTop; i++) {
                if (maxScrollback > 0) {
                    scrollback.add(old[i]);
                    while (scrollback.size() > maxScrollback) scrollback.remove(0);
                }
            }
            for (int i = 0; i < newRows; i++) n[i] = old[dropTop + i];
            cy = Math.max(0, cy - dropTop);
        } else {
            int add = newRows - oldRows;
            int pull = Math.min(add, scrollback.size());
            Row[] pulled = new Row[pull];
            for (int i = pull - 1; i >= 0; i--) pulled[i] = scrollback.remove(scrollback.size() - 1);
            int idx = 0;
            for (int i = 0; i < pull; i++) n[idx++] = pulled[i];
            for (int i = 0; i < oldRows; i++) n[idx++] = old[i];
            while (idx < newRows) n[idx++] = new Row(c, 0);
            cy += pull;
        }
        return n;
    }
}
