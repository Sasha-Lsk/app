package com.app.idemobile;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.FilterInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Мост между JavaScript и файловой системой устройства (Storage Access Framework).
 * Поверх него в fs_bridge.js собран File System Access API —
 * тот самый, через который «хромовые» браузеры открывают папки
 * и синхронно работают с файлами на диске.
 */
public class SafBridge {

    static final int RQ_DIR = 9101;
    static final int RQ_OPEN = 9102;
    static final int RQ_CREATE = 9103;

    private static final Object PENDING = new Object();
    private static final String DIR_MIME = DocumentsContract.Document.MIME_TYPE_DIR;

    private final Activity act;
    private final String token;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final AtomicInteger seq = new AtomicInteger(1);

    private final Map<Integer, String> waiting = new HashMap<Integer, String>();
    private final Map<String, OutputStream> writers = new HashMap<String, OutputStream>();

    private WebView web;

    public SafBridge(Activity act, String token) {
        this.act = act;
        this.token = token;
    }

    public void attach(WebView w) {
        this.web = w;
    }

    public void shutdown() {
        try {
            pool.shutdownNow();
        } catch (Throwable ignored) {
        }
        synchronized (writers) {
            for (OutputStream o : writers.values()) closeQuietly(o);
            writers.clear();
        }
    }

    /* ── точка входа из JavaScript ──────────────────────────────── */

