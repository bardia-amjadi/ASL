package dev.asl.app.terminal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class TerminalEmulatorTest {

    private static final class Capture implements TerminalEmulator.Listener {
        final ByteArrayOutputStream written = new ByteArrayOutputStream();
        String title;
        int bells;

        @Override public void onWrite(byte[] data) { written.write(data, 0, data.length); }
        @Override public void onTitleChanged(String t) { title = t; }
        @Override public void onBell() { bells++; }
        @Override public void onScreenChanged() { }
        String sent() { return new String(written.toByteArray(), StandardCharsets.UTF_8); }
    }

    private Capture cap;

    private TerminalEmulator term(int cols, int rows) {
        cap = new Capture();
        return new TerminalEmulator(cols, rows, 100, cap);
    }

    private static String line(TerminalEmulator t, int screenRow) {
        synchronized (t) {
            return t.rowAt(t.getScrollbackSize() + screenRow).text();
        }
    }

    @Test public void plainTextAndNewline() {
        TerminalEmulator t = term(20, 5);
        t.feed("hello\r\nworld");
        assertEquals("hello", line(t, 0));
        assertEquals("world", line(t, 1));
        assertEquals(1, t.getCursorRow());
        assertEquals(5, t.getCursorCol());
    }

    @Test public void autowrapIsDeferredUntilNextChar() {
        TerminalEmulator t = term(5, 3);
        t.feed("abcde");
        assertEquals(0, t.getCursorRow());
        assertEquals(4, t.getCursorCol());
        t.feed("f");
        assertEquals("abcde", line(t, 0));
        assertEquals("f", line(t, 1));
        assertEquals(1, t.getCursorRow());
        assertEquals(1, t.getCursorCol());
    }

    @Test public void cursorPositioningAndErase() {
        TerminalEmulator t = term(20, 5);
        t.feed("xxxxx\u001b[2J\u001b[3;5Hhi");
        assertEquals("", line(t, 0));
        assertEquals("    hi", line(t, 2));
        t.feed("\u001b[3;5H\u001b[K");
        assertEquals("", line(t, 2));
    }

    @Test public void sgrColoursAndFlags() {
        TerminalEmulator t = term(20, 3);
        t.feed("\u001b[31;1mR\u001b[0mN\u001b[38;5;21mB\u001b[38;2;1;2;3mT\u001b[48;5;196mG");
        synchronized (t) {
            TerminalEmulator.Row r = t.rowAt(t.getScrollbackSize());
            assertEquals(TerminalEmulator.PALETTE[1], r.fg[0]);
            assertTrue((r.fl[0] & TerminalEmulator.FLAG_BOLD) != 0);
            assertEquals(0, r.fg[1]);
            assertEquals(0, r.fl[1]);
            assertEquals(TerminalEmulator.PALETTE[21], r.fg[2]);
            assertEquals(0xFF010203, r.fg[3]);
            assertEquals(TerminalEmulator.PALETTE[196], r.bg[4]);
        }
    }

    @Test public void colonTruecolourForm() {
        TerminalEmulator t = term(10, 2);
        t.feed("\u001b[38:2::10:20:30mX");
        synchronized (t) {
            assertEquals(0xFF0A141E, t.rowAt(t.getScrollbackSize()).fg[0]);
        }
    }

    @Test public void scrollingFillsScrollback() {
        TerminalEmulator t = term(10, 3);
        for (int i = 1; i <= 8; i++) t.feed("L" + i + "\r\n");
        assertEquals(6, t.getScrollbackSize());
        synchronized (t) {
            assertEquals("L1", t.rowAt(0).text());
            assertEquals("L6", t.rowAt(5).text());
        }
        assertEquals("L8", line(t, 1));
    }

    @Test public void utf8SplitAcrossFeeds() {
        TerminalEmulator t = term(10, 2);
        byte[] e = "\u00e9\u20ac".getBytes(StandardCharsets.UTF_8); // 2 + 3 bytes
        t.feed(e, 0, 1);
        t.feed(e, 1, 2);
        t.feed(e, 3, 2);
        assertEquals("\u00e9\u20ac", line(t, 0));
    }

    @Test public void wideCharactersTakeTwoCells() {
        TerminalEmulator t = term(10, 2);
        t.feed("\u4e2d\u6587a");
        assertEquals(5, t.getCursorCol());
        assertEquals("\u4e2d\u6587a", line(t, 0));
    }

    @Test public void alternateScreenRestoresMainScreen() {
        TerminalEmulator t = term(10, 3);
        t.feed("main");
        t.feed("\u001b[?1049h\u001b[H"); // like xterm, the cursor is not moved by the switch itself
        assertTrue(t.isAltScreen());
        assertEquals("", line(t, 0));
        t.feed("alt");
        assertEquals("alt", line(t, 0));
        t.feed("\u001b[?1049l");
        assertFalse(t.isAltScreen());
        assertEquals("main", line(t, 0));
        assertEquals(4, t.getCursorCol());
    }

    @Test public void deviceStatusReports() {
        TerminalEmulator t = term(20, 5);
        t.feed("\u001b[2;3H\u001b[6n");
        assertEquals("\u001b[2;3R", cap.sent());
        t.feed("\u001b[5n");
        assertTrue(cap.sent().endsWith("\u001b[0n"));
    }

    @Test public void oscSetsTitleWithBellOrStTerminator() {
        TerminalEmulator t = term(20, 3);
        t.feed("\u001b]0;first\u0007");
        assertEquals("first", cap.title);
        t.feed("\u001b]2;second\u001b\\");
        assertEquals("second", cap.title);
        assertEquals("", line(t, 0));
    }

    @Test public void scrollRegionKeepsOutsideLinesAndSkipsScrollback() {
        TerminalEmulator t = term(10, 5);
        t.feed("A\r\nB\r\nC\r\nD\r\nE");
        t.feed("\u001b[2;4r\u001b[4;1H\n");
        assertEquals(0, t.getScrollbackSize());
        assertEquals("A", line(t, 0));
        assertEquals("C", line(t, 1));
        assertEquals("D", line(t, 2));
        assertEquals("", line(t, 3));
        assertEquals("E", line(t, 4));
    }

    @Test public void insertAndDeleteLinesAndChars() {
        TerminalEmulator t = term(10, 4);
        t.feed("1\r\n2\r\n3\r\n4\u001b[2;1H\u001b[L");
        assertEquals("1", line(t, 0));
        assertEquals("", line(t, 1));
        assertEquals("2", line(t, 2));
        assertEquals("3", line(t, 3));
        t.feed("\u001b[M");
        assertEquals("2", line(t, 1));
        t.feed("\u001b[1;1Habcdef\u001b[1;3H\u001b[2P");
        assertEquals("abef", line(t, 0));
        t.feed("\u001b[1;2H\u001b[2@");
        assertEquals("a  bef", line(t, 0));
    }

    @Test public void decLineDrawing() {
        TerminalEmulator t = term(10, 2);
        t.feed("\u001b(0lqk\u001b(Bq");
        assertEquals("\u250c\u2500\u2510q", line(t, 0));
    }

    @Test public void tabStopsEveryEight() {
        TerminalEmulator t = term(30, 2);
        t.feed("a\tb");
        assertEquals(9, t.getCursorCol());
        assertEquals("a       b", line(t, 0));
    }

    @Test public void modeFlags() {
        TerminalEmulator t = term(10, 2);
        t.feed("\u001b[?1h\u001b[?2004h\u001b[?25l");
        assertTrue(t.isAppCursorKeys());
        assertTrue(t.isBracketedPaste());
        assertFalse(t.isCursorVisible());
        t.feed("\u001b[?1l\u001b[?2004l\u001b[?25h");
        assertFalse(t.isAppCursorKeys());
        assertFalse(t.isBracketedPaste());
        assertTrue(t.isCursorVisible());
    }

    @Test public void resizeShrinkMovesTopLinesToScrollbackAndGrowRestoresThem() {
        TerminalEmulator t = term(10, 5);
        t.feed("1\r\n2\r\n3\r\n4\r\n5");
        t.resize(10, 3);
        assertEquals(2, t.getScrollbackSize());
        assertEquals("3", line(t, 0));
        assertEquals("5", line(t, 2));
        assertEquals(2, t.getCursorRow());
        t.resize(10, 5);
        assertEquals(0, t.getScrollbackSize());
        assertEquals("1", line(t, 0));
        assertEquals("5", line(t, 4));
        assertEquals(4, t.getCursorRow());
    }

    @Test public void resizeColumnsTruncates() {
        TerminalEmulator t = term(10, 2);
        t.feed("abcdefghij");
        t.resize(5, 2);
        assertEquals("abcde", line(t, 0));
        assertEquals(5, t.getCols());
    }

    @Test public void ctrlCharactersAndBackspace() {
        TerminalEmulator t = term(10, 2);
        t.feed("abc\b\bX\u0007");
        assertEquals("aXc", line(t, 0));
        assertEquals(1, cap.bells);
    }

    @Test public void garbageDoesNotThrow() {
        TerminalEmulator t = term(10, 3);
        byte[] junk = new byte[4096];
        java.util.Random r = new java.util.Random(42);
        r.nextBytes(junk);
        t.feed(junk, 0, junk.length);
        t.feed("\u001b[999999;999999H\u001b[?9999h\u001b[;;;;;;m\u001b[0;0r");
        assertTrue(t.getCursorRow() < t.getRows());
        assertTrue(t.getCursorCol() < t.getCols());
    }
}
