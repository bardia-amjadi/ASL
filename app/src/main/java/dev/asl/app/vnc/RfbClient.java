package dev.asl.app.vnc;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * Minimal VNC (RFB 3.8) client: "None" and "VNC password" authentication, 32-bit true-colour framebuffer,
 * Raw + CopyRect + DesktopSize encodings, pointer and key input. Pure Java (no Android APIs) so it is
 * unit-tested on the JVM against a fake server.
 *
 * Only these encodings are advertised, so a conforming server never sends anything else. That costs bandwidth
 * compared with ZRLE/Tight, which is fine on the loopback connection ASL uses.
 *
 * Renderers must hold {@link #lock} while reading {@link #pixels()}.
 */
public final class RfbClient implements Closeable {

    public interface Listener {
        /** A framebuffer update was applied (called from the reader thread). */
        void onFrame();

        void onResize(int width, int height);

        void onBell();

        /** The connection ended (reason is human readable). Called at most once. */
        void onClosed(String reason);
    }

    public final Object lock = new Object();

    private final Listener listener;
    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final Object outLock = new Object();
    /** Input is sent from this thread: Android forbids network writes on the UI thread. */
    private final java.util.concurrent.ExecutorService sender = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "rfb-sender");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean closed;
    private int width;
    private int height;
    private int[] pixels = new int[0];
    private String desktopName = "";

    private RfbClient(Socket socket, Listener listener) throws IOException {
        this.socket = socket;
        this.listener = listener;
        this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 64 * 1024));
        this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 8 * 1024));
    }

    /** Connects, authenticates, negotiates the pixel format and starts the reader thread. */
    public static RfbClient connect(String host, int port, String password, int timeoutMs, Listener listener) throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), timeoutMs);
        s.setTcpNoDelay(true);
        s.setSoTimeout(timeoutMs); // handshake timeout; cleared afterwards
        RfbClient c = new RfbClient(s, listener);
        try {
            c.handshake(password);
            s.setSoTimeout(0);
        } catch (IOException | RuntimeException e) {
            c.closeQuietly();
            throw e;
        }
        Thread t = new Thread(c::readLoop, "rfb-reader");
        t.setDaemon(true);
        t.start();
        return c;
    }

    // ---- public API ---------------------------------------------------------------------------

    public int width() { synchronized (lock) { return width; } }
    public int height() { synchronized (lock) { return height; } }
    public String desktopName() { return desktopName; }

    /** ARGB framebuffer, row-major. Hold {@link #lock} while reading. */
    public int[] pixels() { return pixels; }

    public void pointer(final int x, final int y, final int buttonMask) {
        submit(() -> {
            synchronized (outLock) {
                try {
                    out.writeByte(5);
                    out.writeByte(buttonMask);
                    out.writeShort(clamp(x, 0, 65535));
                    out.writeShort(clamp(y, 0, 65535));
                    out.flush();
                } catch (IOException e) {
                    fail(e);
                }
            }
        });
    }

    public void key(final int keysym, final boolean down) {
        submit(() -> {
            synchronized (outLock) {
                try {
                    out.writeByte(4);
                    out.writeByte(down ? 1 : 0);
                    out.writeShort(0);
                    out.writeInt(keysym);
                    out.flush();
                } catch (IOException e) {
                    fail(e);
                }
            }
        });
    }

    private void submit(Runnable r) {
        try {
            sender.execute(r);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // connection already closed
        }
    }

    @Override
    public void close() {
        closeQuietly();
        notifyClosed("Disconnected");
    }

    // ---- protocol -----------------------------------------------------------------------------

    private void handshake(String password) throws IOException {
        byte[] ver = new byte[12];
        in.readFully(ver);
        String v = new String(ver, StandardCharsets.US_ASCII);
        if (!v.startsWith("RFB 003.")) throw new IOException("Not a VNC server (got \"" + v.trim() + "\")");
        int minor;
        try {
            minor = Integer.parseInt(v.substring(8, 11));
        } catch (NumberFormatException e) {
            throw new IOException("Unreadable protocol version");
        }
        if (minor < 7) throw new IOException("VNC protocol 3." + minor + " is too old");
        out.write("RFB 003.008\n".getBytes(StandardCharsets.US_ASCII));
        out.flush();

        int n = in.readUnsignedByte();
        if (n == 0) throw new IOException("Server refused: " + readString());
        boolean none = false;
        boolean vnc = false;
        for (int i = 0; i < n; i++) {
            int t = in.readUnsignedByte();
            if (t == 1) none = true;
            if (t == 2) vnc = true;
        }
        if (vnc && password != null) {
            out.writeByte(2);
            out.flush();
            byte[] challenge = new byte[16];
            in.readFully(challenge);
            try {
                out.write(vncAuthResponse(challenge, password));
            } catch (GeneralSecurityException e) {
                throw new IOException("Cannot compute VNC authentication: " + e.getMessage());
            }
            out.flush();
        } else if (none) {
            out.writeByte(1);
            out.flush();
        } else if (vnc) {
            throw new IOException("The server needs a password");
        } else {
            throw new IOException("No supported VNC security type");
        }
        int result = in.readInt();
        if (result != 0) {
            String reason = "";
            try {
                reason = readString();
            } catch (IOException ignored) {
                // some servers send no reason
            }
            throw new IOException("VNC authentication failed" + (reason.isEmpty() ? "" : ": " + reason));
        }

        out.writeByte(1); // ClientInit: shared
        out.flush();

        int w = in.readUnsignedShort();
        int h = in.readUnsignedShort();
        in.skipBytes(16); // server pixel format (we override it)
        int nameLen = in.readInt();
        byte[] name = new byte[Math.min(Math.max(nameLen, 0), 4096)];
        in.readFully(name);
        desktopName = new String(name, StandardCharsets.UTF_8);
        allocate(w, h);

        // SetPixelFormat: 32 bpp, depth 24, little-endian true colour, R<<16 G<<8 B
        synchronized (outLock) {
            out.writeByte(0);
            out.write(new byte[3]);
            out.writeByte(32);
            out.writeByte(24);
            out.writeByte(0);
            out.writeByte(1);
            out.writeShort(255);
            out.writeShort(255);
            out.writeShort(255);
            out.writeByte(16);
            out.writeByte(8);
            out.writeByte(0);
            out.write(new byte[3]);
            // SetEncodings: CopyRect, Raw, DesktopSize
            out.writeByte(2);
            out.writeByte(0);
            out.writeShort(3);
            out.writeInt(1);
            out.writeInt(0);
            out.writeInt(-223);
            out.flush();
        }
        requestUpdate(false);
    }

    private String readString() throws IOException {
        int len = in.readInt();
        byte[] b = new byte[Math.min(Math.max(len, 0), 4096)];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    private void allocate(int w, int h) throws IOException {
        if (w <= 0 || h <= 0 || (long) w * h > 16_000_000L) throw new IOException("Unreasonable framebuffer size " + w + "x" + h);
        synchronized (lock) {
            width = w;
            height = h;
            pixels = new int[w * h];
            java.util.Arrays.fill(pixels, 0xFF000000);
        }
    }

    private void requestUpdate(boolean incremental) {
        synchronized (outLock) {
            try {
                out.writeByte(3);
                out.writeByte(incremental ? 1 : 0);
                out.writeShort(0);
                out.writeShort(0);
                out.writeShort(width);
                out.writeShort(height);
                out.flush();
            } catch (IOException e) {
                fail(e);
            }
        }
    }

    private void readLoop() {
        try {
            byte[] row = new byte[0];
            while (!closed) {
                int type = in.readUnsignedByte();
                switch (type) {
                    case 0: { // FramebufferUpdate
                        in.readUnsignedByte();
                        int rects = in.readUnsignedShort();
                        for (int i = 0; i < rects; i++) {
                            int x = in.readUnsignedShort();
                            int y = in.readUnsignedShort();
                            int w = in.readUnsignedShort();
                            int h = in.readUnsignedShort();
                            int enc = in.readInt();
                            if (enc == 0) {
                                if (row.length < w * 4) row = new byte[w * 4];
                                readRaw(x, y, w, h, row);
                            } else if (enc == 1) {
                                int sx = in.readUnsignedShort();
                                int sy = in.readUnsignedShort();
                                copyRect(sx, sy, x, y, w, h);
                            } else if (enc == -223) {
                                allocate(w, h);
                                listener.onResize(w, h);
                            } else {
                                throw new IOException("Server sent unsupported encoding " + enc);
                            }
                        }
                        listener.onFrame();
                        requestUpdate(true);
                        break;
                    }
                    case 1: { // SetColourMapEntries (not used with true colour): skip
                        in.readUnsignedByte();
                        in.readUnsignedShort();
                        int n = in.readUnsignedShort();
                        in.skipBytes(n * 6);
                        break;
                    }
                    case 2:
                        listener.onBell();
                        break;
                    case 3: { // ServerCutText: ignored
                        in.skipBytes(3);
                        int len = in.readInt();
                        in.skipBytes(Math.max(0, len));
                        break;
                    }
                    default:
                        throw new IOException("Unknown server message " + type);
                }
            }
        } catch (IOException e) {
            if (!closed) fail(e);
        }
    }

    private void readRaw(int x, int y, int w, int h, byte[] row) throws IOException {
        for (int j = 0; j < h; j++) {
            in.readFully(row, 0, w * 4);
            synchronized (lock) {
                int py = y + j;
                if (py >= height) continue;
                int base = py * width;
                for (int i = 0; i < w; i++) {
                    int px = x + i;
                    if (px >= width) break;
                    int o = i * 4;
                    pixels[base + px] = 0xFF000000 | ((row[o + 2] & 0xFF) << 16) | ((row[o + 1] & 0xFF) << 8) | (row[o] & 0xFF);
                }
            }
        }
    }

    private void copyRect(int sx, int sy, int dx, int dy, int w, int h) {
        synchronized (lock) {
            if (sx + w > width || sy + h > height || dx + w > width || dy + h > height) return;
            int[] tmp = new int[w * h];
            for (int j = 0; j < h; j++) System.arraycopy(pixels, (sy + j) * width + sx, tmp, j * w, w);
            for (int j = 0; j < h; j++) System.arraycopy(tmp, j * w, pixels, (dy + j) * width + dx, w);
        }
    }

    // ---- helpers --------------------------------------------------------------------------------

    /** VNC authentication: DES-ECB of the 16-byte challenge, key = password (8 bytes) with each byte's bits reversed. */
    public static byte[] vncAuthResponse(byte[] challenge, String password) throws GeneralSecurityException {
        byte[] pw = password.getBytes(StandardCharsets.ISO_8859_1);
        byte[] key = new byte[8];
        for (int i = 0; i < 8 && i < pw.length; i++) key[i] = reverseBits(pw[i]);
        Cipher c = Cipher.getInstance("DES/ECB/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "DES"));
        return c.doFinal(challenge);
    }

    static byte reverseBits(byte b) {
        int v = b & 0xFF;
        int r = 0;
        for (int i = 0; i < 8; i++) {
            r = (r << 1) | (v & 1);
            v >>= 1;
        }
        return (byte) r;
    }

    /** X11 keysym for a Unicode code point. */
    public static int keysymForCodePoint(int cp) {
        if (cp == '\n') return 0xFF0D;
        if (cp == '\t') return 0xFF09;
        if (cp == 0x7F || cp == '\b') return 0xFF08;
        if ((cp >= 0x20 && cp <= 0x7E) || (cp >= 0xA0 && cp <= 0xFF)) return cp;
        return 0x01000000 + cp;
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private void fail(IOException e) {
        closeQuietly();
        notifyClosed(e.getMessage() == null ? e.toString() : e.getMessage());
    }

    private void closeQuietly() {
        closed = true;
        sender.shutdownNow();
        try {
            socket.close();
        } catch (IOException ignored) {
            // already closed
        }
    }

    private boolean notified;

    private synchronized void notifyClosed(String reason) {
        if (notified) return;
        notified = true;
        listener.onClosed(reason);
    }
}
