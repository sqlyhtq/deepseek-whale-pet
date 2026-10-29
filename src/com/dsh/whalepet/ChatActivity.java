package com.dsh.whalepet;

import android.app.Activity;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.LinearLayout;

/** 应用内的对话页（桌面上的「聊天」按钮走悬浮窗，不经过这里）。 */
public class ChatActivity extends Activity {

    private ChatPanel panel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        panel = new ChatPanel(this, this::finish);
        setContentView(panel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));

        String sayExtra = getIntent().getStringExtra("say");
        if (sayExtra != null && !sayExtra.isEmpty()) panel.speakText(sayExtra);

        if (getIntent().getBooleanExtra("selftest", false)) panel.speakText("你好呀，我是鲸鱼娘，语音自检。");
        if (getIntent().getBooleanExtra("voice", false)) panel.startVoiceNow();
    }

    @Override
    protected void onDestroy() {
        if (panel != null) panel.release();
        super.onDestroy();
    }
}
