package com.BB465_stuff.Terminal;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * An in-process adb client, enough to pair and to run shell commands over a
 * TCP adbd connection. This is the adb wire protocol implemented directly, not
 * a bundled adb binary: Android has blocked exec() of app-writable files since
 * Android 10, so shipping the real binary and running it is not an option.
 *
 * Wire format, from AOSP adb/protocol.txt:
 *
 *   CONNECT(version, maxdata, "system-identity-string")
 *   AUTH(type, 0, "data")
 *   OPEN(local-id, 0, "service")
 *   OKAY / WRTE / CLSE
 *
 * Each packet is a 24-byte little-endian header followed by data:
 *   command, arg0, arg1, data_length, data_crc32, magic (command ^ 0xffffffff)
 */
public final class AdbLink {

    // message command constants
    static final int A_CNXN = 0x4e584e43;
    static final int A_AUTH = 0x48545541;
    static final int A_OPEN = 0x4e45504f;
    static final int A_OKAY  = 0x59414b4f;
    static final int A_CLSE  = 0x45534c43;
    static final int A_WRTE  = 0x45545257;

    // AUTH arg0 values
    static final int AUTH_TOKEN         = 1;   // device -> host, 20 random bytes
    static final int AUTH_SIGNATURE     = 2;   // host -> device, raw RSA signature
    static final int AUTH_RSAPUBLICKEY  = 3;   // host -> device, base64 pubkey + NUL

    static final int A_VERSION = 0x01000001;  // 0x01000000 | skip-checks
    static final int MAXDATA   = 256 * 1024;
    static final int TIMEOUT_MS = 15000;
    static final int TOKEN_SIZE = 20;

    private final AdbKey key;
    private Socket sock;
    private DataInputStream in;
    private DataOutputStream out;
    private int localId = 1;
    private final List<String> log = new ArrayList<String>();

    public AdbLink(AdbKey key) { this.key = key; }

    public String lastLog() {
        StringBuilder sb = new StringBuilder();
        for (String s : log) sb.append(s).append('\n');
        return sb.toString();
    }
    private void say(String s) { log.add(s); }

    // ---------------------------------------------------------------- framing

    private static final class Msg {
        int cmd, arg0, arg1;
        byte[] data;
    }

    private void send(int cmd, int arg0, int arg1, byte[] data) throws IOException {
        CRC32 crc = new CRC32();
        crc.update(data, 0, data.length);
        byte[] h = new byte[24];
        le32(h, 0, cmd);
        le32(h, 4, arg0);
        le32(h, 8, arg1);
        le32(h, 12, data.length);
        le32(h, 16, (int) crc.getValue());
        le32(h, 20, cmd ^ 0xffffffff);
        out.write(h);
        out.write(data);
        out.flush();
    }

    private Msg read() throws IOException {
        byte[] h = new byte[24];
        in.readFully(h);
        Msg m = new Msg();
        m.cmd  = (int) le32(h, 0);
        m.arg0 = (int) le32(h, 4);
        m.arg1 = (int) le32(h, 8);
        int len = (int) le32(h, 12);
        long magic = le32(h, 20) & 0xffffffffL;
        if (magic != ((m.cmd ^ 0xffffffff) & 0xffffffffL)) {
            throw new IOException("bad magic in packet");
        }
        if (len > 0) {
            m.data = new byte[len];
            in.readFully(m.data);
        } else {
            m.data = new byte[0];
        }
        return m;
    }

    private static long le32(byte[] b, int o) {
        return (b[o] & 0xffL) | (b[o+1] & 0xffL) << 8
             | (b[o+2] & 0xffL) << 16 | (b[o+3] & 0xffL) << 24;
    }
    private static void le32(byte[] b, int o, int v) {
        b[o] = (byte) v; b[o+1] = (byte) (v >>> 8);
        b[o+2] = (byte) (v >>> 16); b[o+3] = (byte) (v >>> 24);
    }

    // ------------------------------------------------------------- handshake

