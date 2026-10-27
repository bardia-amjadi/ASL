package dev.asl.app.vnc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class RfbClientTest {

    /** A tiny scripted RFB server: 4x2 desktop, one Raw rect then a CopyRect then a DesktopSize change. */
    private static final class FakeServer implements Runnable {
        final ServerSocket ss;
        final String password; // null = security type None
        final AtomicReference<String> authResult = new AtomicReference<>();
        final byte[] receivedKey = new byte[9];
        volatile boolean gotKey;
        volatile int pointerX = -1, pointerMask = -1;
        final CountDownLatch inputSeen = new CountDownLatch(2);
        volatile boolean sendResize = true;

        FakeServer(String password) throws IOException {
            this.password = password;
            this.ss = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
        }

        int port() { return ss.getLocalPort(); }

        /** Reads client messages in whatever order they arrive until a FramebufferUpdateRequest (type 3) is seen. */
        private void readUntilRequest(DataInputStream in) throws IOException {
            while (true) {
                int t = in.readUnsignedByte();
                if (t == 3) { in.skipBytes(9); return; }
                handleInput(t, in);
            }
        }

        private void handleInput(int t, DataInputStream in) throws IOException {
            if (t == 5) {
                pointerMask = in.readUnsignedByte(); pointerX = in.readUnsignedShort(); in.readUnsignedShort();
                inputSeen.countDown();
            } else if (t == 4) {
                in.readUnsignedByte(); in.skipBytes(2);
                int ks = in.readInt();
                receivedKey[0] = (byte) (ks & 0xFF); gotKey = true;
                inputSeen.countDown();
            } else {
                throw new IOException("unexpected client message " + t);
            }
        }

        @Override public void run() {
            try (Socket s = ss.accept()) {
                DataInputStream in = new DataInputStream(s.getInputStream());
                DataOutputStream out = new DataOutputStream(s.getOutputStream());
                out.write("RFB 003.008\n".getBytes(StandardCharsets.US_ASCII));
                byte[] cv = new byte[12];
                in.readFully(cv);
                if (password == null) {
                    out.writeByte(1); out.writeByte(1);
                    in.readUnsignedByte();
                    out.writeInt(0);
                } else {
                    out.writeByte(1); out.writeByte(2);
                    in.readUnsignedByte();
                    byte[] challenge = new byte[16];
                    for (int i = 0; i < 16; i++) challenge[i] = (byte) (i * 7 + 3);
                    out.write(challenge);
                    byte[] resp = new byte[16];
                    in.readFully(resp);
                    byte[] expect = RfbClient.vncAuthResponse(challenge, password);
                    boolean ok = java.util.Arrays.equals(resp, expect);
                    authResult.set(ok ? "ok" : "bad");
                    if (!ok) {
                        out.writeInt(1);
                        byte[] reason = "Authentication failure".getBytes(StandardCharsets.US_ASCII);
                        out.writeInt(reason.length);
                        out.write(reason);
                        return;
                    }
                    out.writeInt(0);
                }
                in.readUnsignedByte(); // ClientInit
                out.writeShort(4); out.writeShort(2);
                out.write(new byte[16]);
                byte[] name = "fake".getBytes(StandardCharsets.US_ASCII);
                out.writeInt(name.length); out.write(name);
                out.flush();

                // SetPixelFormat (20) + SetEncodings (12) + first update request (10)
                in.skipBytes(20 + 16);
                readUntilRequest(in);

                // Update 1: Raw rect covering the full 4x2 frame; pixel (x,y) = B=x*10, G=y*10, R=200 (bytes B,G,R,X)
                out.writeByte(0); out.writeByte(0); out.writeShort(1);
                out.writeShort(0); out.writeShort(0); out.writeShort(4); out.writeShort(2); out.writeInt(0);
                for (int y = 0; y < 2; y++) for (int x = 0; x < 4; x++) { out.writeByte(x * 10); out.writeByte(y * 10); out.writeByte(200); out.writeByte(0); }
                out.flush();
                readUntilRequest(in); // incremental request

                // Update 2: CopyRect from (0,0) 2x1 to (2,1)
                out.writeByte(0); out.writeByte(0); out.writeShort(1);
                out.writeShort(2); out.writeShort(1); out.writeShort(2); out.writeShort(1); out.writeInt(1);
                out.writeShort(0); out.writeShort(0);
                out.flush();
                readUntilRequest(in);

                // Update 3: desktop resize to 8x3
                if (sendResize) {
                    out.writeByte(0); out.writeByte(0); out.writeShort(1);
                    out.writeShort(0); out.writeShort(0); out.writeShort(8); out.writeShort(3); out.writeInt(-223);
                    out.flush();
                    readUntilRequest(in);
                }

                // keep reading client messages (pointer, key, extra update requests) until the client closes
                try {
                    while (true) {
                        int t = in.readUnsignedByte();
                        if (t == 3) in.skipBytes(9); else handleInput(t, in);
                    }
                } catch (java.io.EOFException | java.net.SocketException done) {
                    // client closed
                }
            } catch (Exception e) {
                authResult.compareAndSet(null, "server error: " + e);
            }
        }
    }

    private static final class Events implements RfbClient.Listener {
        final CountDownLatch frames = new CountDownLatch(3);
        final CountDownLatch resized = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        volatile int newW, newH;
        @Override public void onFrame() { frames.countDown(); }
        @Override public void onResize(int w, int h) { newW = w; newH = h; resized.countDown(); }
        @Override public void onBell() { }
        @Override public void onClosed(String reason) { closed.countDown(); }
    }

    @Test public void passwordAuthRawCopyRectResizeAndInput() throws Exception {
        FakeServer server = new FakeServer("secret");
        Thread t = new Thread(server);
        t.start();
        Events ev = new Events();
        RfbClient c = RfbClient.connect("127.0.0.1", server.port(), "secret", 3000, ev);
        assertTrue(ev.frames.await(3, TimeUnit.SECONDS));
        assertTrue(ev.resized.await(3, TimeUnit.SECONDS));
        assertEquals("ok", server.authResult.get());
        assertEquals(8, ev.newW);
        assertEquals(3, ev.newH);
        c.pointer(123, 45, 1);
        c.key(RfbClient.keysymForCodePoint('a'), true);
        assertTrue(server.inputSeen.await(3, TimeUnit.SECONDS));
        assertEquals(123, server.pointerX);
        assertEquals(1, server.pointerMask);
        assertEquals((byte) 'a', server.receivedKey[0]);
        c.close();
        t.join(2000);
    }

    @Test public void framebufferContentAfterRawAndCopyRect() throws Exception {
        FakeServer server = new FakeServer(null);
        server.sendResize = false;
        Thread t = new Thread(server);
        t.start();
        final CountDownLatch two = new CountDownLatch(2);
        RfbClient.Listener l = new RfbClient.Listener() {
            @Override public void onFrame() { two.countDown(); }
            @Override public void onResize(int w, int h) { }
            @Override public void onBell() { }
            @Override public void onClosed(String r) { }
        };
        RfbClient c = RfbClient.connect("127.0.0.1", server.port(), null, 3000, l);
        assertTrue(two.await(3, TimeUnit.SECONDS));
        int[] px;
        synchronized (c.lock) { px = c.pixels().clone(); }
        // pixel (3,0): B=30,G=0,R=200
        assertEquals(0xFF000000 | (200 << 16) | (0 << 8) | 30, px[3]);
        // CopyRect copied (0,0),(1,0) to (2,1),(3,1): pixel (2,1) must equal old (0,0), (3,1) old (1,0)
        assertEquals(0xFF000000 | (200 << 16), px[1 * 4 + 2]);
        assertEquals(0xFF000000 | (200 << 16) | 10, px[1 * 4 + 3]);
        c.close();
    }

    @Test public void wrongPasswordIsRejected() throws Exception {
        FakeServer server = new FakeServer("right");
        Thread t = new Thread(server);
        t.start();
        boolean threw = false;
        try {
            RfbClient.connect("127.0.0.1", server.port(), "wrong", 3000, new Events());
        } catch (IOException e) {
            threw = e.getMessage().contains("authentication failed");
        }
        assertTrue(threw);
    }

    @Test public void keysymMapping() {
        assertEquals('a', RfbClient.keysymForCodePoint('a'));
        assertEquals(0xFF0D, RfbClient.keysymForCodePoint('\n'));
        assertEquals(0xFF08, RfbClient.keysymForCodePoint(0x7F));
        assertEquals(0xE9, RfbClient.keysymForCodePoint(0xE9));
        assertEquals(0x01000000 + 0x20AC, RfbClient.keysymForCodePoint(0x20AC));
    }

    @Test public void reverseBitsMatchesKnownValues() {
        assertEquals((byte) 0x80, RfbClient.reverseBits((byte) 0x01));
        assertEquals((byte) 0xE8, RfbClient.reverseBits((byte) 0x17));
        assertEquals((byte) 0x00, RfbClient.reverseBits((byte) 0x00));
    }
}
