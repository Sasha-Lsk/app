package com.app.idemobile;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;

/** Стартовая заставка: 3 секунды, картинка растянута на весь экран. */
public class SplashActivity extends Activity {

    private static final long SPLASH_MS = 3000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean gone = false;

    private final Runnable go = new Runnable() {
        @Override
        public void run() {
            openMain();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_splash);

        View root = findViewById(R.id.splash_image);
        if (root != null) {
            root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }

        handler.postDelayed(go, SPLASH_MS);
    }

    private void openMain() {
        if (gone) return;
        gone = true;
        startActivity(new Intent(this, MainActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(go);
        super.onDestroy();
    }

    /** Кнопка «назад» на заставке просто закрывает приложение. */
    @Override
    public void onBackPressed() {
        gone = true;
        handler.removeCallbacks(go);
        finish();
    }
}
