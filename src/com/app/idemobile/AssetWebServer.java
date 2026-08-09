package com.app.idemobile;

import android.content.Context;
import android.content.res.AssetManager;
import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Отдаёт содержимое папки assets по адресу https://appassets.androidplatform.net/…
 *
 * Обычный file:// не годится: без «безопасного контекста» не работают
 * localStorage, IndexedDB, service worker и File System Access API.
 *
 * В каждый HTML на лету дописывается мост к файловой системе устройства
 * и правится meta viewport, чтобы страница не «ёрзала» и не масштабировалась.
 */
public class AssetWebServer {

    public static final String AUTHORITY = "appassets.androidplatform.net";
    public static final String ORIGIN = "https://" + AUTHORITY;

    private static final String VIEWPORT =
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, "
                    + "maximum-scale=1.0, minimum-scale=1.0, user-scalable=no, viewport-fit=cover\">";

    private static final String FIX_CSS =
            "<style id=\"ide-mobile-fix\">"
                    + "html{-webkit-text-size-adjust:100%;touch-action:manipulation;}"
                    + "html,body{overscroll-behavior:none;overscroll-behavior-y:none;}"
                    + "body{-webkit-tap-highlight-color:transparent;}"
                    + "</style>";

    private final Context ctx;
    private final AssetManager am;
    private final SafBridge saf;
    private final String token;

    private volatile String entry;
    private volatile String bridgeJs;

    public AssetWebServer(Context ctx, SafBridge saf, String token) {
        this.ctx = ctx.getApplicationContext();
        this.am = this.ctx.getAssets();
        this.saf = saf;
        this.token = token;
    }

    /** Точка входа: index.html, либо единственный/самый верхний html в assets. */
    public String entryPath() {
        String e = entry;
        if (e == null) {
            e = findEntry();
            entry = e;
        }
        return e;
    }

    public String entryUrl() {
        String e = entryPath();
        return e == null ? null : ORIGIN + "/" + e;
    }

    public WebResourceResponse handle(WebResourceRequest req) {
        if (req == null) return null;
        Map<String, String> h = null;
        try {
            h = req.getRequestHeaders();
        } catch (Throwable ignored) {
        }
        return handle(req.getUrl(), h);
    }

    public WebResourceResponse handle(Uri uri, Map<String, String> headers) {
        if (uri == null) return null;
        if (!AUTHORITY.equals(uri.getAuthority())) return null;

        String path = uri.getPath();
        if (path == null) path = "/";
        while (path.startsWith("/")) path = path.substring(1);
        path = Uri.decode(path);

        try {
            if (path.startsWith("__afs__")) {
                return saf == null ? notFound() : saf.serve(uri, headers);
            }
            if (path.startsWith("__bridge__/")) {
                return bridge(path.substring("__bridge__/".length()));
            }
            if (path.length() == 0) {
                String e = entryPath();
                if (e == null) return notFound();
                path = e;
            }
            return asset(path);
        } catch (Throwable t) {
            return notFound();
        }
    }

    /* ── ресурсы моста ──────────────────────────────────────────── */

    private WebResourceResponse bridge(String name) {
        if ("fs_bridge.js".equals(name)) {
            String js = bridgeJs;
            if (js == null) {
                js = readRaw(R.raw.fs_bridge);
                bridgeJs = js;
            }
            return text("application/javascript", js);
        }
        return notFound();
    }

    private String readRaw(int id) {
        InputStream in = null;
        try {
            in = ctx.getResources().openRawResource(id);
            return new String(readAll(in), "UTF-8");
        } catch (Throwable t) {
            return "";
        } finally {
            close(in);
        }
    }

    /* ── файлы из assets ────────────────────────────────────────── */

    private WebResourceResponse asset(String path) {
        InputStream in;
        try {
            in = am.open(path, AssetManager.ACCESS_STREAMING);
        } catch (Throwable t) {
            return notFound();
        }
        String mime = mimeOf(path);
        if (mime.startsWith("text/html")) {
            byte[] data;
            try {
                data = readAll(in);
            } catch (Throwable t) {
                return notFound();
            } finally {
                close(in);
            }
            String html;
            try {
                html = new String(data, "UTF-8");
            } catch (Throwable t) {
                html = new String(data);
            }
            return text(mime, inject(html));
        }
        return new WebResourceResponse(mime, encodingFor(mime), 200, "OK", corsHeaders(), in);
    }

    /** Дописывает в страницу мост файловой системы и правит viewport. */
    private String inject(String html) {
        String lower = html.toLowerCase(Locale.US);
        StringBuilder add = new StringBuilder();
        add.append("<script>window.__AFS_T=\"").append(token).append("\";</script>");
        add.append("<script src=\"/__bridge__/fs_bridge.js\"></script>");
        add.append(FIX_CSS);

        // Заменяем существующий viewport на неизменяемый (без зума и «резинки»).
        int vp = lower.indexOf("name=\"viewport\"");
        if (vp < 0) vp = lower.indexOf("name='viewport'");
        if (vp >= 0) {
            int start = html.lastIndexOf('<', vp);
            int end = html.indexOf('>', vp);
            if (start >= 0 && end > start) {
                html = html.substring(0, start) + VIEWPORT + html.substring(end + 1);
                lower = html.toLowerCase(Locale.US);
            }
        } else {
            add.insert(0, VIEWPORT);
        }

        int head = lower.indexOf("<head");
        if (head >= 0) {
            int close = html.indexOf('>', head);
            if (close > 0) {
                return html.substring(0, close + 1) + add + html.substring(close + 1);
            }
        }
        int htmlTag = lower.indexOf("<html");
        if (htmlTag >= 0) {
            int close = html.indexOf('>', htmlTag);
            if (close > 0) {
                return html.substring(0, close + 1) + "<head>" + add + "</head>" + html.substring(close + 1);
            }
        }
        return add + html;
    }

