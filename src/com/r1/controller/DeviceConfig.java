package com.r1.controller;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Per-device addressing + auto discovery.
 *
 * The app talks to both devices directly over the LAN, so it needs an address
 * for each: the R1 speaker by IPv4, the TC1 strip by MAC (the zTC1 protocol
 * carries the MAC inside every JSON command). Both can be discovered
 * automatically so a fresh install works without any setup.
 */
public final class DeviceConfig {

    private static final String TAG = "DeviceConfig";
    private static final String PREF = "r1pref";
    private static final String KEY_R1_IP = "ip";
    private static final String KEY_TC1_MAC = "tc1Mac";
    private static final String KEY_R1_VERIFIED = "r1Verified";
    private static final String KEY_TC1_VERIFIED = "tc1Verified";

    private static final int ADB_PORT = 5555;

    private DeviceConfig() { }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    // ---------------- R1 (IPv4) ----------------

    /** Saved IP, falling back to the built-in default. */
    public static String r1Ip(Context c) {
        String v = prefs(c).getString(KEY_R1_IP, null);
        if (v == null || v.trim().length() == 0) v = c.getString(R.string.r1_ip);
        return v;
    }

    public static void setR1Ip(Context c, String ip) {
        prefs(c).edit().putString(KEY_R1_IP, ip).apply();
    }

    /** True once we have successfully talked to the speaker at this IP. */
    public static boolean r1Verified(Context c) {
        return prefs(c).getBoolean(KEY_R1_VERIFIED, false);
    }

    public static void setR1Verified(Context c, boolean ok) {
        prefs(c).edit().putBoolean(KEY_R1_VERIFIED, ok).apply();
    }

    // ---------------- TC1 (MAC) ----------------

    public static String tc1Mac(Context c) {
        String v = prefs(c).getString(KEY_TC1_MAC, null);
        if (v == null || v.length() != 12) v = c.getString(R.string.tc1_mac);
        return v;
    }

    public static void setTc1Mac(Context c, String mac) {
        prefs(c).edit().putString(KEY_TC1_MAC, mac).apply();
    }

    public static boolean tc1Verified(Context c) {
        return prefs(c).getBoolean(KEY_TC1_VERIFIED, false);
    }

    public static void setTc1Verified(Context c, boolean ok) {
        prefs(c).edit().putBoolean(KEY_TC1_VERIFIED, ok).apply();
    }

    // ---------------- discovery ----------------

    /**
     * Listen for a zTC1 status broadcast and take whatever MAC it reports.
     * Works because the strip answers queries that carry a bogus MAC.
     *
     * @return 12-char lowercase MAC, or null on timeout
     */
    public static String discoverTc1Mac(long timeoutMs) {
        Tc1Client.ensureStarted();
        Tc1Client.forgetLastMac();
        StringBuilder sb = new StringBuilder("{\"mac\":\"000000000000\"");
        for (int i = 0; i < 6; i++) {
            sb.append(",\"plug_").append(i).append("\":{\"on\":null}");
        }
        sb.append(",\"power\":null,\"total_time\":null}");
        Tc1Client.sendBroadcast(sb.toString());

        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            String m = Tc1Client.lastMac();
            if (m != null) return m;
            try {
                Thread.sleep(80);
            } catch (InterruptedException e) {
                break;
            }
        }
        return Tc1Client.lastMac();
    }

    /**
     * Scan the local /24 for a host that accepts a TCP connection on 5555
     * (the R1's ADB port). Returns the first hit, or null.
     */
    public static String discoverR1Ip(long budgetMs) {
        String local = localIpv4();
        if (local == null) {
            Log.w(TAG, "no local IPv4, cannot scan");
            return null;
        }
        int dot = local.lastIndexOf('.');
        if (dot <= 0) return null;
        final String prefix = local.substring(0, dot + 1);

        ExecutorService pool = Executors.newFixedThreadPool(48);
        CompletionService<String> cs = new ExecutorCompletionService<String>(pool);
        int submitted = 0;
        for (int i = 1; i <= 254; i++) {
            final String host = prefix + i;
            if (host.equals(local)) continue;
            cs.submit(new Callable<String>() {
                @Override public String call() {
                    Socket s = new Socket();
                    try {
                        s.connect(new InetSocketAddress(host, ADB_PORT), 400);
                        return host;
                    } catch (Exception e) {
                        return null;
                    } finally {
                        try { s.close(); } catch (Exception ignored) { }
                    }
                }
            });
            submitted++;
        }

        String found = null;
        long end = System.currentTimeMillis() + budgetMs;
        try {
            for (int i = 0; i < submitted; i++) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) break;
                Future<String> f = cs.poll(left, TimeUnit.MILLISECONDS);
                if (f == null) break;
                String r = f.get();
                if (r != null) { found = r; break; }
            }
        } catch (Exception e) {
            Log.w(TAG, "scan: " + e.getMessage());
        } finally {
            pool.shutdownNow();
        }
        return found;
    }

    /** Current WiFi/IPv4 address as a dotted string, or null. */
    private static String localIpv4() {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            List<String> candidates = new ArrayList<String>();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface nif = ifs.nextElement();
                if (!nif.isUp() || nif.isLoopback()) continue;
                String name = nif.getName();
                for (InterfaceAddress ia : nif.getInterfaceAddresses()) {
                    if (!(ia.getAddress() instanceof Inet4Address)) continue;
                    String host = ia.getAddress().getHostAddress();
                    if (host == null || host.startsWith("127.")) continue;
                    if (host.startsWith("192.168.") || host.startsWith("10.")
                            || host.startsWith("172.")) {
                        // prefer wlan
                        if (name != null && name.startsWith("wlan")) return host;
                        candidates.add(host);
                    }
                }
            }
            if (!candidates.isEmpty()) return candidates.get(0);
            return null;
        } catch (Exception e) {
            Log.w(TAG, "localIpv4: " + e.getMessage());
            return null;
        }
    }
}
