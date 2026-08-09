package com.app.idemobile;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
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
        makeSystemBarsTransparent();
        setContentView(R.layout.activity_splash);

        View root = findViewById(R.id.splash_image);
        if (root != null) {
            root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }

        handler.postDelayed(go, SPLASH_MS);
    }

    @Override
    protected void onResume() {
        super.onResume();
        makeSystemBarsTransparent();
    }


    /** Делает системные панели прозрачными на заставке. */
    private void makeSystemBarsTransparent() {
        Window window = getWindow();
        if (window == null) return;

        if (Build.VERSION.SDK_INT >= 21) {
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                    | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
            window.setStatusBarColor(Color.TRANSPARENT);
            window.setNavigationBarColor(Color.TRANSPARENT);
        }

        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        if (Build.VERSION.SDK_INT >= 26) {
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        window.getDecorView().setSystemUiVisibility(flags);
    }


    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) makeSystemBarsTransparent();
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
