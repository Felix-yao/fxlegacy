package com.r1.controller;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int PORT = 5555;
    private static final int TIMEOUT = 20000;
    private static final int PING_TIMEOUT = 8000;   // phone->R1 WiFi can take 3-4s to handshake
    private static final String PREF = "r1pref";
    private static final String KEY_IP = "ip";
    private static final String KEY_SLEEP = "sleeping";
    private static final String KEY_SLEEP_VOL = "sleepVol";

    private TextView statusText, infoText, volumeText, refreshBtn;
    private TextView timerStatus, timerCancel, sleepHint, sleepSwitch;
    private TextView infoFw, infoWifi, infoUptime, infoBt;
    private TextView btButton;
    private View dot;
    private SeekBar seekBar;
    private EditText ipEdit;

    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private volatile boolean busy = false;
    private int currentVolume = -1;
    private volatile String ip = "192.168.1.100"; // replaced in onCreate
    private AdbClient.Session session = null;
    private String sessionIp = null;

    /** Get a live session, reconnecting only when needed (one handshake). */
    private AdbClient.Session session() throws IOException {
        if (session == null || session.isDead() || !ip.equals(sessionIp)) {
            if (session != null) session.close();
            session = AdbClient.connect(ip, PORT, TIMEOUT);
            sessionIp = ip;
        }
        return session;
    }

    private boolean sleeping = false;
    private int sleepRememberVol = 6;

    private long timerEndAt = 0;
    private int fadeStartVol = 0;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (timerEndAt == 0) return;
            long left = timerEndAt - System.currentTimeMillis();
            if (left <= 0) { finishTimer(); return; }
            long sec = left / 1000;
            String mmss = String.format("%02d:%02d", sec / 60, sec % 60);
            timerStatus.setText("渐弱至静音 · 剩余 " + mmss);
            if (left <= 60000 && !sleeping && fadeStartVol > 0) {
                int step = (int) ((long) fadeStartVol * left / 60000);
                if (step >= 0 && step <= 15 && step != currentVolume) applyVolumeAsync(step);
            }
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        findViewById(R.id.r1Back).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });

        statusText = findViewById(R.id.statusText);
        infoText = findViewById(R.id.infoText);
        volumeText = findViewById(R.id.volumeText);
        timerStatus = findViewById(R.id.timerStatus);
        timerCancel = findViewById(R.id.timerCancel);
        sleepHint = findViewById(R.id.sleepHint);
        sleepSwitch = findViewById(R.id.sleepSwitch);
        infoFw = findViewById(R.id.infoFw);
        infoWifi = findViewById(R.id.infoWifi);
        infoUptime = findViewById(R.id.infoUptime);
        infoBt = findViewById(R.id.infoBt);
        btButton = findViewById(R.id.btButton);
        dot = findViewById(R.id.dot);
        seekBar = findViewById(R.id.seekBar);
        ipEdit = findViewById(R.id.ipEdit);

        android.content.SharedPreferences p = getSharedPreferences(PREF, MODE_PRIVATE);
        ip = p.getString(KEY_IP, getString(R.string.r1_ip));
        ipEdit.setText(ip);
        sleeping = p.getBoolean(KEY_SLEEP, false);
        sleepRememberVol = p.getInt(KEY_SLEEP_VOL, 6);
        updateSleepUI();

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int prog, boolean fromUser) {
                if (fromUser) volumeText.setText(prog + " / 15");
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { setVolume(s.getProgress()); }
        });

        click(R.id.refreshBtn, new Runnable() { @Override public void run() { refresh(); } });
        click(R.id.saveBtn, new Runnable() { @Override public void run() { saveIp(); } });
        click(R.id.timerCancel, new Runnable() { @Override public void run() { cancelTimer(); } });
        click(R.id.sleepSwitch, new Runnable() { @Override public void run() { toggleSleep(); } });
        click(R.id.btButton, new Runnable() { @Override public void run() { openBluetooth(); } });
        bindPreset(R.id.v2, 2);  bindPreset(R.id.v4, 4);  bindPreset(R.id.v6, 6);
        bindPreset(R.id.v8, 8);  bindPreset(R.id.v10, 10); bindPreset(R.id.v12, 12);
        click(R.id.t15, new Runnable() { @Override public void run() { startTimer(15); } });
        click(R.id.t30, new Runnable() { @Override public void run() { startTimer(30); } });
        click(R.id.t60, new Runnable() { @Override public void run() { startTimer(60); } });

        refresh();
    }

    private void click(int id, final Runnable r) {
        findViewById(id).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { r.run(); }
        });
    }

    private void bindPreset(int id, final int value) {
        click(id, new Runnable() { @Override public void run() { setVolume(value); } });
    }

    private void saveIp() {
        String s = ipEdit.getText().toString().trim();
        if (TextUtils.isEmpty(s)) { Toast.makeText(this, "请输入 IP", Toast.LENGTH_SHORT).show(); return; }
        ip = s;
        currentVolume = -1;
        getSharedPreferences(PREF, MODE_PRIVATE).edit().putString(KEY_IP, ip).apply();
        Toast.makeText(this, "已保存 " + ip, Toast.LENGTH_SHORT).show();
        refresh();
    }

    private void setStatus(final boolean online, final String detail) {
        ui.post(new Runnable() {
            @Override public void run() {
                dot.setBackgroundColor(Color.parseColor(online ? "#34C759" : "#E24B4A"));
                statusText.setText(online ? "音箱在线" : "音箱离线");
                statusText.setTextColor(Color.parseColor(online ? "#000000" : "#E24B4A"));
                infoText.setText(detail);
            }
        });
    }

    private void showVolume(final int v) {
        ui.post(new Runnable() {
            @Override public void run() {
                volumeText.setText(v + " / 15");
                seekBar.setProgress(v);
                highlightPreset(v);
            }
        });
    }

    private void highlightPreset(int v) {
        int[] ids = {R.id.v2, R.id.v4, R.id.v6, R.id.v8, R.id.v10, R.id.v12};
        int[] vals = {2, 4, 6, 8, 10, 12};
        for (int i = 0; i < ids.length; i++) {
            TextView t = findViewById(ids[i]);
            boolean on = (vals[i] == v);
            t.setBackgroundResource(on ? R.drawable.pill_active : R.drawable.pill);
            t.setTextColor(on ? 0xFFFFFFFF : 0xFF007AFF);
        }
    }

    private void refresh() {
        if (busy) return;
        busy = true;
        final String host = ip;
        pool.execute(new Runnable() {
            @Override public void run() {
                try {
                    // Don't do a separate raw-TCP isOnline(): opening and
                    // closing a bare socket leaves the R1's adbd in CLOSE_WAIT
                    // and eventually it stops answering. Use the real shell
                    // handshake as the liveness check instead - one connection.
                    AdbClient.Session s;
                    String dump;
                    try {
                        s = session();
                        dump = s.shell("dumpsys audio");
                    } catch (Exception e) {
                        setStatus(false, "连不上 " + host + "，确认音箱通电并连着同一 WiFi（" + e.getMessage() + "）");
                        return;
                    }
                    int v = AdbClient.parseVolume(dump);
                    if (v >= 0 && !sleeping) { currentVolume = v; showVolume(v); }
                    setStatus(true, "已直连 " + host);
                    final String[] info = AdbClient.readInfo(s);
                    ui.post(new Runnable() {
                        @Override public void run() {
                            infoFw.setText(info[0]);
                            infoWifi.setText(info[1]);
                            infoUptime.setText(info[2]);
                            infoBt.setText(info[3]);
                        }
                    });
                } catch (final Exception e) {
                    setStatus(false, "连接出错：" + e.getMessage());
                } finally {
                    busy = false;
                }
            }
        });
    }

    private void sendVolume(final String cmd, final String failMsg) {
        if (cmd == null) return;
        busy = true;
        final String host = ip;
        pool.execute(new Runnable() {
            @Override public void run() {
                try { session().shell(cmd); }
                catch (Exception e) { if (failMsg != null) setStatus(false, failMsg + e.getMessage()); }
                finally { busy = false; }
            }
        });
    }

    private void applyVolumeAsync(int target) {
        String cmd = AdbClient.volumeCommand(currentVolume, target);
        currentVolume = target;
        showVolume(target);
        sendVolume(cmd, null);
    }

    private void setVolume(final int target) {
        if (sleeping) { Toast.makeText(this, "休眠中，请先关闭休眠开关", Toast.LENGTH_SHORT).show(); return; }
        if (busy) { Toast.makeText(this, "忙，稍等", Toast.LENGTH_SHORT).show(); return; }
        if (currentVolume < 0) {
            Toast.makeText(this, "请先点刷新读取音量", Toast.LENGTH_SHORT).show();
            refresh();
            return;
        }
        if (target == currentVolume) return;
        final String cmd = AdbClient.volumeCommand(currentVolume, target);
        if (cmd == null) return;
        busy = true;
        volumeText.setText("设置中…");
        final int expected = target;
        pool.execute(new Runnable() {
            @Override public void run() {
                try {
                    AdbClient.Session s = session();
                    s.shell(cmd);
                    // read back the REAL volume and correct the UI
                    String dump = s.shell("dumpsys audio");
                    int v = AdbClient.parseVolume(dump);
                    if (v >= 0) {
                        currentVolume = v;
                        showVolume(v);
                    } else {
                        currentVolume = expected;
                        showVolume(expected);
                    }
                } catch (Exception e) {
                    currentVolume = expected;
                    showVolume(expected);
                    setStatus(false, "设置失败：" + e.getMessage());
                } finally {
                    busy = false;
                }
            }
        });
    }

    private void updateSleepUI() {
        sleepSwitch.setBackgroundResource(sleeping ? R.drawable.switch_on : R.drawable.switch_off);
        sleepHint.setText(sleeping ? "已休眠 · 点开关恢复音量 " + sleepRememberVol : "关闭后恢复到原音量");
    }

    private void toggleSleep() {
        if (busy) { Toast.makeText(this, "忙，稍等", Toast.LENGTH_SHORT).show(); return; }
        final boolean goingToSleep = !sleeping;
        if (goingToSleep && currentVolume < 0) {
            Toast.makeText(this, "请先点刷新", Toast.LENGTH_SHORT).show();
            refresh();
            return;
        }
        if (goingToSleep) sleepRememberVol = currentVolume;
        final int from = goingToSleep ? currentVolume : 0;
        final int to = goingToSleep ? 0 : sleepRememberVol;
        final String cmd = AdbClient.volumeCommand(from, to);
        sleeping = goingToSleep;
        updateSleepUI();
        volumeText.setText(goingToSleep ? "休眠中…" : "恢复中…");
        busy = true;
        getSharedPreferences(PREF, MODE_PRIVATE).edit()
            .putBoolean(KEY_SLEEP, sleeping).putInt(KEY_SLEEP_VOL, sleepRememberVol).apply();
        pool.execute(new Runnable() {
            @Override public void run() {
                try {
                    AdbClient.Session s = session();
                    if (cmd != null) s.shell(cmd);
                    String dump = s.shell("dumpsys audio");
                    int v = AdbClient.parseVolume(dump);
                    if (v >= 0) { currentVolume = v; showVolume(v); }
                    else { currentVolume = to; showVolume(to); }
                } catch (Exception e) {
                    currentVolume = to;
                    showVolume(to);
                    setStatus(false, "操作失败：" + e.getMessage());
                } finally {
                    busy = false;
                }
            }
        });
    }

    private void openBluetooth() {
        if (busy) { Toast.makeText(this, "忙，稍等", Toast.LENGTH_SHORT).show(); return; }
        busy = true;
        final String tap = AdbClient.bluetoothTapCommand();
        final String original = "打开蓝牙模式";
        btButton.setEnabled(false);
        btButton.setText("正在发送…");
        pool.execute(new Runnable() {
            @Override public void run() {
                try {
                    AdbClient.Session s = session();
                    // Three separate taps, ~180ms apart, exactly like a real
                    // triple-press. Spacing is essential: a burst is seen as
                    // one long press and does NOT trigger TRIPLE_CLICK.
                    for (int i = 0; i < 3; i++) {
                        if (i > 0) Thread.sleep(180);
                        s.shell(tap);
                    }
                    ui.post(new Runnable() {
                        @Override public void run() {
                            btButton.setText("已发送 ✓");
                            btButton.setBackgroundColor(0xFF34C759);
                            Toast.makeText(MainActivity.this,
                                "已触发蓝牙模式，稍等电脑自动连接", Toast.LENGTH_LONG).show();
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override public void run() {
                            btButton.setText(original);
                            btButton.setBackgroundResource(R.drawable.bt_button);
                            setStatus(false, "发送失败：" + e.getMessage());
                        }
                    });
                } finally {
                    busy = false;
                    ui.postDelayed(new Runnable() {
                        @Override public void run() {
                            btButton.setEnabled(true);
                            btButton.setText(original);
                            btButton.setBackgroundResource(R.drawable.bt_button);
                        }
                    }, 2500);
                }
            }
        });
    }

    private void startTimer(final int minutes) {
        if (sleeping) { Toast.makeText(this, "休眠中，请先关闭休眠开关", Toast.LENGTH_SHORT).show(); return; }
        if (currentVolume < 0) {
            Toast.makeText(this, "请先点刷新读取音量", Toast.LENGTH_SHORT).show();
            refresh();
            return;
        }
        timerEndAt = System.currentTimeMillis() + minutes * 60 * 1000L;
        fadeStartVol = currentVolume;
        timerCancel.setVisibility(View.VISIBLE);
        Toast.makeText(this, minutes + " 分钟后渐弱静音", Toast.LENGTH_SHORT).show();
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    private void cancelTimer() {
        timerEndAt = 0;
        timerCancel.setVisibility(View.GONE);
        timerStatus.setText("渐弱至静音");
        ui.removeCallbacks(tick);
        Toast.makeText(this, "已取消定时", Toast.LENGTH_SHORT).show();
    }

    private void finishTimer() {
        timerEndAt = 0;
        timerCancel.setVisibility(View.GONE);
        timerStatus.setText("已完成 · 音量渐弱到 0");
        applyVolumeAsync(0);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacks(tick);
        pool.shutdownNow();
        if (session != null) { session.close(); session = null; }
    }
}
