package com.r1.controller;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal ADB-over-TCP client for the Phicomm R1 (Android 5.1.1, adbd on 5555,
 * no authentication required).
 *
 * IMPORTANT DESIGN NOTE: the phone->R1 WiFi link is slow (~3.4s per TCP
 * handshake). Opening a fresh TCP connection for every command left the R1's
 * adbd full of CLOSE_WAIT sockets until it stopped answering. Instead this
 * class keeps ONE connection, does the CNXN handshake once, and runs each
 * shell command as its own ADB channel (OPEN/WRTE/CLSE) on that connection.
 * Callers must share one AdbSession and close() it when done.
 */
public final class AdbClient {

    private static final int CNXN = 0x4e584e43; // "CNXN"
    private static final int AUTH = 0x48545541; // "AUTH"
    private static final int OKAY = 0x59414b4f; // "OKAY"
    private static final int WRTE = 0x45545257; // "WRTE"
    private static final int CLSE = 0x45534c43; // "CLSE"
    private static final int OPEN = 0x4e45504f; // "OPEN"

    private static final int ADB_VERSION = 0x01000000;
    private static final int MAX_DATA = 1024 * 1024;
    private static final int HEADER_SIZE = 24;

    private AdbClient() {}

    /** A single reusable ADB connection. Not thread-safe - serialize calls. */
    public static final class Session {
        private final Socket sock;
        private final DataOutputStream out;
        private final DataInputStream in;
        private int nextLocalId = 1;
        private final int timeoutMs;
        private volatile boolean dead = false;

        private Session(Socket sock, int timeoutMs) throws IOException {
            this.sock = sock;
            this.timeoutMs = timeoutMs;
            this.out = new DataOutputStream(sock.getOutputStream());
            this.in = new DataInputStream(sock.getInputStream());
            // CNXN handshake
            writeMsg(out, new Msg(CNXN, ADB_VERSION, MAX_DATA,
                                  "host::\0".getBytes(StandardCharsets.UTF_8)));
            Msg reply = readMsg();
            if (reply == null) throw new IOException("no handshake reply");
            if (reply.command == AUTH) throw new IOException("device requires ADB auth");
            if (reply.command != CNXN) throw new IOException("bad handshake: " + reply.command);
        }

        public boolean isDead() { return dead; }

        /** Run one shell command on a fresh channel of this connection. */
        public synchronized String shell(String command) throws IOException {
            if (dead) throw new IOException("session closed");
            int localId = nextLocalId++;
            try {
                byte[] service = ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8);
                writeMsg(out, new Msg(OPEN, localId, 0, service));

                int remoteId = -1;
                for (int i = 0; i < 5; i++) {
                    Msg m = readMsg();
                    if (m == null) throw new IOException("no OPEN reply");
                    if (m.command == OKAY && m.arg1 == localId) { remoteId = m.arg0; break; }
                    if (m.command == CLSE && m.arg1 == localId) throw new IOException("shell rejected");
                }
                if (remoteId < 0) throw new IOException("OPEN not acknowledged");

                StringBuilder sb = new StringBuilder();
                int guard = 0;
                while (guard++ < 20000) {
                    Msg m = readMsg();
                    if (m == null) break;
                    if (m.command == WRTE && m.arg1 == localId) {
                        if (m.data != null && m.data.length > 0) {
                            sb.append(new String(m.data, StandardCharsets.UTF_8));
                        }
                        writeMsg(out, new Msg(OKAY, localId, remoteId, null));
                    } else if (m.command == CLSE && m.arg1 == localId) {
                        writeMsg(out, new Msg(CLSE, localId, remoteId, null));
                        break;
                    }
                    // ignore traffic belonging to other channels
                }
                return sb.toString();
            } catch (IOException e) {
                dead = true;
                throw e;
            }
        }

        private Msg readMsg() throws IOException {
            byte[] header = new byte[HEADER_SIZE];
            in.readFully(header);
            ByteBuffer h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            int command = h.getInt(), a0 = h.getInt(), a1 = h.getInt();
            int len = h.getInt();
            h.getInt(); h.getInt(); // checksum, magic
            if (len < 0 || len > MAX_DATA) throw new IOException("bad length " + len);
            byte[] data = new byte[len];
            if (len > 0) in.readFully(data);
            return new Msg(command, a0, a1, data);
        }

