package com.r1.controller;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

public class DeviceListActivity extends Activity {

    private TextView r1Sub, tc1Sub;
    private Tc1Client tc1;
    private Tc1Client.Listener tcListener;
    private final Handler ui = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_device_list);
        setTitle("FX遗产");

        r1Sub = findViewById(R.id.r1Sub);
        tc1Sub = findViewById(R.id.tc1Sub);

        findViewById(R.id.cardR1).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(DeviceListActivity.this, MainActivity.class));
            }
        });
        findViewById(R.id.cardTc1).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(DeviceListActivity.this, Tc1Activity.class));
            }
        });

        // Probe TC1 once for a live subtitle (power + socket count).
        tc1 = new Tc1Client(Tc1Activity.TC1_MAC);
        tcListener = new Tc1Client.Listener() {
            @Override public void onState(final Tc1Client.Tc1State s) {
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (!s.hasPlugData) return; // sensor-only reply, skip
                        int onCount = 0;
                        for (boolean b : s.on) if (b) onCount++;
                        tc1Sub.setText("在线 · " + onCount + "/6 路开启"
                                       + (s.power != null ? " · " + s.power + "W" : ""));
                    }
                });
            }
            @Override public void onError(String msg) {
                ui.post(new Runnable() {
                    @Override public void run() { tc1Sub.setText("未检测到设备（请连同一 WiFi）"); }
                });
            }
        };
        tc1.addListener(tcListener);
        tc1.start();
        tc1.query();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (tc1 != null) { tc1.start(); tc1.query(); }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (tc1 != null && tcListener != null) tc1.detach(tcListener);
    }
}
