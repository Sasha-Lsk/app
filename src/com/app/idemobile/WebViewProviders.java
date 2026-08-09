package com.app.idemobile;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.webkit.WebView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Поиск установленных на устройстве реализаций WebView
 * (системный WebView, WebView Dev/Beta/Canary, Chrome и т. п.).
 */
public final class WebViewProviders {

    /** Метка, которой Android помечает пакет, умеющий быть движком WebView. */
    private static final String WEBVIEW_META = "com.android.webview.WebViewLibrary";

    /** Запасной список — на случай, если мета-данные недоступны. */
    private static final String[] KNOWN = {
            "com.google.android.webview",
            "com.google.android.webview.beta",
            "com.google.android.webview.dev",
            "com.google.android.webview.canary",
            "com.android.webview",
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "com.chrome.canary"
    };

    public static final class Item {
        public final String pkg;
        public final String label;
        public final String version;

        Item(String pkg, String label, String version) {
            this.pkg = pkg;
            this.label = label;
            this.version = version;
        }

        public String title() {
            return version == null || version.length() == 0 ? label : label + "  (" + version + ")";
        }
    }

    private WebViewProviders() {
    }

    /** Все пакеты устройства, которые могут работать движком WebView. */
    public static List<Item> installed(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        ArrayList<Item> out = new ArrayList<Item>();
        ArrayList<String> seen = new ArrayList<String>();

        List<PackageInfo> all = null;
        try {
            all = pm.getInstalledPackages(PackageManager.GET_META_DATA);
        } catch (Throwable t) {
            all = null;
        }
        if (all != null) {
            for (int i = 0; i < all.size(); i++) {
                PackageInfo pi = all.get(i);
                ApplicationInfo ai = pi.applicationInfo;
                if (ai == null || ai.metaData == null) continue;
                if (!ai.metaData.containsKey(WEBVIEW_META)) continue;
                if (seen.contains(pi.packageName)) continue;
                seen.add(pi.packageName);
                out.add(new Item(pi.packageName, label(pm, ai, pi.packageName), pi.versionName));
            }
        }

        for (int i = 0; i < KNOWN.length; i++) {
            String p = KNOWN[i];
            if (seen.contains(p)) continue;
            try {
                PackageInfo pi = pm.getPackageInfo(p, 0);
                seen.add(p);
                out.add(new Item(p, label(pm, pi.applicationInfo, p), pi.versionName));
            } catch (Throwable ignored) {
            }
        }

        Collections.sort(out, new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        return out;
    }

    private static String label(PackageManager pm, ApplicationInfo ai, String fallback) {
        try {
            CharSequence cs = ai == null ? null : pm.getApplicationLabel(ai);
            if (cs != null && cs.length() > 0) return cs.toString();
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    /** Пакет, который сейчас реально обслуживает WebView, либо null. */
    public static String current(Context ctx) {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                PackageInfo pi = WebView.getCurrentWebViewPackage();
                if (pi != null) return pi.packageName;
            } catch (Throwable ignored) {
            }
        }
        try {
            String s = Settings.Global.getString(ctx.getContentResolver(), "webview_provider");
            if (s != null && s.length() > 0) return s;
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * Открывает системный экран выбора движка WebView.
     * Приложение не может переключить движок само — это делает система.
     */
    public static boolean openPicker(Context ctx) {
        Intent[] tries = new Intent[]{
                new Intent().setClassName("com.android.settings",
                        "com.android.settings.Settings$WebViewImplementationActivity"),
                new Intent("android.settings.WEBVIEW_IMPLEMENTATION"),
                new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        };
        for (int i = 0; i < tries.length; i++) {
            try {
                Intent it = tries[i];
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(it);
                return true;
            } catch (Throwable ignored) {
            }
        }
        try {
            Intent it = new Intent(Settings.ACTION_SETTINGS);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(it);
            return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** Открывает страницу пакета в Play Market (если движок не установлен). */
    public static void openInStore(Context ctx, String pkg) {
        try {
            Intent it = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg));
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(it);
        } catch (Throwable ignored) {
        }
    }
}
