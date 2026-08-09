package com.app.idemobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Window;
import android.view.WindowManager;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Главный экран: WebView с содержимым папки assets. */
public class MainActivity extends Activity {

    private static final String PREFS = "idemobile";
    private static final String KEY_DONE = "webview_setup_done";
    private static final String KEY_PKG = "webview_pkg";

    private static final int RQ_FILE_CHOOSER = 9201;

    private FrameLayout root;
    private WebView web;
    private SafBridge saf;
    private DownloadBridge downloads;
    private AssetWebServer server;
    private SharedPreferences prefs;

    private ValueCallback<Uri[]> fileCallback;
    private boolean waitingForSettings = false;
    private boolean startAfterReturn = false;
    private boolean started = false;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        makeSystemBarsTransparent();
        setContentView(R.layout.activity_main);
        root = (FrameLayout) findViewById(R.id.web_root);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        if (prefs.getBoolean(KEY_DONE, false)) startWeb();
        else askWebViewProvider(true);
    }


    /** Делает системные панели прозрачными без растягивания WebView под них. */
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

    /* ── выбор движка WebView (один раз при первом запуске) ─────── */

    private void askWebViewProvider(final boolean firstRun) {
        final List<WebViewProviders.Item> items = WebViewProviders.installed(this);
        if (items.isEmpty()) {
            prefs.edit().putBoolean(KEY_DONE, true).apply();
            toast(getString(R.string.wv_none));
            startWeb();
            return;
        }

        final String current = WebViewProviders.current(this);
        final String[] titles = new String[items.size()];
        int checked = 0;
        for (int i = 0; i < items.size(); i++) {
            WebViewProviders.Item it = items.get(i);
            boolean isCur = it.pkg.equals(current);
            titles[i] = it.title() + (isCur ? getString(R.string.wv_current) : "");
            if (isCur) checked = i;
        }
        final int[] sel = new int[]{checked};

        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle(R.string.wv_title);
        b.setSingleChoiceItems(titles, checked, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                sel[0] = which;
            }
        });
        b.setPositiveButton(R.string.wv_use, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                WebViewProviders.Item chosen = items.get(sel[0]);
                prefs.edit().putBoolean(KEY_DONE, true).putString(KEY_PKG, chosen.pkg).apply();
                if (chosen.pkg.equals(current)) {
                    startWebOrReload(firstRun);
                } else {
                    explainSwitch(chosen, firstRun);
                }
            }
        });
        b.setNegativeButton(R.string.wv_cancel, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                prefs.edit().putBoolean(KEY_DONE, true).apply();
                startWebOrReload(firstRun);
            }
        });
        b.setCancelable(false);

        AlertDialog dlg = b.create();
        TextView msg = new TextView(this);
        msg.setPadding(48, 32, 48, 8);
        msg.setText(R.string.wv_msg);
        dlg.setView(msg);
        dlg.show();
    }

    private void explainSwitch(final WebViewProviders.Item chosen, final boolean firstRun) {
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle(R.string.wv_switch_title);
        b.setMessage(getString(R.string.wv_switch_msg, chosen.title()));
        b.setPositiveButton(R.string.wv_open_settings, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                waitingForSettings = true;
                startAfterReturn = !started;
                boolean opened = WebViewProviders.openPicker(MainActivity.this);
                if (!opened) {
                    startAfterReturn = false;
                    startWebOrReload(firstRun);
                }
            }
        });
        b.setNegativeButton(R.string.wv_later, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                startWebOrReload(firstRun);
            }
        });
        b.setCancelable(false);
        b.show();
    }

    private void startWebOrReload(boolean firstRun) {
        if (!started) startWeb();
        else if (web != null) web.reload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        makeSystemBarsTransparent();
        if (waitingForSettings) {
            waitingForSettings = false;
            String want = prefs.getString(KEY_PKG, null);
            String cur = WebViewProviders.current(this);
            if (want != null && cur != null && want.equals(cur)) {
                toast("Движок: " + cur);
            }
        }
        if (startAfterReturn && !started) {
            startAfterReturn = false;
            startWeb();
        }
        if (web != null) {
            web.onResume();
            try {
                web.resumeTimers();
            } catch (Throwable ignored) {
            }
        }
    }

    /* ── WebView ────────────────────────────────────────────────── */

    private void startWeb() {
        if (started) return;
        started = true;

        String token = UUID.randomUUID().toString();
        saf = new SafBridge(this, token);
        downloads = new DownloadBridge(this, token);
        server = new AssetWebServer(this, saf, token);

        try {
            web = new WebView(this);
        } catch (Throwable t) {
            showFatal(getString(R.string.wv_missing));
            return;
        }
        saf.attach(web);

        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        configure(web);
        web.addJavascriptInterface(saf, "AndroidFS");
        web.addJavascriptInterface(downloads, "AndroidDL");
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        web.setDownloadListener(new Downloads());

        if (Build.VERSION.SDK_INT >= 24) {
            try {
                SwCompat.install(server);
            } catch (Throwable ignored) {
            }
        }

        // Доступ к памяти нужен запасному способу сохранения — спрашиваем заранее,
        // чтобы «скачать» из проводника IDE работало в любом случае.
        try {
            if (!downloads.hasPermission()) downloads.requestPermission();
        } catch (Throwable ignored) {
        }

        String url = server.entryUrl();
        if (url == null) {
            showFatal(getString(R.string.no_html));
            return;
        }
        web.loadUrl(url);
    }

    private void configure(WebView w) {
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadsImagesAutomatically(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setDefaultTextEncodingName("UTF-8");
        s.setTextZoom(100);

        // Никакого зума и «резинки» — страница не должна ёрзать.
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);

        try {
            s.setAllowFileAccessFromFileURLs(false);
            s.setAllowUniversalAccessFromFileURLs(false);
        } catch (Throwable ignored) {
        }
        try {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        } catch (Throwable ignored) {
        }
        try {
            s.setCacheMode(WebSettings.LOAD_DEFAULT);
        } catch (Throwable ignored) {
        }

        w.setOverScrollMode(View.OVER_SCROLL_NEVER);
        w.setVerticalScrollBarEnabled(false);
        w.setHorizontalScrollBarEnabled(false);
        w.setScrollbarFadingEnabled(true);
        w.setBackgroundColor(0xFF0F1621);
        w.setFocusable(true);
        w.setFocusableInTouchMode(true);

        try {
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            if (Build.VERSION.SDK_INT >= 21) cm.setAcceptThirdPartyCookies(w, true);
        } catch (Throwable ignored) {
        }
        try {
            if (Build.VERSION.SDK_INT >= 19) WebView.setWebContentsDebuggingEnabled(true);
        } catch (Throwable ignored) {
        }
    }

    private final class Client extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
            WebResourceResponse r = server.handle(req);
            return r != null ? r : super.shouldInterceptRequest(view, req);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            return handleUrl(req == null ? null : req.getUrl());
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return handleUrl(url == null ? null : Uri.parse(url));
        }

        private boolean handleUrl(Uri u) {
            if (u == null) return false;
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
            if (AssetWebServer.AUTHORITY.equals(u.getAuthority())) return false;
            if (scheme.equals("about") || scheme.equals("data") || scheme.equals("blob")
                    || scheme.equals("javascript")) return false;
            try {
                Intent it = new Intent(Intent.ACTION_VIEW, u);
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(it);
                return true;
            } catch (Throwable t) {
                return false;
            }
        }
    }

    private final class Chrome extends WebChromeClient {
        @Override
        public boolean onConsoleMessage(ConsoleMessage m) {
            return true;
        }

        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            try {
                request.grant(request.getResources());
            } catch (Throwable ignored) {
            }
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb,
                                         FileChooserParams params) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = cb;
            try {
                Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                it.addCategory(Intent.CATEGORY_OPENABLE);
                it.setType("*/*");
                if (params != null && params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    it.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
                it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivityForResult(it, RQ_FILE_CHOOSER);
                return true;
            } catch (Throwable t) {
                fileCallback = null;
                return false;
            }
        }
    }

    /* ── скачивание файлов ──────────────────────────────────────── */

    /**
     * Запасной путь: скачивание, которое не перехватил fs_bridge.js
     * (например, сервер прислал Content-Disposition: attachment).
     */
    private final class Downloads implements DownloadListener {
        @Override
        public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                    String mimetype, long contentLength) {
            if (url == null || url.length() == 0) return;

            String name;
            try {
                name = URLUtil.guessFileName(url, contentDisposition, mimetype);
            } catch (Throwable t) {
                name = "file";
            }

            String low = url.toLowerCase(Locale.US);
            boolean ours = low.startsWith("blob:") || low.startsWith("data:")
                    || low.startsWith("filesystem:") || url.startsWith(AssetWebServer.ORIGIN);
            if (ours) {
                // Содержимое доступно только внутри страницы — просим её отдать файл мосту.
                String js = "window.IDEMobile&&window.IDEMobile.saveUrl("
                        + JSONObject.quote(url) + "," + JSONObject.quote(name) + ","
                        + JSONObject.quote(mimetype == null ? "" : mimetype) + ")";
                try {
                    if (Build.VERSION.SDK_INT >= 19) web.evaluateJavascript(js, null);
                    else web.loadUrl("javascript:" + js);
                } catch (Throwable ignored) {
                }
                return;
            }

            // Внешняя ссылка — отдаём системе (браузер, менеджер загрузок).
            try {
                Intent it = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(it);
                toast(getString(R.string.dl_external));
            } catch (Throwable t) {
                toast(getString(R.string.dl_failed, name));
            }
        }
    }

    /* ── результаты системных диалогов ──────────────────────────── */

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (saf != null && saf.onActivityResult(requestCode, resultCode, data)) return;
        if (downloads != null && downloads.onActivityResult(requestCode, resultCode, data)) return;

        if (requestCode == RQ_FILE_CHOOSER) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    result = new Uri[n];
                    for (int i = 0; i < n; i++) result[i] = data.getClipData().getItemAt(i).getUri();
                } else if (data.getData() != null) {
                    result = new Uri[]{data.getData()};
                }
            }
            if (fileCallback != null) {
                fileCallback.onReceiveValue(result);
                fileCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (downloads != null
                && downloads.onRequestPermissionsResult(requestCode, permissions, results)) return;
        super.onRequestPermissionsResult(requestCode, permissions, results);
    }

    /* ── меню и кнопки ──────────────────────────────────────────── */

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, R.string.wv_menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1) {
            askWebViewProvider(false);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && web != null && web.canGoBack()) {
            web.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onPause() {
        if (web != null) {
            web.onPause();
            try {
                web.pauseTimers();
            } catch (Throwable ignored) {
            }
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (saf != null) saf.shutdown();
        if (downloads != null) downloads.shutdown();
        if (web != null) {
            try {
                root.removeView(web);
                web.removeAllViews();
                web.destroy();
            } catch (Throwable ignored) {
            }
            web = null;
        }
        super.onDestroy();
    }

    /* ── мелочи ─────────────────────────────────────────────────── */

    private void showFatal(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFFE6EDF3);
        tv.setTextSize(16f);
        tv.setPadding(64, 96, 64, 64);
        root.addView(tv);
    }

    private void toast(String s) {
        try {
            Toast.makeText(this, s, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
    }
}