    /* ── поиск точки входа ──────────────────────────────────────── */

    private String findEntry() {
        String best = null;
        int bestScore = Integer.MAX_VALUE;
        ArrayDeque<String> queue = new ArrayDeque<String>();
        queue.add("");
        int guard = 0;

        while (!queue.isEmpty() && guard < 4000) {
            String dir = queue.poll();
            String[] names;
            try {
                names = am.list(dir);
            } catch (Throwable t) {
                continue;
            }
            if (names == null) continue;
            for (int i = 0; i < names.length; i++) {
                guard++;
                String name = names[i];
                if (name == null || name.length() == 0) continue;
                String path = dir.length() == 0 ? name : dir + "/" + name;
                if (isDir(path)) {
                    queue.add(path);
                    continue;
                }
                String low = name.toLowerCase(Locale.US);
                if (!low.endsWith(".html") && !low.endsWith(".htm")) continue;
                int depth = 0;
                for (int k = 0; k < path.length(); k++) if (path.charAt(k) == '/') depth++;
                int rank = "index.html".equals(low) || "index.htm".equals(low) ? 0
                        : ("main.html".equals(low) || "app.html".equals(low) ? 1 : 2);
                int score = depth * 10 + rank;
                if (score < bestScore) {
                    bestScore = score;
                    best = path;
                }
            }
            if (bestScore == 0) break;
        }
        return best;
    }

    private boolean isDir(String path) {
        try {
            String[] c = am.list(path);
            return c != null && c.length > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /* ── утилиты ────────────────────────────────────────────────── */

    static Map<String, String> corsHeaders() {
        HashMap<String, String> h = new HashMap<String, String>();
        h.put("Access-Control-Allow-Origin", "*");
        h.put("Cache-Control", "no-cache, no-store");
        return h;
    }

    private static WebResourceResponse text(String mime, String body) {
        byte[] data;
        try {
            data = body.getBytes("UTF-8");
        } catch (Throwable t) {
            data = body.getBytes();
        }
        return new WebResourceResponse(mime, "UTF-8", 200, "OK", corsHeaders(),
                new ByteArrayInputStream(data));
    }

    static WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found",
                corsHeaders(), new ByteArrayInputStream(new byte[0]));
    }

    static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(4096, in.available()));
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    static void close(InputStream in) {
        try {
            if (in != null) in.close();
        } catch (Throwable ignored) {
        }
    }

    static String encodingFor(String mime) {
        if (mime.startsWith("text/") || mime.contains("javascript") || mime.contains("json")
                || mime.contains("xml") || mime.contains("svg")) return "UTF-8";
        return null;
    }

    static String mimeOf(String path) {
        String p = path.toLowerCase(Locale.US);
        int dot = p.lastIndexOf('.');
        String ext = dot < 0 ? "" : p.substring(dot + 1);
        if (ext.equals("html") || ext.equals("htm")) return "text/html";
        if (ext.equals("js") || ext.equals("mjs") || ext.equals("cjs")) return "application/javascript";
        if (ext.equals("css")) return "text/css";
        if (ext.equals("json") || ext.equals("map")) return "application/json";
        if (ext.equals("svg")) return "image/svg+xml";
        if (ext.equals("png")) return "image/png";
        if (ext.equals("jpg") || ext.equals("jpeg")) return "image/jpeg";
        if (ext.equals("gif")) return "image/gif";
        if (ext.equals("webp")) return "image/webp";
        if (ext.equals("avif")) return "image/avif";
        if (ext.equals("ico")) return "image/x-icon";
        if (ext.equals("bmp")) return "image/bmp";
        if (ext.equals("woff")) return "font/woff";
        if (ext.equals("woff2")) return "font/woff2";
        if (ext.equals("ttf")) return "font/ttf";
        if (ext.equals("otf")) return "font/otf";
        if (ext.equals("eot")) return "application/vnd.ms-fontobject";
        if (ext.equals("wasm")) return "application/wasm";
        if (ext.equals("txt") || ext.equals("md") || ext.equals("log")) return "text/plain";
        if (ext.equals("xml")) return "text/xml";
        if (ext.equals("csv")) return "text/csv";
        if (ext.equals("mp3")) return "audio/mpeg";
        if (ext.equals("wav")) return "audio/wav";
        if (ext.equals("ogg")) return "audio/ogg";
        if (ext.equals("mp4") || ext.equals("m4v")) return "video/mp4";
        if (ext.equals("webm")) return "video/webm";
        if (ext.equals("pdf")) return "application/pdf";
        if (ext.equals("zip")) return "application/zip";
        return "application/octet-stream";
    }
}
