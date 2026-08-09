package com.app.idemobile;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Скачивание файлов из веб-IDE в память устройства.
 *
 * Страница отдаёт содержимое файла по частям (base64) — оно копится во временном
 * файле кэша. Когда файл собран, открывается системный диалог Android
 * «Сохранить как» (ACTION_CREATE_DOCUMENT): пользователь сам выбирает папку и имя,
 * никаких разрешений для этого не нужно. Если системного диалога на устройстве нет,
 * файл пишется прямо в «Загрузки» — тогда нужен доступ к памяти,
 * он запрашивается на месте.
 */
public class DownloadBridge {

    static final int RQ_SAVE = 9301;
    static final int RQ_PERM = 9302;

    private static final String PERM = Manifest.permission.WRITE_EXTERNAL_STORAGE;

    private final Activity act;
    private final String token;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final AtomicInteger seq = new AtomicInteger(1);

    private final Map<String, Job> jobs = new HashMap<String, Job>();
    private final ArrayDeque<Job> queue = new ArrayDeque<Job>();

    /** Задание, для которого сейчас открыт системный диалог. */
    private Job asking;
    /** Задание, ждущее разрешения на запись в память. */
    private Job waitingPerm;

    public DownloadBridge(Activity act, String token) {
        this.act = act;
        this.token = token;
    }

    public void shutdown() {
        try {
            pool.shutdownNow();
        } catch (Throwable ignored) {
        }
        synchronized (jobs) {
            for (Job j : jobs.values()) j.discard();
            jobs.clear();
        }
        synchronized (queue) {
            for (Job j : queue) j.discard();
            queue.clear();
        }
    }

    /* ── вызовы из JavaScript ───────────────────────────────────── */

    /** Начать сохранение. Возвращает id задания или "" при ошибке. */
    @JavascriptInterface
    public String begin(String t, String name, String mime) {
        if (t == null || !t.equals(token)) return "";
        try {
            Job j = new Job(safeName(name), mime, tempFile());
            synchronized (jobs) {
                jobs.put(j.id, j);
            }
            return j.id;
        } catch (Throwable e) {
            return "";
        }
    }

    /** Дописать очередную порцию (base64). */
    @JavascriptInterface
    public boolean chunk(String t, String id, String b64) {
        if (t == null || !t.equals(token)) return false;
        Job j = job(id);
        if (j == null) return false;
        try {
            if (b64 != null && b64.length() > 0) {
                j.out.write(Base64.decode(b64, Base64.DEFAULT));
            }
            return true;
        } catch (Throwable e) {
            drop(id);
            return false;
        }
    }

    /** Файл собран — спросить, куда его положить. */
    @JavascriptInterface
    public void finish(String t, String id) {
        if (t == null || !t.equals(token)) return;
        final Job j = job(id);
        if (j == null) return;
        synchronized (jobs) {
            jobs.remove(id);
        }
        if (!j.seal()) {
            toast(act.getString(R.string.dl_failed, j.name));
            j.discard();
            return;
        }
        ui.post(new Runnable() {
            @Override
            public void run() {
                enqueue(j);
            }
        });
    }

    /** Отменить сохранение и убрать временный файл. */
    @JavascriptInterface
    public void cancel(String t, String id) {
        if (t == null || !t.equals(token)) return;
        drop(id);
    }

    /** Сообщение странице показать пользователю (ошибки скачивания). */
    @JavascriptInterface
    public void note(String t, String msg) {
        if (t == null || !t.equals(token)) return;
        if (msg == null || msg.length() == 0) return;
        toast(msg);
    }

    /* ── системный диалог «Сохранить как» ───────────────────────── */

    private void enqueue(Job j) {
        if (asking != null) {
            synchronized (queue) {
                queue.add(j);
            }
            return;
        }
        ask(j);
    }