    @JavascriptInterface
    public void call(final String t, final String reqId, final String method, final String args) {
        if (t == null || !t.equals(token)) return;
        pool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject a = args == null || args.length() == 0
                            ? new JSONObject() : new JSONObject(args);
                    Object v = dispatch(reqId, method, a);
                    if (v != PENDING) resolve(reqId, v);
                } catch (AfsError e) {
                    reject(reqId, e.name, e.getMessage());
                } catch (Throwable t2) {
                    reject(reqId, "InvalidStateError", String.valueOf(t2));
                }
            }
        });
    }

    /** Открыть системный экран выбора движка WebView. */
    @JavascriptInterface
    public void openWebViewSettings(String t) {
        if (t == null || !t.equals(token)) return;
        ui.post(new Runnable() {
            @Override
            public void run() {
                WebViewProviders.openPicker(act);
            }
        });
    }

    /* ── диспетчер ──────────────────────────────────────────────── */

    private Object dispatch(String reqId, String m, JSONObject a) throws Exception {
        if ("pickDirectory".equals(m)) {
            Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            startPicker(reqId, RQ_DIR, it);
            return PENDING;
        }
        if ("pickFiles".equals(m)) {
            Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            it.addCategory(Intent.CATEGORY_OPENABLE);
            it.setType("*/*");
            it.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, a.optBoolean("multiple", false));
            it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startPicker(reqId, RQ_OPEN, it);
            return PENDING;
        }
        if ("saveFile".equals(m)) {
            String name = a.optString("suggestedName", "untitled.txt");
            Intent it = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            it.addCategory(Intent.CATEGORY_OPENABLE);
            it.setType(mimeForName(name));
            it.putExtra(Intent.EXTRA_TITLE, name);
            it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startPicker(reqId, RQ_CREATE, it);
            return PENDING;
        }
        if ("list".equals(m)) return list(a.getString("id"));
        if ("child".equals(m)) return child(a.getString("id"), a.getString("name"),
                a.optString("kind", "file"), a.optBoolean("create", false));
        if ("meta".equals(m)) return meta(Uri.parse(a.getString("id")), true);
        if ("read".equals(m)) return read(a.getString("id"), a.optLong("offset", 0),
                a.optInt("length", -1));
        if ("writeAll".equals(m)) return writeAll(a.getString("id"), a.getString("b64"));
        if ("writeOpen".equals(m)) return writeOpen(a.getString("id"));
        if ("writeChunk".equals(m)) return writeChunk(a.getString("sid"), a.getString("b64"));
        if ("writeClose".equals(m)) return writeClose(a.getString("sid"), false);
        if ("writeAbort".equals(m)) return writeClose(a.getString("sid"), true);
        if ("remove".equals(m)) return remove(a.getString("id"), a.getString("name"),
                a.optBoolean("recursive", false));
        if ("permission".equals(m)) return permission(a.getString("id"));
        throw new AfsError("NotSupportedError", "Неизвестный метод: " + m);
    }

    /* ── выбор папки/файла ──────────────────────────────────────── */

    private void startPicker(final String reqId, final int code, final Intent it) {
        synchronized (waiting) {
            String old = waiting.put(code, reqId);
            if (old != null) reject(old, "AbortError", "Заменён новым запросом");
        }
        ui.post(new Runnable() {
            @Override
            public void run() {
                try {
                    act.startActivityForResult(it, code);
                } catch (Throwable t) {
                    String id;
                    synchronized (waiting) {
                        id = waiting.remove(code);
                    }
                    if (id != null) reject(id, "AbortError", "Системный выбор файлов недоступен");
                }
            }
        });
    }

    /** Вызывается из MainActivity. true — событие обработано мостом. */
    public boolean onActivityResult(int code, int result, Intent data) {
        String reqId;
        synchronized (waiting) {
            reqId = waiting.remove(code);
        }
        if (reqId == null) return code == RQ_DIR || code == RQ_OPEN || code == RQ_CREATE;

        if (result != Activity.RESULT_OK || data == null) {
            reject(reqId, "AbortError", "Отменено пользователем");
            return true;
        }
        try {
            ContentResolver cr = act.getContentResolver();
            if (code == RQ_DIR) {
                Uri tree = data.getData();
                if (tree == null) {
                    reject(reqId, "AbortError", "Папка не выбрана");
                    return true;
                }
                persist(cr, tree, true);
                Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree,
                        DocumentsContract.getTreeDocumentId(tree));
                resolve(reqId, meta(doc, false));
            } else if (code == RQ_CREATE) {
                Uri u = data.getData();
                if (u == null) {
                    reject(reqId, "AbortError", "Файл не выбран");
                    return true;
                }
                persist(cr, u, true);
                resolve(reqId, meta(u, false));
            } else {
                JSONArray arr = new JSONArray();
                if (data.getClipData() != null) {
                    for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                        Uri u = data.getClipData().getItemAt(i).getUri();
                        if (u == null) continue;
                        persist(cr, u, false);
                        arr.put(meta(u, false));
                    }
                } else if (data.getData() != null) {
                    persist(cr, data.getData(), false);
                    arr.put(meta(data.getData(), false));
                }
                resolve(reqId, arr);
            }
        } catch (Throwable t) {
            reject(reqId, "InvalidStateError", String.valueOf(t));
        }
        return true;
    }

    private void persist(ContentResolver cr, Uri uri, boolean write) {
        try {
            int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
            if (write) flags |= Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            cr.takePersistableUriPermission(uri, flags);
        } catch (Throwable ignored) {
        }
    }

    /* ── операции с документами ─────────────────────────────────── */

    private JSONObject meta(Uri doc, boolean strict) throws Exception {
        Cursor c = null;
        try {
            c = act.getContentResolver().query(doc, new String[]{
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED
            }, null, null, null);
            if (c != null && c.moveToFirst()) {
                String name = c.isNull(0) ? nameFromUri(doc) : c.getString(0);
                String mime = c.isNull(1) ? "" : c.getString(1);
                long size = c.isNull(2) ? 0 : c.getLong(2);
                long mtime = c.isNull(3) ? 0 : c.getLong(3);
                return handle(doc, name, mime, size, mtime);
            }
        } catch (Throwable t) {
            if (strict) throw new AfsError("NotFoundError", "Нет доступа к файлу");
        } finally {
            closeQuietly(c);
        }
        if (strict) throw new AfsError("NotFoundError", "Файл не найден");
        return handle(doc, nameFromUri(doc), "", 0, 0);
    }

    private static JSONObject handle(Uri doc, String name, String mime, long size, long mtime)
            throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", doc.toString());
        o.put("name", name == null ? "" : name);
        o.put("kind", DIR_MIME.equals(mime) ? "directory" : "file");
        o.put("mime", mime == null ? "" : mime);
        o.put("size", size);
        o.put("mtime", mtime);
        return o;
    }

    private JSONArray list(String id) throws Exception {
        Uri parent = Uri.parse(id);
        Uri children;
        try {
            children = DocumentsContract.buildChildDocumentsUriUsingTree(parent,
                    DocumentsContract.getDocumentId(parent));
        } catch (Throwable t) {
            throw new AfsError("NotFoundError", "Это не папка устройства");
        }
        JSONArray out = new JSONArray();
        Cursor c = null;
        try {
            c = act.getContentResolver().query(children, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED
            }, null, null, null);
            if (c == null) throw new AfsError("NotFoundError", "Папка недоступна");
            while (c.moveToNext()) {
                String docId = c.getString(0);
                if (docId == null) continue;
                Uri u = DocumentsContract.buildDocumentUriUsingTree(parent, docId);
                out.put(handle(u,
                        c.isNull(1) ? docId : c.getString(1),
                        c.isNull(2) ? "" : c.getString(2),
                        c.isNull(3) ? 0 : c.getLong(3),
                        c.isNull(4) ? 0 : c.getLong(4)));
            }
        } finally {
            closeQuietly(c);
        }
        return out;
    }

    private JSONObject child(String dirId, String name, String kind, boolean create)
            throws Exception {
        JSONArray kids = list(dirId);
        for (int i = 0; i < kids.length(); i++) {
            JSONObject o = kids.getJSONObject(i);
            if (!name.equals(o.optString("name"))) continue;
            if (!kind.equals(o.optString("kind"))) {
                throw new AfsError("TypeMismatchError",
                        "«" + name + "» уже существует с другим типом");
            }
            return o;
        }
        if (!create) throw new AfsError("NotFoundError", "Нет такого элемента: " + name);

        Uri parent = Uri.parse(dirId);
        String mime = "directory".equals(kind) ? DIR_MIME : mimeForName(name);
        Uri created;
        try {
            created = DocumentsContract.createDocument(act.getContentResolver(), parent, mime, name);
        } catch (Throwable t) {
            throw new AfsError("NoModificationAllowedError", "Не удалось создать: " + name);
        }
        if (created == null) throw new AfsError("NoModificationAllowedError", "Не удалось создать: " + name);

        JSONObject res = meta(created, false);
        // Некоторые провайдеры дописывают расширение — возвращаем имя как просили.
        if (!name.equals(res.optString("name"))) {
            try {
                Uri renamed = DocumentsContract.renameDocument(act.getContentResolver(), created, name);
                if (renamed != null) res = meta(renamed, false);
                else res = meta(created, false);
            } catch (Throwable ignored) {
            }
        }
        return res;
    }

    private boolean remove(String dirId, String name, boolean recursive) throws Exception {
        JSONObject target = null;
        JSONArray kids = list(dirId);
        for (int i = 0; i < kids.length(); i++) {
            JSONObject o = kids.getJSONObject(i);
            if (name.equals(o.optString("name"))) {
                target = o;
                break;
            }
        }
        if (target == null) throw new AfsError("NotFoundError", "Нет такого элемента: " + name);
        if ("directory".equals(target.optString("kind")) && !recursive) {
            JSONArray inner = list(target.getString("id"));
            if (inner.length() > 0) {
                throw new AfsError("InvalidModificationError", "Папка не пуста: " + name);
            }
        }
        boolean ok;
        try {
            ok = DocumentsContract.deleteDocument(act.getContentResolver(),
                    Uri.parse(target.getString("id")));
        } catch (Throwable t) {
            ok = false;
        }
        if (!ok) throw new AfsError("NoModificationAllowedError", "Не удалось удалить: " + name);
        return true;
    }

    private String permission(String id) {
        Uri u = Uri.parse(id);
        try {
            List<android.content.UriPermission> ps =
                    act.getContentResolver().getPersistedUriPermissions();
            String s = u.toString();
            for (int i = 0; i < ps.size(); i++) {
                android.content.UriPermission p = ps.get(i);
                if (s.startsWith(p.getUri().toString()) && p.isWritePermission()) return "granted";
            }
        } catch (Throwable ignored) {
        }
        Cursor c = null;
        try {
            c = act.getContentResolver().query(u,
                    new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID}, null, null, null);
            if (c != null && c.moveToFirst()) return "granted";
        } catch (Throwable ignored) {
        } finally {
            closeQuietly(c);
        }
        return "prompt";
    }

    /* ── чтение / запись ────────────────────────────────────────── */

    private String read(String id, long offset, int length) throws Exception {
        InputStream in = null;
        try {
            in = act.getContentResolver().openInputStream(Uri.parse(id));
            if (in == null) throw new AfsError("NotFoundError", "Файл не открывается");
            skip(in, offset);
            byte[] data;
            if (length < 0) {
                data = AssetWebServer.readAll(in);
            } else {
                data = new byte[length];
                int got = 0;
                while (got < length) {
                    int n = in.read(data, got, length - got);
                    if (n <= 0) break;
                    got += n;
                }
                if (got != length) {
                    byte[] cut = new byte[got];
                    System.arraycopy(data, 0, cut, 0, got);
                    data = cut;
                }
            }
            return Base64.encodeToString(data, Base64.NO_WRAP);
        } finally {
            AssetWebServer.close(in);
        }
    }

    private boolean writeAll(String id, String b64) throws Exception {
        OutputStream out = null;
        try {
            out = open(id, "wt");
            out.write(Base64.decode(b64, Base64.DEFAULT));
            out.flush();
        } finally {
            closeQuietly(out);
        }
        return true;
    }

    private String writeOpen(String id) throws Exception {
        OutputStream out = open(id, "wt");
        String sid = "w" + seq.getAndIncrement();
        synchronized (writers) {
            writers.put(sid, out);
        }
        return sid;
    }

    private boolean writeChunk(String sid, String b64) throws Exception {
        OutputStream out;
        synchronized (writers) {
            out = writers.get(sid);
        }
        if (out == null) throw new AfsError("InvalidStateError", "Поток записи закрыт");
        out.write(Base64.decode(b64, Base64.DEFAULT));
        return true;
    }

    private boolean writeClose(String sid, boolean abort) {
        OutputStream out;
        synchronized (writers) {
            out = writers.remove(sid);
        }
        if (out == null) return true;
        try {
            if (!abort) out.flush();
        } catch (Throwable ignored) {
        }
        closeQuietly(out);
        return true;
    }

    private OutputStream open(String id, String mode) throws Exception {
        ContentResolver cr = act.getContentResolver();
        Uri u = Uri.parse(id);
        OutputStream out = null;
        try {
            out = cr.openOutputStream(u, mode);
        } catch (Throwable t) {
            out = null;
        }
        if (out == null) {
            try {
                out = cr.openOutputStream(u, "w");
            } catch (Throwable t) {
                out = null;
            }
        }
        if (out == null) throw new AfsError("NoModificationAllowedError", "Файл недоступен для записи");
        return out;
    }

    /* ── отдача содержимого файла в WebView по https ────────────── */

    public WebResourceResponse serve(Uri url, Map<String, String> headers) {
        String id = url.getQueryParameter("u");
        if (id == null) return AssetWebServer.notFound();
        Uri doc = Uri.parse(id);
        JSONObject m;
        try {
            m = meta(doc, true);
        } catch (Throwable t) {
            return AssetWebServer.notFound();
        }
        long size = m.optLong("size", 0);
        String name = m.optString("name", "file");
        String mime = m.optString("mime", "");
        if (mime == null || mime.length() == 0 || DIR_MIME.equals(mime)
                || "application/octet-stream".equals(mime)) {
            mime = AssetWebServer.mimeOf(name);
        }

        long start = 0, end = size > 0 ? size - 1 : -1;
        boolean partial = false;
        String range = headers == null ? null : header(headers, "Range");
        if (range != null && range.startsWith("bytes=")) {
            try {
                String r = range.substring(6).trim();
                int dash = r.indexOf('-');
                String a = dash < 0 ? r : r.substring(0, dash);
                String b = dash < 0 ? "" : r.substring(dash + 1);
                if (a.length() > 0) {
                    start = Long.parseLong(a.trim());
                    if (b.length() > 0) end = Long.parseLong(b.trim());
                } else if (b.length() > 0 && size > 0) {
                    start = Math.max(0, size - Long.parseLong(b.trim()));
                }
                partial = size > 0;
            } catch (Throwable ignored) {
                partial = false;
                start = 0;
            }
        }
        if (size > 0 && end >= size) end = size - 1;

        InputStream in;
        try {
            in = act.getContentResolver().openInputStream(doc);
            if (in == null) return AssetWebServer.notFound();
            if (start > 0) skip(in, start);
        } catch (Throwable t) {
            return AssetWebServer.notFound();
        }

        Map<String, String> h = AssetWebServer.corsHeaders();
        h.put("Accept-Ranges", "bytes");
        h.put("Content-Type", mime);
        long len = size > 0 ? (end - start + 1) : -1;
        if (len >= 0) h.put("Content-Length", String.valueOf(len));
        if (partial) {
            h.put("Content-Range", "bytes " + start + "-" + end + "/" + size);
            return new WebResourceResponse(mime, AssetWebServer.encodingFor(mime), 206,
                    "Partial Content", h, len >= 0 ? new Limited(in, len) : in);
        }
        return new WebResourceResponse(mime, AssetWebServer.encodingFor(mime), 200, "OK", h, in);
    }

    private static String header(Map<String, String> h, String key) {
        String v = h.get(key);
        if (v != null) return v;
        for (Map.Entry<String, String> e : h.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(key)) return e.getValue();
        }
        return null;
    }

    private static final class Limited extends FilterInputStream {
        private long left;

        Limited(InputStream in, long len) {
            super(in);
            this.left = len;
        }

        @Override
        public int read() throws java.io.IOException {
            if (left <= 0) return -1;
            int b = super.read();
            if (b >= 0) left--;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws java.io.IOException {
            if (left <= 0) return -1;
            if (len > left) len = (int) left;
            int n = super.read(b, off, len);
            if (n > 0) left -= n;
            return n;
        }

        @Override
        public int available() throws java.io.IOException {
            long a = Math.min(left, super.available());
            return (int) Math.max(0, a);
        }
    }

    /* ── ответы в JavaScript ────────────────────────────────────── */

    private void resolve(String reqId, Object value) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", true);
            o.put("v", value == null ? JSONObject.NULL : value);
        } catch (Throwable ignored) {
        }
        post(reqId, o.toString());
    }

    private void reject(String reqId, String name, String msg) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false);
            o.put("e", name);
            o.put("m", msg == null ? "" : msg);
        } catch (Throwable ignored) {
        }
        post(reqId, o.toString());
    }

    private void post(final String reqId, final String json) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                WebView w = web;
                if (w == null) return;
                String js = "window.__AFS&&window.__AFS.settle(" + JSONObject.quote(reqId)
                        + "," + JSONObject.quote(json) + ")";
                try {
                    if (Build.VERSION.SDK_INT >= 19) w.evaluateJavascript(js, null);
                    else w.loadUrl("javascript:" + js);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    /* ── мелочи ─────────────────────────────────────────────────── */

    static String mimeForName(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.US);
        int dot = n.lastIndexOf('.');
        if (dot > 0 && dot < n.length() - 1) {
            String ext = n.substring(dot + 1);
            try {
                String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                if (m != null && m.length() > 0) return m;
            } catch (Throwable ignored) {
            }
        }
        return "application/octet-stream";
    }

    private static String nameFromUri(Uri u) {
        String s = u.getLastPathSegment();
        if (s == null) return "root";
        int i = s.lastIndexOf('/');
        if (i >= 0 && i < s.length() - 1) s = s.substring(i + 1);
        int j = s.lastIndexOf(':');
        if (j >= 0 && j < s.length() - 1) s = s.substring(j + 1);
        return s.length() == 0 ? "root" : s;
    }

    private static void skip(InputStream in, long n) throws Exception {
        long left = n;
        byte[] trash = null;
        while (left > 0) {
            long s = in.skip(left);
            if (s > 0) {
                left -= s;
                continue;
            }
            if (trash == null) trash = new byte[8192];
            int r = in.read(trash, 0, (int) Math.min(trash.length, left));
            if (r <= 0) break;
            left -= r;
        }
    }

    private static void closeQuietly(Object c) {
        try {
            if (c instanceof Cursor) ((Cursor) c).close();
            else if (c instanceof OutputStream) ((OutputStream) c).close();
        } catch (Throwable ignored) {
        }
    }

    /** Ошибка с именем в стиле DOMException. */
    static final class AfsError extends Exception {
        final String name;

        AfsError(String name, String msg) {
            super(msg);
            this.name = name;
        }
    }
}
