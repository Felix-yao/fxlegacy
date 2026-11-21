package com.r1.controller;

import android.util.Log;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * UDP client for the zTC1 flashed Phicomm TC1 power strip.
 *
 * Protocol (reverse engineered from a2633063/SmartControl_Android_MQTT):
 *   - Commands are broadcast over UDP to 255.255.255.255:10182 as a JSON string.
 *   - Device replies are sent back to UDP port 10181.
 *   - Switch a socket : {"mac":"<mac>","plug_N":{"on":1}}   (1=on, 0=off)
 *   - Rename a socket : {"mac":"<mac>","plug_N":{"setting":{"name":"xxx"}}}
 *   - Query status    : {"mac":"<mac>","version":null,
 *                        "plug_0":{"on":null,"setting":{"name":null}}, ... }
 *
 * One listener socket is kept open and a background thread parses replies.
 */
public final class Tc1Client {

    public static final int PHONE_UDP_PORT = 10181;
    public static final int DEVICE_UDP_PORT = 10182;

    public interface Listener {
        void onState(Tc1State state);
        void onError(String msg);
    }

    /** Parsed device state. */
    public static final class Tc1State {
        public String mac;
        public String name;
        public String version;
        public String power;          // e.g. "39.6" (watts) or "-1" = error
        public int totalTime;         // seconds
        public boolean[] on = new boolean[6];
        public String[] plugName = new String[6];
        public boolean hasPlugData = false;   // true only if reply contained plug_N fields
        public boolean hasSensorData = false; // true if reply contained power/total_time
    }

    private final String mac;
    private static DatagramSocket socket;
    private static volatile boolean running = false;
    private static final CopyOnWriteArrayList<Listener> ALL_LISTENERS = new CopyOnWriteArrayList<>();
    private static final String TAG = "Tc1Client";

    /** MAC of the last zTC1 that answered a broadcast — used for auto discovery. */
    private static volatile String LAST_MAC = null;

    public Tc1Client(String mac) { this.mac = mac; }

    public static String lastMac() { return LAST_MAC; }
    public static void forgetLastMac() { LAST_MAC = null; }

    // Listeners are process-wide: every active Activity receives every reply.
    public void addListener(Listener l) { if (!ALL_LISTENERS.contains(l)) ALL_LISTENERS.add(l); }
    public void removeListener(Listener l) { ALL_LISTENERS.remove(l); }

    /** Start the shared socket + listener thread once for the whole process. */
    public void start() { ensureStarted(); }

    public static synchronized void ensureStarted() {
        if (running && socket != null && !socket.isClosed()) return;

        try {
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.setBroadcast(true);           // required for 255.255.255.255
            socket.bind(new InetSocketAddress(PHONE_UDP_PORT));
        } catch (Exception e) {
            notifyErrorStatic("UDP 端口占用: " + e.getMessage());
            return;
        }
        running = true;
        new Thread(new Runnable() {
            @Override public void run() {
                byte[] buf = new byte[2048];
                while (running && socket != null && !socket.isClosed()) {
                    try {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        socket.receive(p);
                        String msg = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                        Tc1State s = parse(msg);
                        if (s != null) notifyState(s);
                    } catch (Exception e) {
                        if (running) Log.w(TAG, "receive: " + e.getMessage());
                    }
                }
            }
        }, "tc1-udp-listener").start();
    }

    /** Detach this client's listeners. The shared socket stays alive. */
    public void stop() {
        // shared socket intentionally kept for the process lifetime
    }

    public void detach(Listener l) { ALL_LISTENERS.remove(l); }

    /** Send a raw JSON command via broadcast. */
    public void send(String json) {
        new Thread(new Runnable() {
            @Override public void run() {
                sendNow(json);
            }
        }, "tc1-udp-send").start();
    }

    /** Fire-and-forget broadcast from a static context (auto discovery). */
    public static void sendBroadcast(String json) {
        ensureStarted();
        new Thread(new Runnable() {
            @Override public void run() {
                sendNow(json);
            }
        }, "tc1-udp-send").start();
    }

    private static void sendNow(String json) {
        try {
            if (socket == null) {
                socket = new DatagramSocket(null);
                socket.setReuseAddress(true);
                socket.setBroadcast(true);
                socket.bind(new InetSocketAddress(PHONE_UDP_PORT));
            }
            InetAddress addr = InetAddress.getByName("255.255.255.255");
            byte[] data = json.getBytes(StandardCharsets.UTF_8);
            DatagramPacket p = new DatagramPacket(data, data.length, addr, DEVICE_UDP_PORT);
            socket.send(p);
        } catch (Exception e) {
            notifyErrorStatic("发送失败: " + e.getMessage());
        }
    }

    public void query() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"mac\":\"").append(mac).append("\",\"version\":null");
        for (int i = 0; i < 6; i++) {
            sb.append(",\"plug_").append(i)
              .append("\":{\"on\":null,\"setting\":{\"name\":null}}");
        }
        // asking for these nulls makes the device include them in its reply
        sb.append(",\"power\":null,\"total_time\":null,\"name\":null");
        sb.append('}');
        send(sb.toString());
    }

    public void setPlug(int index, boolean on) {
        if (index < 0 || index > 5) return;
        send("{\"mac\":\"" + mac + "\",\"plug_" + index + "\":{\"on\":" + (on ? 1 : 0) + "}}");
    }

    public void setAll(boolean on) {
        StringBuilder sb = new StringBuilder("{\"mac\":\"").append(mac).append('"');
        int s = on ? 1 : 0;
        for (int i = 0; i < 6; i++) {
            sb.append(",\"plug_").append(i).append("\":{\"on\":").append(s).append('}');
        }
        sb.append('}');
        send(sb.toString());
    }

    public void renamePlug(int index, String name) {
        if (index < 0 || index > 5) return;
        String safe = name.replace("\\", "").replace("\"", "").replace("\n", "");
        send("{\"mac\":\"" + mac + "\",\"plug_" + index
             + "\":{\"setting\":{\"name\":\"" + safe + "\"}}}");
    }

    private static Tc1State parse(String json) {
        try {
            JSONObject o = new JSONObject(json);
            if (!o.has("mac")) return null;
            Tc1State s = new Tc1State();
            s.mac = o.optString("mac");
            if (s.mac != null && s.mac.length() == 12) LAST_MAC = s.mac.toLowerCase();
            // accept our device, or a broadcast status from any zTC1
            s.name = o.optString("name", null);
            s.version = o.optString("version", null);
            if (o.has("power")) { s.power = String.valueOf(o.opt("power")); s.hasSensorData = true; }
            if (o.has("total_time")) s.hasSensorData = true;
            s.totalTime = o.optInt("total_time", 0);
            for (int i = 0; i < 6; i++) {
                JSONObject plug = o.optJSONObject("plug_" + i);
                if (plug != null) {
                    s.hasPlugData = true;
                    if (plug.has("on")) s.on[i] = plug.optInt("on", 0) != 0;
                    JSONObject setting = plug.optJSONObject("setting");
                    if (setting != null && setting.has("name")) {
                        s.plugName[i] = setting.optString("name");
                    }
                }
            }
            return s;
        } catch (Exception e) {
            Log.w(TAG, "parse: " + e.getMessage());
            return null;
        }
    }

    private static void notifyState(final Tc1State s) {
        for (Listener l : ALL_LISTENERS) l.onState(s);
    }
    private static void notifyErrorStatic(final String msg) {
        for (Listener l : ALL_LISTENERS) l.onError(msg);
    }
}