    private void ask(Job j) {
        asking = j;
        Intent it = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        it.addCategory(Intent.CATEGORY_OPENABLE);
        it.setType(j.mimeOrGuess());
        it.putExtra(Intent.EXTRA_TITLE, j.name);
        it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            act.startActivityForResult(it, RQ_SAVE);
        } catch (Throwable t) {
            asking = null;
            toast(act.getString(R.string.dl_no_picker));
            toDownloads(j);
        }
    }

    /** Вызывается из MainActivity. true — событие обработано мостом. */
    public boolean onActivityResult(int code, int result, Intent data) {
        if (code != RQ_SAVE) return false;
        Job j = asking;
        asking = null;
        if (j == null) {
            next();
            return true;
        }
        Uri target = null;
        if (result == Activity.RESULT_OK && data != null) target = data.getData();
        if (target == null) {
            toast(act.getString(R.string.dl_canceled));
            j.discard();
            next();
            return true;
        }
        copyTo(j, target);
        next();
        return true;
    }

    private void next() {
        Job n;
        synchronized (queue) {
            n = queue.poll();
        }
        if (n != null) ask(n);
    }

    private void copyTo(final Job j, final Uri target) {
        pool.execute(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                InputStream in = null;
                OutputStream out = null;
                try {
                    ContentResolver cr = act.getContentResolver();
                    out = cr.openOutputStream(target);
                    if (out != null) {
                        in = new FileInputStream(j.file);
                        pipe(in, out);
                        ok = true;
                    }
                } catch (Throwable ignored) {
                } finally {
                    closeQuietly(in);
                    closeQuietly(out);
                }
                j.discard();
                toast(ok ? act.getString(R.string.dl_saved, j.name)
                        : act.getString(R.string.dl_failed, j.name));
            }
        });
    }

    /* ── запасной путь: папка «Загрузки» ────────────────────────── */

    private void toDownloads(Job j) {
        if (Build.VERSION.SDK_INT >= 23 && !hasPermission()) {
            waitingPerm = j;
            requestPermission();
            return;
        }
        writeToDownloads(j);
    }

    private void writeToDownloads(final Job j) {
        pool.execute(new Runnable() {
            @Override
            public void run() {
                File saved = null;
                InputStream in = null;
                OutputStream out = null;
                try {
                    File dir = Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS);
                    if (dir != null && !dir.exists()) dir.mkdirs();
                    File f = unique(dir, j.name);
                    in = new FileInputStream(j.file);
                    out = new FileOutputStream(f);
                    pipe(in, out);
                    saved = f;
                } catch (Throwable ignored) {
                } finally {
                    closeQuietly(in);
                    closeQuietly(out);
                }
                j.discard();
                if (saved == null) {
                    toast(act.getString(R.string.dl_failed, j.name));
                    return;
                }
                scan(saved);
                toast(act.getString(R.string.dl_saved_to, saved.getAbsolutePath()));
            }
        });
    }

    public boolean hasPermission() {
        if (Build.VERSION.SDK_INT < 23) return true;
        try {
            return act.checkSelfPermission(PERM) == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return true;
        }
    }

    /** Спросить доступ к памяти (нужен только запасному пути записи). */
    public void requestPermission() {
        if (Build.VERSION.SDK_INT < 23 || hasPermission()) return;
        try {
            act.requestPermissions(new String[]{PERM,
                    Manifest.permission.READ_EXTERNAL_STORAGE}, RQ_PERM);
        } catch (Throwable ignored) {
        }
    }

    /** Вызывается из MainActivity. true — событие обработано мостом. */
    public boolean onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code != RQ_PERM) return false;
        Job j = waitingPerm;
        waitingPerm = null;
        if (j == null) return true;
        if (hasPermission()) {
            writeToDownloads(j);
        } else {
            toast(act.getString(R.string.dl_need_perm));
            j.discard();
        }
        return true;
    }

    /* ── утилиты ────────────────────────────────────────────────── */

    private Job job(String id) {
        if (id == null) return null;
        synchronized (jobs) {
            return jobs.get(id);
        }
    }

    private void drop(String id) {
        Job j;
        synchronized (jobs) {
            j = jobs.remove(id);
        }
        if (j != null) j.discard();
    }

    private File tempFile() throws Exception {
        File dir = new File(act.getCacheDir(), "downloads");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "dl" + System.currentTimeMillis() + "_"
                + seq.getAndIncrement() + ".part");
    }

    private static File unique(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        String base = name, ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        for (int i = 1; i < 1000; i++) {
            File c = new File(dir, base + " (" + i + ")" + ext);
            if (!c.exists()) return c;
        }
        return new File(dir, base + "_" + System.currentTimeMillis() + ext);
    }

    /** Убирает из имени всё, что не годится для файловой системы. */
    static String safeName(String name) {
        String n = name == null ? "" : name.trim();
        int slash = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        if (slash >= 0) n = n.substring(slash + 1);
        n = n.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_");
        if (n.length() == 0 || ".".equals(n) || "..".equals(n)) n = "file";
        if (n.length() > 120) {
            String ext = "";
            int dot = n.lastIndexOf('.');
            if (dot > 0 && n.length() - dot <= 12) ext = n.substring(dot);
            n = n.substring(0, 120 - ext.length()) + ext;
        }
        return n;
    }

    private void scan(File f) {
        try {
            Intent it = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
            it.setData(Uri.fromFile(f));
            act.sendBroadcast(it);
        } catch (Throwable ignored) {
        }
    }

    private static void pipe(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[32768];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.flush();
    }

    private static void closeQuietly(Object c) {
        try {
            if (c instanceof InputStream) ((InputStream) c).close();
            else if (c instanceof OutputStream) ((OutputStream) c).close();
        } catch (Throwable ignored) {
        }
    }

    private void toast(final String text) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Toast.makeText(act, text, Toast.LENGTH_LONG).show();
                } catch (Throwable ignored) {
                }
            }
        });
    }

    /* ── одно скачивание ────────────────────────────────────────── */

    private final class Job {
        final String id;
        final String name;
        final String mime;
        final File file;
        OutputStream out;

        Job(String name, String mime, File file) throws Exception {
            this.id = "d" + seq.getAndIncrement();
            this.name = name;
            this.mime = mime;
            this.file = file;
            this.out = new FileOutputStream(file);
        }

        String mimeOrGuess() {
            if (mime != null && mime.length() > 0
                    && !"application/octet-stream".equals(mime.toLowerCase(Locale.US))) {
                int semi = mime.indexOf(';');
                return semi > 0 ? mime.substring(0, semi).trim() : mime;
            }
            return SafBridge.mimeForName(name);
        }

        /** Закрыть временный файл. false — записать не удалось. */
        boolean seal() {
            try {
                if (out != null) {
                    out.flush();
                    out.close();
                }
                out = null;
                return file.exists();
            } catch (Throwable t) {
                out = null;
                return false;
            }
        }

        void discard() {
            try {
                if (out != null) out.close();
            } catch (Throwable ignored) {
            }
            out = null;
            try {
                if (file != null && file.exists()) file.delete();
            } catch (Throwable ignored) {
            }
        }
    }
}