    /**
     * CNXN exchange plus RSA authentication. Returns true if the device
     * accepted our key, in which case the connection is usable.
     *
     * Order matters: adbd cannot check a signature until it knows which public
     * key to check it against, so the host must offer its key before signing
     * anything. Signing first is what made every attempt fail.
     */
    private boolean handshake(String banner, String pairingCode) throws IOException {
        send(A_CNXN, A_VERSION, MAXDATA, cstr(banner));

        boolean sentKey = false;
        int signatures = 0;
        int rounds = 0;
        while (true) {
            if (++rounds > 12) throw new IOException("auth did not converge");
            Msg m = read();
            if (m.cmd == A_CNXN) {
                say("device CNXN, banner=" + preview(m.data));
                if (sentKey) {
                    // adbd only sends a second CNXN once the key is accepted
                    say("AUTH accepted");
                    return true;
                }
                continue;
            }
            if (m.cmd == A_AUTH) {
                int type = m.arg0;
                // A zero-length SIGNATURE is adbd saying "denied".
                if (type == AUTH_SIGNATURE && m.data.length == 0) {
                    say("device denied our key");
                    return false;
                }
                if (!sentKey) {
                    send(A_AUTH, AUTH_RSAPUBLICKEY, 0, cstr(key.encodeBase64()));
                    say("offered public key to device");
                    sentKey = true;
                    continue;
                }
                if (type == AUTH_TOKEN || type == AUTH_SIGNATURE) {
                    if (m.data.length != TOKEN_SIZE) {
                        say("odd challenge length " + m.data.length + ", skipping");
                        continue;
                    }
                    if (++signatures > 4) {
                        throw new IOException("device rejected our key 4 times");
                    }
                    // SIGNATURE payload is the raw signature: adbd feeds it
                    // straight into RSA_verify(NID_sha1, token, ...).
                    byte[] sig;
                    try {
                        sig = key.signToken(m.data);
                    } catch (Exception e) {
                        throw new IOException("signing failed: " + e);
                    }
                    send(A_AUTH, AUTH_SIGNATURE, 0, sig);
                    say("signed challenge, sent raw signature");
                    continue;
                }
                say("AUTH arg0=" + type + " -> rejected/failed");
                return false;
            }
            say("unexpected packet cmd=0x" + Integer.toHexString(m.cmd));
            return false;
        }
    }

    // ---------------------------------------------------------------- public

    /**
     * Pair with the device's pairing server so our key lands in its
     * authorised list. ip/port come from Developer options -> Wireless
     * debugging -> Pair device with pairing code.
     */
    public boolean pair(String ip, int port, String code) throws IOException {
        open(ip, port);
        say("connected to pairing port " + ip + ":" + port);
        // The six digit code belongs to the TLS/SPAKE2 pairing transport, which
        // an in-process client cannot speak. Over plain TCP the RSA exchange is
        // the entire protocol, so code is unused here.
        return handshake("host::features=shell_v2,cmd,pairing", code);
    }

    /** Open a connection to a device that has already accepted our key. */
    public boolean connect(String ip, int port) throws IOException {
        open(ip, port);
        say("connected to " + ip + ":" + port);
        return handshake("host::features=shell_v2,cmd,stat_v2,abb_exec", null);
    }

    private void open(String ip, int port) throws IOException {
        close();
        sock = new Socket();
        sock.connect(new InetSocketAddress(ip, port), TIMEOUT_MS);
        sock.setSoTimeout(TIMEOUT_MS);
        in = new DataInputStream(sock.getInputStream());
        out = new DataOutputStream(sock.getOutputStream());
    }

    /** Run one command over shell: and return everything it wrote. */
    public String exec(String cmd, int timeoutMs) throws IOException {
        int id = localId++;
        send(A_OPEN, id, 0, cstr("shell:"));
        // wait for OKAY
        while (true) {
            Msg m = read();
            if (m.cmd == A_OKAY) break;
            if (m.cmd == A_CLSE) throw new IOException("device refused shell:");
            if (m.cmd == A_AUTH) throw new IOException("re-auth required mid-stream");
        }
        send(A_WRTE, id, 0, cstr(cmd));
        StringBuilder sb = new StringBuilder();
        long deadline = System.currentTimeMillis() + timeoutMs;
        sock.setSoTimeout(Math.max(1, timeoutMs));
        while (System.currentTimeMillis() < deadline) {
            Msg m = read();
            if (m.cmd == A_WRTE) {
                sb.append(new String(m.data, "UTF-8"));
            } else if (m.cmd == A_CLSE) {
                break;
            } else if (m.cmd == A_OKAY) {
                continue;
            }
        }
        send(A_CLSE, id, 0, new byte[0]);
        return sb.toString();
    }

    public void close() {
        try { if (sock != null) sock.close(); } catch (IOException ignored) { }
        sock = null; in = null; out = null;
    }

    private static byte[] cstr(String s) throws IOException {
        byte[] b = s.getBytes("UTF-8");
        byte[] r = new byte[b.length + 1];
        System.arraycopy(b, 0, r, 0, b.length);
        return r;   // NUL terminated
    }

    private static String preview(byte[] b) {
        int n = 0;
        while (n < b.length && b[n] != 0) n++;
        return new String(b, 0, n);
    }
}
