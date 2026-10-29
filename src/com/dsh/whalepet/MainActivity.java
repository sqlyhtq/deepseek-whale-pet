package com.dsh.whalepet;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.util.Log;
import java.io.File;

public class MainActivity extends Activity {

    private TextView status;
    private Button toggle;
    private EditText baseUrl, apiKey, model, breezyKey;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#EAF4FF"));
        int pad = dp(20);
        root.setPadding(pad, dp(28), pad, pad);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("🐋 鲸鱼娘桌宠");
        title.setTextSize(22);
        title.setTextColor(Color.parseColor("#27334F"));
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        status = new TextView(this);
        status.setTextSize(14);
        status.setTextColor(Color.parseColor("#5D739B"));
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(10);
        root.addView(status, slp);

        toggle = new Button(this);
        toggle.setText("开启桌宠");
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(14);
        root.addView(toggle, blp);
        toggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!Settings.canDrawOverlays(MainActivity.this)) {
                    requestOverlay();
                    return;
                }
                if (PetService.isRunning) {
                    stopService(new Intent(MainActivity.this, PetService.class));
                    PetService.setEnabled(MainActivity.this, false);
                } else {
                    startPet();
                }
                refresh();
            }
        });

        Button chat = new Button(this);
        chat.setText("开始对话");
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(8);
        root.addView(chat, clp);
        chat.setOnClickListener(v -> startActivity(new Intent(this, ChatActivity.class)));

        TextView sv = new TextView(this);
        sv.setText("模型设置（DeepSeek 官方接口）");
        sv.setTextSize(15);
        sv.setTextColor(Color.parseColor("#27334F"));
        LinearLayout.LayoutParams svlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        svlp.topMargin = dp(24);
        root.addView(sv, svlp);

        baseUrl = field(root, "接口地址", "https://api.deepseek.com", false);
        apiKey = field(root, "API Key", "", true);
        model = field(root, "模型", "deepseek-chat", false);

        TextView vl = new TextView(this);
        vl.setText("语音音色");
        vl.setTextSize(13);
        vl.setTextColor(Color.parseColor("#5D739B"));
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        vlp.topMargin = dp(14);
        root.addView(vl, vlp);

        final Spinner voiceSpin = new Spinner(this);
        String[] voices = {"大肥鱼 · 机灵版（官方）", "大肥鱼 · 迷糊版（官方）",
                "晓伊 · 少女音", "晓晓 · 温柔女声", "云希 · 少年音", "系统 TTS（离线）"};
        final String[] voiceKeys = {"dafeiyu-clever", "dafeiyu-dreamy", "xiaoyi", "xiaoxiao", "yunxi", "system"};
        voiceSpin.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, voices));
        root.addView(voiceSpin);

        String cur = getSharedPreferences("pet", MODE_PRIVATE).getString("ttsVoice", "dafeiyu-clever");
        for (int i = 0; i < voiceKeys.length; i++) if (voiceKeys[i].equals(cur)) voiceSpin.setSelection(i);

        TextView fl = new TextView(this);
        fl.setText("动作帧率（越高越顺，越费电）");
        fl.setTextSize(13);
        fl.setTextColor(Color.parseColor("#5D739B"));
        LinearLayout.LayoutParams fllp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        fllp.topMargin = dp(14);
        root.addView(fl, fllp);

        final Spinner fpsSpin = new Spinner(this);
        String[] fpsLabels = {"120 fps（默认，最顺）", "90 fps", "60 fps"};
        final int[] fpsValues = {120, 90, 60};
        fpsSpin.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, fpsLabels));
        root.addView(fpsSpin);
        int curFps = getSharedPreferences("pet", MODE_PRIVATE).getInt("petFps", 120);
        for (int i = 0; i < fpsValues.length; i++) if (fpsValues[i] == curFps) fpsSpin.setSelection(i);
        fpsSpin.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view, int position, long id) {
                int v = fpsValues[Math.max(0, Math.min(fpsValues.length - 1, position))];
                getSharedPreferences("pet", MODE_PRIVATE).edit().putInt("petFps", v).apply();
                PetService.applyFps(v);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

        TextView bl = new TextView(this);
        bl.setText("气泡停留时长：7 秒");
        bl.setTextSize(13);
        bl.setTextColor(Color.parseColor("#5D739B"));
        LinearLayout.LayoutParams bllp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bllp.topMargin = dp(14);
        root.addView(bl, bllp);

        final android.widget.SeekBar bubbleBar = new android.widget.SeekBar(this);
        bubbleBar.setMax(10);   // 5..15
        bubbleBar.setProgress(Math.max(0, Math.min(10,
                getSharedPreferences("pet", MODE_PRIVATE).getInt("bubbleSeconds", 7) - 5)));
        root.addView(bubbleBar);
        bubbleBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                int sec = 5 + progress;
                bl.setText("气泡停留时长：" + sec + " 秒");
                getSharedPreferences("pet", MODE_PRIVATE).edit().putInt("bubbleSeconds", sec).apply();
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar seekBar) {
            }
        });

        TextView modelLabel = new TextView(this);
        modelLabel.setText("对话模型");
        modelLabel.setTextSize(13);
        modelLabel.setTextColor(Color.parseColor("#5D739B"));
        LinearLayout.LayoutParams mllp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mllp.topMargin = dp(12);
        root.addView(modelLabel, mllp);

        final String[] modelIds = {"deepseek-flash", "deepseek-v4-pro"};
        final String[] modelNames = {"deepseek-flash（快、便宜）", "deepseek-v4-pro（专业版，更聪明）"};
        final android.widget.Spinner modelSpinner = new android.widget.Spinner(this);
        android.widget.ArrayAdapter<String> modelAdapter = new android.widget.ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, modelNames);
        modelSpinner.setAdapter(modelAdapter);
        String curModel = getSharedPreferences("pet", MODE_PRIVATE).getString("model", "deepseek-flash");
        modelSpinner.setSelection(modelIds[1].equals(curModel) ? 1 : 0);
        modelSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view,
                                       int position, long id) {
                getSharedPreferences("pet", MODE_PRIVATE).edit()
                        .putString("model", modelIds[position]).apply();
                if (model != null) model.setText(modelIds[position]);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
        root.addView(modelSpinner);

        TextView asrLabel = new TextView(this);
        asrLabel.setText("语音识别模型");
        asrLabel.setTextSize(13);
        asrLabel.setTextColor(Color.parseColor("#5D739B"));
        LinearLayout.LayoutParams allp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        allp.topMargin = dp(14);
        root.addView(asrLabel, allp);

        final android.widget.Spinner asrSpinner = new android.widget.Spinner(this);
        final String[] asrNames = {"大模型（更准，占 3GB 内存）", "小模型（省内存，准确率低）"};
        android.widget.ArrayAdapter<String> asrAdapter = new android.widget.ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, asrNames);
        asrSpinner.setAdapter(asrAdapter);
        boolean isBig = getSharedPreferences("pet", MODE_PRIVATE)
                .getString("asrModel", "big").equals("big");
        asrSpinner.setSelection(isBig ? 0 : 1);
        asrSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view,
                                       int position, long id) {
                getSharedPreferences("pet", MODE_PRIVATE).edit()
                        .putString("asrModel", position == 0 ? "big" : "small").apply();
                VoiceInput.reset();
                PetService svc = PetService.INSTANCE;
                if (svc != null) svc.restartWakeForModelChange();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
        root.addView(asrSpinner);

        final Button bigModel = new Button(this);
        bigModel.setText(BigModel.isReady(this) ? "大模型：已就绪 ✓" : "下载大模型（1.3GB，更准）");
        bigModel.setAllCaps(false);
        LinearLayout.LayoutParams bmlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bmlp.topMargin = dp(10);
        root.addView(bigModel, bmlp);
        bigModel.setOnClickListener(v -> {
            if (BigModel.isReady(this)) {
                android.widget.Toast.makeText(this, "已经装好啦", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            bigModel.setEnabled(false);
            bigModel.setText("开始下载…");
            BigModel.start(this, new BigModel.Progress() {
                @Override
                public void onProgress(int percent, long done, long total) {
                    runOnUiThread(() -> bigModel.setText(percent >= 0
                            ? "下载中 " + percent + "%"
                            : "解压中… " + done + " 个文件"));
                }

                @Override
                public void onDone(boolean ok, String message) {
                    runOnUiThread(() -> {
                        bigModel.setEnabled(true);
                        bigModel.setText(ok ? "大模型：已就绪 ✓" : "下载失败，点我重试");
                        android.widget.Toast.makeText(MainActivity.this,
                                ok ? "大模型准备完成" : ("失败：" + message),
                                android.widget.Toast.LENGTH_LONG).show();
                    });
                }
            });
        });

        Button preview = new Button(this);
        preview.setText("试听");
        root.addView(preview);
        final Tts[] ttsRef = new Tts[1];
        preview.setOnClickListener(v -> {
            String key = voiceKeys[Math.max(0, voiceSpin.getSelectedItemPosition())];
            getSharedPreferences("pet", MODE_PRIVATE).edit()
                    .putString("ttsVoice", key)
                    .putString("breezyKey", breezyKey.getText().toString().trim())
                    .apply();
            if (ttsRef[0] != null) ttsRef[0].release();
            ttsRef[0] = new Tts(this);
            ttsRef[0].speak("你好呀，我是鲸鱼娘，今天也要好好摸鱼。");
        });

        final Switch wakeSwitch = new Switch(this);
        wakeSwitch.setText("语音唤醒「小鲸小鲸」（只在桌面待机时听）");
        wakeSwitch.setTextSize(13);
        wakeSwitch.setChecked(getSharedPreferences("pet", MODE_PRIVATE).getBoolean("wake", false));
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        wlp.topMargin = dp(12);
        root.addView(wakeSwitch, wlp);
        wakeSwitch.setOnCheckedChangeListener((b, checked) ->
                getSharedPreferences("pet", MODE_PRIVATE).edit().putBoolean("wake", checked).apply());

        TextView bt = new TextView(this);
        bt.setText("语音：BreezeBlue 官方「大肥鱼」音色 Key");
        bt.setTextSize(15);
        bt.setTextColor(Color.parseColor("#27334F"));
        LinearLayout.LayoutParams btlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btlp.topMargin = dp(18);
        root.addView(bt, btlp);
        breezyKey = new EditText(this);
        breezyKey.setHint("xi-api-key（breezeblue.ai 开发者页申请）");
        breezyKey.setTextSize(14);
        breezyKey.setSingleLine(true);
        breezyKey.setText(getSharedPreferences("pet", MODE_PRIVATE).getString("breezyKey", ""));
        root.addView(breezyKey);

        Button shizuku = new Button(this);
        shizuku.setText("授予 Shizuku 权限（读前台信息用）");
        LinearLayout.LayoutParams shlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        shlp.topMargin = dp(10);
        root.addView(shizuku, shlp);
        shizuku.setOnClickListener(v -> {
            if (!ForegroundReader.shizukuAlive()) {
                Toast.makeText(this, "没连上 Shizuku，先在设置里启动它", Toast.LENGTH_LONG).show();
                return;
            }
            if (ForegroundReader.shizukuGranted()) {
                Toast.makeText(this, "已经授权过了", Toast.LENGTH_SHORT).show();
                return;
            }
            ForegroundReader.requestShizuku();
        });

        Button diag = new Button(this);
        diag.setText("一键自检");
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(14);
        root.addView(diag, dlp);
        diag.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
                .setTitle("鲸鱼娘自检")
                .setMessage(diagnostics())
                .setPositiveButton("知道了", null)
                .show());

        Button save = new Button(this);
        save.setText("保存设置");
        LinearLayout.LayoutParams saplp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        saplp.topMargin = dp(10);
        root.addView(save, saplp);
        save.setOnClickListener(v -> {
            getSharedPreferences("pet", MODE_PRIVATE).edit()
                    .putString("baseUrl", baseUrl.getText().toString().trim())
                    .putString("apiKey", apiKey.getText().toString().trim())
                    .putString("model", model.getText().toString().trim())
                    .putString("ttsVoice", voiceKeys[Math.max(0, voiceSpin.getSelectedItemPosition())])
                    .putString("breezyKey", breezyKey.getText().toString().trim())
                    .apply();
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
        });

        TextView hint = new TextView(this);
        hint.setText("Key 只存在本机 SharedPreferences，代码和安装包里不含任何 key。\n语音识别：离线 Vosk（可选大小模型）；朗读：BreezeBlue 大肥鱼，失败回退系统 TTS。");
        hint.setTextSize(12);
        hint.setTextColor(Color.parseColor("#7C8FB5"));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(16);
        root.addView(hint, hlp);

        // 老版本默认是晓伊，这里一次性迁到官方「大肥鱼·机灵版」
        android.content.SharedPreferences sp = getSharedPreferences("pet", MODE_PRIVATE);
        if (!sp.getBoolean("voiceMigrated", false)) {
            sp.edit().putString("ttsVoice", "dafeiyu-clever").putBoolean("voiceMigrated", true).apply();
        }
        if (!sp.getBoolean("wakeDefaultV1", false)) {
            // 首次升级：默认打开唤醒词
            sp.edit().putBoolean("wake", true).putBoolean("wakeDefaultV1", true).apply();
        }

        setContentView(scroll);
        loadSettings();
        refresh();
    }

    private EditText field(LinearLayout parent, String label, String def, boolean secret) {
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(13);
        l.setTextColor(Color.parseColor("#5D739B"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        parent.addView(l, lp);

        EditText e = new EditText(this);
        e.setHint(def);
        e.setTextSize(14);
        e.setSingleLine(true);
        if (secret) e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        parent.addView(e);
        return e;
    }

    private String diagnostics() {
        StringBuilder sb = new StringBuilder();
        sb.append(Settings.canDrawOverlays(this) ? "✓ 悬浮窗权限" : "✗ 悬浮窗权限（点上面授权）").append('\n');
        sb.append(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == android.content.pm.PackageManager.PERMISSION_GRANTED
                ? "✓ 麦克风权限" : "✗ 麦克风权限（说话功能不可用）").append('\n');
        sb.append(PetService.isRunning ? "✓ 桌宠在跑" : "✗ 桌宠没在跑").append('\n');
        boolean model = new File(getFilesDir(), "vosk-cn/.ready").exists();
        sb.append(model ? "✓ 离线语音模型已解压" : "！语音模型未解压（首次说话时需要一次解压）").append('\n');
        String key = getSharedPreferences("pet", MODE_PRIVATE).getString("apiKey", "");
        sb.append(key.trim().isEmpty() ? "✗ 模型 API Key 未填" : "✓ 模型 API Key 已填（" + key.length() + " 位）").append('\n');
        String bz = getSharedPreferences("pet", MODE_PRIVATE).getString("breezyKey", "");
        sb.append(bz.trim().isEmpty() ? "！BreezeBlue Key 未填（大肥鱼音色会回落晓伊）" : "✓ BreezeBlue Key 已填").append('\n');
        sb.append("语音引擎：").append(getSharedPreferences("pet", MODE_PRIVATE).getString("ttsVoice", "dafeiyu-clever")).append('\n');
        sb.append("唤醒词：").append(getSharedPreferences("pet", MODE_PRIVATE).getBoolean("wake", false) ? "开" : "关").append('\n');
        sb.append("模型：").append(getSharedPreferences("pet", MODE_PRIVATE).getString("model", "deepseek-chat"));
        return sb.toString();
    }

    private void loadSettings() {
        baseUrl.setText(getSharedPreferences("pet", MODE_PRIVATE).getString("baseUrl", "https://api.deepseek.com"));
        apiKey.setText(getSharedPreferences("pet", MODE_PRIVATE).getString("apiKey", ""));
        model.setText(getSharedPreferences("pet", MODE_PRIVATE).getString("model", "deepseek-chat"));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Settings.canDrawOverlays(this) && !PetService.isRunning && PetService.wasEnabled(this)) {
            startPet();
        }
        refresh();
        Log.i("WhalePet", "diagnostics:\n" + diagnostics());
    }

    private void startPet() {
        Intent i = new Intent(this, PetService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
        PetService.setEnabled(this, true);
    }

    private void requestOverlay() {
        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        startActivity(i);
    }

    private void refresh() {
        boolean can = Settings.canDrawOverlays(this);
        if (!can) {
            status.setText("需要「显示在其他应用上层」权限");
            toggle.setText("去授权");
        } else if (PetService.isRunning) {
            status.setText("桌宠运行中：她在桌面上待着");
            toggle.setText("关闭桌宠");
        } else {
            status.setText("已授权，尚未开启");
            toggle.setText("开启桌宠");
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