        public void close() {
            dead = true;
            try { if (!sock.isClosed()) sock.close(); } catch (IOException ignored) {}
        }
    }

    /** Open a reusable session (one TCP handshake). */
    public static Session connect(String host, int port, int timeoutMs) throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), timeoutMs);
        s.setSoTimeout(timeoutMs);
        try {
            return new Session(s, timeoutMs);
        } catch (IOException e) {
            try { s.close(); } catch (Exception ignored) {}
            throw e;
        }
    }

    /** Read all speaker infos in ONE shell command. */
    public static String[] readInfo(Session session) throws IOException {
        String fw = "–", wifi = "–", uptime = "–", bt = "–";
        String cmd = "echo FW=$(getprop ro.build.version.incremental); "
                   + "echo UP=$(cat /proc/uptime); "
                   + "echo WL=$(dumpsys wifi | grep mCurrentSignalLevel); "
                   + "echo BT=$(dumpsys bluetooth_manager | grep curState)";
        String s = session.shell(cmd);
        for (String line : s.split("\\r?\\n")) {
            line = line.trim();
            if (line.startsWith("FW=") && line.length() > 3) fw = line.substring(3).trim();
            else if (line.startsWith("UP=")) {
                Matcher m = Pattern.compile("([0-9.]+)").matcher(line.substring(3));
                if (m.find()) {
                    long sec = (long) Double.parseDouble(m.group(1));
                    long h = sec / 3600;
                    uptime = h >= 24 ? (h / 24) + " 天 " + (h % 24) + " 小时" : h + " 小时";
                }
            } else if (line.startsWith("WL=")) {
                Matcher m = Pattern.compile("(\\d+)").matcher(line);
                if (m.find()) {
                    int level = Integer.parseInt(m.group(1));
                    wifi = level >= 4 ? "满格" : level >= 3 ? "良好" : level >= 2 ? "一般" : "较弱";
                }
            } else if (line.startsWith("BT=")) {
                bt = line.contains("curState=Connected") ? "已连接" : "开启/未连接";
            }
        }
        return new String[]{fw, wifi, uptime, bt};
    }

    /** Parse LIVE music-stream speaker index from `dumpsys audio`. */
    public static int parseVolume(String dump) {
        if (dump == null) return -1;
        String[] lines = dump.split("\\r?\\n");
        String target = null;
        for (String line : lines) {
            if (line.contains("Current:") && line.contains("speaker")) target = line;
        }
        if (target == null) {
            for (String line : lines) if (line.contains("Current:")) target = line;
        }
        if (target == null) return -1;
        Matcher m = Pattern.compile("\\(speaker\\):\\s*(\\d+)").matcher(target);
        if (!m.find()) {
            Matcher d = Pattern.compile("\\(default\\):\\s*(\\d+)").matcher(target);
            if (d.find()) return clamp(Integer.parseInt(d.group(1)));
            return -1;
        }
        return clamp(Integer.parseInt(m.group(1)));
    }

    /**
     * Build the triple-tap command that switches the R1 into Bluetooth speaker
     * mode. Verified on firmware 3448: the top key emits KEYCODE_PHICOMM_OK
     * (scanCode 195) on /dev/input/event0; three quick taps are recognized by
     * UserDefInputKeyService as TRIPLE_CLICK -> TripleClickProcessor.openBluetooth
     * -> SceneStateMachine enters BluetoothState. All taps are packed into a
     * single shell call so it is one ADB round-trip (~3.4s on this slow link).
     */
    /** One tap of the R1 top key (KEYCODE_PHICOMM_OK, scanCode 195, event0). */
    public static String bluetoothTapCommand() {
        return "sendevent /dev/input/event0 1 195 1; sendevent /dev/input/event0 0 0 0; "
             + "sendevent /dev/input/event0 1 195 0; sendevent /dev/input/event0 0 0 0";
    }

    /** Build a multi-keyevent command (one process, N keycodes). */
    public static String volumeCommand(int current, int target) {
        int steps = target - current;
        if (steps == 0) return null;
        int key = steps > 0 ? 24 : 25;
        StringBuilder cmd = new StringBuilder("input keyevent");
        for (int i = 0; i < Math.abs(steps); i++) cmd.append(' ').append(key);
        return cmd.toString();
    }

    private static int clamp(int v) { return Math.max(0, Math.min(15, v)); }

    private static void writeMsg(DataOutputStream out, Msg m) throws IOException {
        byte[] data = (m.data == null) ? new byte[0] : m.data;
        ByteBuffer h = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(m.command).putInt(m.arg0).putInt(m.arg1).putInt(data.length);
        int checksum = 0;
        for (byte b : data) checksum += (b & 0xff);
        h.putInt(checksum).putInt(m.command ^ 0xffffffff);
        out.write(h.array());
        out.write(data);
        out.flush();
    }

    private static final class Msg {
        final int command, arg0, arg1;
        final byte[] data;
        Msg(int command, int arg0, int arg1, byte[] data) {
            this.command = command; this.arg0 = arg0; this.arg1 = arg1; this.data = data;
        }
    }
}
