package com.canon.backup;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Intent;
import android.graphics.Color;
import android.view.View;
import android.view.WindowInsets;
import android.content.ContentValues;
import android.database.Cursor;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.ActivityManager;
import android.content.Context;
import android.provider.Settings;
import java.util.ArrayList;
import java.util.List;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.BufferedReader;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONObject;

/**
 * 佳能备份 - 手机直连相机版
 * 相机 CCAPI 无 CORS 设置，浏览器网页无法跨域直连；
 * 本 App 用原生网络层（HttpURLConnection）请求相机，完全不受浏览器 CORS 限制，
 * 因此手机连上相机 Wi-Fi（相机热点或同一路由器）后即可直接浏览并备份照片/视频。
 */
public class MainActivity extends Activity {

    private static final int REQ_STORAGE = 100;

    private WebView webView;
    /** 缩略图串行队列：一次只从相机下载一张，避免并发请求把相机 HTTP 服务压垮。 */
    private final ExecutorService thumbExec = Executors.newSingleThreadExecutor();
    /** 通用异步请求池：GET / 事件轮询 / 下载全部走后台线程 + 回调，避免阻塞 WebView UI 线程（卡死）。 */
    private final ExecutorService apiExec = Executors.newCachedThreadPool();
    /** 后台保持：退到后台/锁屏后仍持续备份（WakeLock 保 CPU + 前台服务保进程）。 */
    private PowerManager.WakeLock wakeLock;
    /** 后台心跳：WebView 退后台后 JS 定时器会被系统节流，用 Java Handler 每 2.5s 强制唤醒 JS 扫描/备份。 */
    private final Handler bgHandler = new Handler(Looper.getMainLooper());
    private boolean bgTickOn = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 状态栏适配：浅色背景 + 深色图标，内容不延伸（header 正常排在状态栏下方，不与状态栏重叠）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.rgb(247, 248, 250));   // 与页面皎白背景 --bg 一致
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        webView = new WebView(this);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        // file:// 页面加载 http 相机缩略图/大图需要放行混合内容
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient());
        // 渲染进程提权：退后台/锁屏后渲染进程不易被系统回收，JS 与 Java 心跳持续生效
        if (Build.VERSION.SDK_INT >= 26) {
            try { webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false); } catch (Exception e) { /* 忽略 */ }
        }
        webView.addJavascriptInterface(new CameraBridge(), "CameraBridge");
        webView.loadUrl("file:///android_asset/www/index.html");
        setContentView(webView);

        // 后台备份保持：锁屏/退后台后 WebView JS 定时器与下载桥仍可运行
        keepAlive();
        startBgTick();
    }

    /** 后台心跳：evaluateJavascript 唤醒不受 WebView 后台节流影响，等效后台持续快速检测 + 自动备份。 */
    private void startBgTick() {
        if (bgTickOn) return;
        bgTickOn = true;
        bgHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!bgTickOn) return;
                try {
                    if (webView != null) {
                        webView.evaluateJavascript("window.__bgTick && window.__bgTick();", null);
                    }
                } catch (Exception e) { /* 忽略 */ }
                bgHandler.postDelayed(this, 2000);
            }
        });
    }

    private void stopBgTick() {
        bgTickOn = false;
        bgHandler.removeCallbacksAndMessages(null);
    }

    /** 后台保持：PARTIAL_WAKE_LOCK（锁屏后 CPU 不睡）+ 前台服务（进程不被回收）。
     *  注意：不调用 webView.onPause()，页面 JS 定时器在后台继续驱动备份。 */
    private void keepAlive() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "canon-backup:keep");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
        } catch (Exception e) { /* 忽略：个别机型拒绝 WakeLock */ }
        try {
            Intent si = new Intent(this, BackupService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(si);
            } else {
                startService(si);
            }
        } catch (Exception e) { /* 忽略 */ }
        // Android 13+：通知 + 照片 + 视频 合并一次请求（系统一次只弹一个对话框，分开请求会被后一个顶掉）
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                List<String> perms = new ArrayList<>();
                perms.add("android.permission.POST_NOTIFICATIONS");
                if (checkSelfPermission("android.permission.READ_MEDIA_IMAGES") != PackageManager.PERMISSION_GRANTED) {
                    perms.add("android.permission.READ_MEDIA_IMAGES");
                }
                if (checkSelfPermission("android.permission.READ_MEDIA_VIDEO") != PackageManager.PERMISSION_GRANTED) {
                    perms.add("android.permission.READ_MEDIA_VIDEO");
                }
                if (!perms.isEmpty()) requestPermissions(perms.toArray(new String[0]), 101);
            } catch (Exception e) { /* 忽略 */ }
        }
    }

    /** 前台服务是否存活（Android 14 划掉保活通知会停服务，需在回前台时检查并恢复）。 */
    private boolean isBackupServiceRunning() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am != null) {
                for (ActivityManager.RunningServiceInfo s : am.getRunningServices(Integer.MAX_VALUE)) {
                    if (BackupService.class.getName().equals(s.service.getClassName())) return true;
                }
            }
        } catch (Exception e) { /* 忽略 */ }
        return false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 通知被划掉/服务被系统停后，App 每次回到前台自动恢复保活（回前台启动前台服务不受后台限制）
        if (!isBackupServiceRunning()) {
            try {
                Intent si = new Intent(this, BackupService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(si);
                else startService(si);
            } catch (Exception e) { /* 忽略 */ }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopBgTick();
        if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (Exception e) { /* 忽略 */ }
        }
        try { stopService(new Intent(this, BackupService.class)); } catch (Exception e) { /* 忽略 */ }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // 存储权限结果回调给 JS（当前写入 MediaStore 不依赖该权限，仅为完整性）
        if (requestCode == REQ_STORAGE && webView != null) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            webView.evaluateJavascript("window.__permResult && window.__permResult(" + granted + ")", null);
        }
    }

    /** 原生网络桥：JS 通过 window.CameraBridge 调用，绕过浏览器 CORS。 */
    private class CameraBridge {

        /** 原生 GET 请求，返回 {"status":<HTTP码>,"body":"<响应文本>","error":"<异常详情>"}。用于目录列表等 JSON 接口。 */
        @JavascriptInterface
        public String requestText(String url) {
            return doGet(url, 10000);
        }

        /** 目录分页探测：带 Range 请求头（佳能部分机型以 items 为单位分页目录列表；
         *  不支持的机型忽略 Range 返回第一页，由 JS 侧去重停止翻页）。返回与 requestText 相同格式。 */
        @JavascriptInterface
        public String requestTextRange(String url, String range) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(4000);
                c.setReadTimeout(8000);
                c.setRequestProperty("Accept", "application/json");
                c.setRequestProperty("Accept-Encoding", "");   // 官方 sendRequest：禁 gzip
                c.setRequestProperty("User-Agent", "canon-backup/1.0");
                if (range != null && range.length() > 0) c.setRequestProperty("Range", range);
                int code = c.getResponseCode();
                InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                if (in != null) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
                    in.close();
                }
                c.disconnect();
                return "{\"status\":" + code + ",\"body\":" + quote(bos.toString("UTF-8")) + "}";
            } catch (Exception e) {
                if (c != null) { try { c.disconnect(); } catch (Exception e2) { } }
                return "{\"status\":0,\"body\":" + quote(e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "无详情" : e.getMessage())) + "}";
            }
        }

        /** 连接探测专用：单次尝试 + 短超时，避免同步调用把界面卡住过久。 */
        @JavascriptInterface
        public String requestTextFast(String url) {
            return doGetOnce(url, 6000);
        }

        /** 事件长轮询：相机无事件时挂起约 60s，按下快门后立即返回新文件列表。 */
        @JavascriptInterface
        public String pollEvents(String url) {
            return doGet(url, 80000);
        }

        private String doGetOnce(String url, int readTimeoutMs) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(4000);
                c.setReadTimeout(readTimeoutMs);
                c.setRequestProperty("Accept", "application/json");
                c.setRequestProperty("Accept-Encoding", "");   // 官方 sendRequest：禁 gzip
                c.setRequestProperty("User-Agent", "canon-backup/1.0");
                int code = c.getResponseCode();
                InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                if (in != null) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
                    in.close();
                }
                c.disconnect();
                return "{\"status\":" + code + ",\"body\":" + quote(bos.toString("UTF-8")) + "}";
            } catch (Exception e) {
                String msg = e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "无详情" : e.getMessage());
                return "{\"status\":0,\"body\":\"\",\"error\":" + quote(msg) + "}";
            } finally {
                if (c != null) c.disconnect();
            }
        }

        private String doGet(String url, int readTimeoutMs) {
            String lastErr = null;
            for (int attempt = 0; attempt < 3; attempt++) {
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(4000);
                    c.setReadTimeout(readTimeoutMs);
                    c.setRequestProperty("Accept", "application/json");
                    c.setRequestProperty("Accept-Encoding", "");   // 官方 sendRequest：禁 gzip
                    c.setRequestProperty("User-Agent", "canon-backup/1.0");
                    int code = c.getResponseCode();
                    // 相机 HTTP 服务繁忙（读卡/传文件中）会临时返回 502/503/504/429，短暂等待后重试
                    if ((code == 502 || code == 503 || code == 504 || code == 429) && attempt < 2) {
                        lastErr = "HTTP " + code;
                        try { Thread.sleep(900 * (attempt + 1)); } catch (InterruptedException ie) { /* 忽略 */ }
                        continue;
                    }
                    InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    if (in != null) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
                        in.close();
                    }
                    c.disconnect();
                    return "{\"status\":" + code + ",\"body\":" + quote(bos.toString("UTF-8")) + "}";
                } catch (Exception e) {
                    // 网络瞬时异常也重试；把最终异常类型与原因透传给 JS
                    if (attempt < 2) {
                        lastErr = e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "无详情" : e.getMessage());
                        try { Thread.sleep(900 * (attempt + 1)); } catch (InterruptedException ie) { /* 忽略 */ }
                        continue;
                    }
                    String msg = e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "无详情" : e.getMessage());
                    return "{\"status\":0,\"body\":\"\",\"error\":" + quote(msg) + "}";
                } finally {
                    if (c != null) c.disconnect();
                }
            }
            return "{\"status\":0,\"body\":\"\",\"error\":" + quote(lastErr == null ? "未知错误" : lastErr) + "}";
        }


        /** 分块同步下载：每次最多下载 maxBytes 字节到临时文件，供 JS 逐块拉取（块间界面可刷新）。
         * 返回 {"ok":true,"complete":bool,"bytes":N,"offset":M,"total":T} 或 {"ok":false,"error":...}。 */
        @JavascriptInterface
        public String downloadChunk(String url, String filename, long offset, long maxBytes) {
            File base = getExternalFilesDir(null);
            if (base == null) base = getCacheDir();
            File dir = new File(base, "canon-tmp");
            if (!dir.exists()) dir.mkdirs();
            String safe = filename.replaceAll("[^A-Za-z0-9._-]", "_");
            File tmp = new File(dir, safe + ".part");
            HttpURLConnection c = null;
            try {
                long off = offset > 0 ? offset : tmp.length();
                long chunk = maxBytes > 0 ? maxBytes : 1048576L;
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(15000);
                c.setRequestProperty("Accept", "*/*");
                c.setRequestProperty("Accept-Encoding", "");   // 官方 sendRequest：禁 gzip
                c.setRequestProperty("User-Agent", "canon-backup/1.0");
                if (off > 0) {
                    c.setRequestProperty("Range", "bytes=" + off + "-" + (off + chunk - 1));
                }
                int code = c.getResponseCode();
                if (code == 502 || code == 503 || code == 504 || code == 429) {
                    return "{\"ok\":false,\"busy\":true,\"error\":\"HTTP " + code + "\"}";
                }
                if (code != 200 && code != 206) {
                    return "{\"ok\":false,\"error\":\"HTTP " + code + "\"}";
                }
                long total = -1;
                String cl = c.getHeaderField("Content-Length");
                if (cl != null && cl.trim().length() > 0) {
                    try { total = Long.parseLong(cl.trim()); } catch (NumberFormatException nfe) { total = -1; }
                    total = code == 206 ? off + total : total;
                }
                boolean append = code == 206;
                if (code == 200 && off > 0) { off = 0; append = false; }   // 相机不支持 Range，从头写

                InputStream in = new BufferedInputStream(c.getInputStream());
                OutputStream fos = new BufferedOutputStream(new FileOutputStream(tmp, append));
                byte[] buf = new byte[65536];
                int n;
                long written = 0;
                while ((n = in.read(buf)) != -1) {
                    fos.write(buf, 0, n); written += n;
                    if (written >= chunk) break;
                }
                fos.close(); in.close(); c.disconnect();
                long now = off + written;
                boolean done = total > 0 ? now >= total : written < chunk;   // 无 Content-Length 时按不足块长判定结束
                return "{\"ok\":true,\"complete\":" + done + ",\"bytes\":" + written + ",\"offset\":" + now + ",\"total\":" + total + "}";
            } catch (Exception e) {
                String msg = e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "无详情" : e.getMessage());
                return "{\"ok\":false,\"error\":" + quote(msg) + "}";
            } finally {
                if (c != null) c.disconnect();
            }
        }

        /** 分块下载完成后：把临时文件写入系统相册（CanonBackup 相簿），并返回校验结果。 */
        @JavascriptInterface
        public String finalizeDownload(String filename) {
            File base = getExternalFilesDir(null);
            if (base == null) base = getCacheDir();
            File tmp = new File(new File(base, "canon-tmp"), filename.replaceAll("[^A-Za-z0-9._-]", "_") + ".part");
            if (!tmp.exists() || tmp.length() == 0) {
                return "{\"ok\":false,\"error\":\"临时文件不存在\"}";
            }
            try {
                String lower = filename.toLowerCase(Locale.ROOT);
                boolean isVideo = lower.endsWith(".mp4") || lower.endsWith(".mov");
                String mime;
                Uri collection;
                String ddir;
                if (isVideo) {
                    mime = "video/mp4";
                    collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                    ddir = Environment.DIRECTORY_MOVIES + "/CanonBackup";
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        // RAW：下载目录 + image/x-canon-cr3（原名 .CR3，不加后缀）
                        mime = "image/x-canon-cr3";
                        collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                        ddir = Environment.DIRECTORY_DOWNLOADS + "/CanonBackup";
                    } else {
                        mime = "image/jpeg";
                        collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                        ddir = Environment.DIRECTORY_PICTURES + "/CanonBackup";
                    }
                }
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cv.put(MediaStore.MediaColumns.RELATIVE_PATH, ddir);
                }
                Uri u;
                try {
                    u = getContentResolver().insert(collection, cv);
                } catch (IllegalArgumentException mie) {
                    if ((lower.endsWith(".cr3") || lower.endsWith(".cr2")) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        // Downloads 被拒：降级相册 + image/jpeg + 原名
                        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
                        cv.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
                        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/CanonBackup");
                        u = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                    } else {
                        throw mie;
                    }
                }
                if (u == null) return "{\"ok\":false,\"error\":\"写入相册失败\"}";
                OutputStream os = getContentResolver().openOutputStream(u);
                if (os == null) return "{\"ok\":false,\"error\":\"无法打开相册输出流\"}";
                FileInputStream fis = new FileInputStream(tmp);
                byte[] buf = new byte[65536];
                int n;
                long w = 0;
                while ((n = fis.read(buf)) != -1) { os.write(buf, 0, n); w += n; }
                fis.close(); os.close();
                tmp.delete();
                return "{\"ok\":true,\"bytes\":" + w + ",\"verified\":true}";
            } catch (Exception e) {
                String msg = e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "无详情" : e.getMessage());
                return "{\"ok\":false,\"error\":" + quote(msg) + "}";
            }
        }

        /** 异步进度下载：后台线程分块下载，每 256KB 回调一次进度到 JS（参数 4 个，与 JS 调用完全一致）。
         * 断点续传（Range + 临时文件）+ Content-Length 完整性校验 + 完成后直接写入系统相册。
         * 回调 JSON：{"phase":"prog","bytes":N,"total":T} / {"phase":"done","ok":true,"bytes":N,"verified":true}
         *          / {"phase":"busy","error":"HTTP 502"} / {"phase":"err","error":"..."} */
        @JavascriptInterface
        public void downloadProg(String url, String filename, long offset, String cb) {
            apiExec.execute(() -> {
                try {
                    if (hasLocalFile(filename)) {
                        // 本地已存在同名文件（含带 (1)(2) 后缀的旧副本）→ 直接跳过，不再重复下载
                        final long t2 = queryLocalTime(filename);
                        final String done2 = "{\"phase\":\"done\",\"ok\":true,\"bytes\":0,\"verified\":true,\"skipped\":true,\"time\":" + t2 + "}";
                        final String js2 = "try{" + cb + "(" + quote(done2) + ");}catch(e){}";
                        runOnUiThread(() -> webView.evaluateJavascript(js2, null));
                        return;
                    }
                } catch (Exception ignored) {
                }
                final String r = doDownloadProg(url, filename, offset, cb);
                final String js = "try{" + cb + "(" + quote(r) + ");}catch(e){}";
                runOnUiThread(() -> webView.evaluateJavascript(js, null));
            });
        }

        private String doDownloadProg(String url, String filename, long offset, String cb) {
            File base = getExternalFilesDir(null);
            if (base == null) base = getCacheDir();
            File dir = new File(base, "canon-tmp");
            if (!dir.exists()) dir.mkdirs();
            String safe = filename.replaceAll("[^A-Za-z0-9._-]", "_");
            File tmp = new File(dir, safe + ".part");
            HttpURLConnection c = null;
            try {
                long off = offset > 0 ? offset : tmp.length();
                long total = -1;
                boolean append = off > 0;
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(30000);
                c.setRequestProperty("Accept", "*/*");
                c.setRequestProperty("Accept-Encoding", "");   // 官方 sendRequest：禁 gzip
                c.setRequestProperty("User-Agent", "canon-backup/1.0");
                if (off > 0) c.setRequestProperty("Range", "bytes=" + off + "-");
                int code = c.getResponseCode();
                if (code == 502 || code == 503 || code == 504 || code == 429) {
                    return "{\"phase\":\"busy\",\"error\":\"HTTP " + code + "\"}";
                }
                if (code == 416 && off > 0) {
                    // Range 起始已超出文件末尾：临时文件已完整（上次传输完成但写相册中断）
                    return saveToGallery(filename, tmp, off);
                }
                if (code != 200 && code != 206) return "{\"phase\":\"err\",\"error\":\"HTTP " + code + "\"}";
                String cl = c.getHeaderField("Content-Length");
                if (cl != null && cl.trim().length() > 0) {
                    try { total = Long.parseLong(cl.trim()); } catch (NumberFormatException nfe) { total = -1; }
                    total = code == 206 ? off + total : total;
                }
                if (code == 200 && off > 0) { off = 0; append = false; }   // 相机不支持 Range，从头写
                InputStream in = new BufferedInputStream(c.getInputStream());
                OutputStream fos = new BufferedOutputStream(new FileOutputStream(tmp, append));
                byte[] buf = new byte[65536];
                int n;
                long written = 0, lastCb = 0;
                while ((n = in.read(buf)) != -1) {
                    fos.write(buf, 0, n); written += n;
                    if (written - lastCb >= 262144) {   // 每 256KB 回调一次进度，界面实时刷新
                        lastCb = written;
                        final String prog = "{\"phase\":\"prog\",\"bytes\":" + (off + written) + ",\"total\":" + total + "}";
                        final String js2 = "try{" + cb + "(" + quote(prog) + ");}catch(e){}";
                        runOnUiThread(() -> webView.evaluateJavascript(js2, null));
                    }
                }
                fos.close(); in.close(); c.disconnect();
                long bytes = off + written;
                if (total > 0 && bytes != total) {
                    return "{\"phase\":\"err\",\"error\":\"大小校验失败 " + bytes + "/" + total + "\"}";
                }
                // 写入系统相册（CanonBackup 相簿），完整下载后校验通过才写入
                return saveToGallery(filename, tmp, bytes);
            } catch (Exception e) {
                String msg = e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "无详情" : e.getMessage());
                return "{\"phase\":\"err\",\"error\":" + quote(msg) + "}";
            } finally {
                if (c != null) c.disconnect();
            }
        }

        /** 把已完整下载的临时文件写入系统相册（CanonBackup 相簿），CR3 兼容回退，成功后删除临时文件。 */
        private String saveToGallery(String filename, File tmp, long bytes) {
            try {
                String lower = filename.toLowerCase(Locale.ROOT);
                boolean isCr = lower.endsWith(".cr3") || lower.endsWith(".cr2");
                boolean isVideo = lower.endsWith(".mp4") || lower.endsWith(".mov");
                String mime;
                Uri collection;
                String ddir;
                String display = filename;
                if (isCr) {
                    // RAW：存入「下载/CanonBackup」，保持原名 .CR3（MIME image/x-canon-cr3 与 .cr3 后缀匹配，不会被追加 .jpg）
                    // 需配合「所有文件访问权限」：开启后目录级检测 + MediaStore 均不受重装限制，防重彻底生效
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                        ddir = Environment.DIRECTORY_DOWNLOADS + "/CanonBackup";
                        mime = "image/x-canon-cr3";
                    } else {
                        // 极老系统无 Downloads 集合：降级相册（image/jpeg 会带 .jpg 后缀，内容仍为 RAW 原样）
                        collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                        ddir = Environment.DIRECTORY_PICTURES + "/CanonBackup";
                        mime = "image/jpeg";
                        display = filename;
                    }
                } else if (isVideo) {
                    mime = "video/mp4";
                    collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                    ddir = Environment.DIRECTORY_MOVIES + "/CanonBackup";
                } else {
                    mime = "image/jpeg";
                    collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                    ddir = Environment.DIRECTORY_PICTURES + "/CanonBackup";
                }
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, display);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cv.put(MediaStore.MediaColumns.RELATIVE_PATH, ddir);
                }
                Uri u;
                try {
                    u = getContentResolver().insert(collection, cv);
                } catch (IllegalArgumentException mie) {
                    if (isCr && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        // Downloads 被个别 ROM 拒绝：降级相册 + image/jpeg + 原名（系统可能自动补 .jpg 后缀，内容仍为 RAW 原样）
                        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
                        cv.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
                        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/CanonBackup");
                        u = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                    } else {
                        throw mie;
                    }
                }
                if (u == null) return "{\"phase\":\"err\",\"error\":\"写入相册失败\"}";
                OutputStream os = getContentResolver().openOutputStream(u);
                if (os == null) return "{\"phase\":\"err\",\"error\":\"无法打开相册输出流\"}";
                FileInputStream fis = new FileInputStream(tmp);
                byte[] buf = new byte[65536];
                int n;
                long w = 0;
                while ((n = fis.read(buf)) != -1) { os.write(buf, 0, n); w += n; }
                fis.close(); os.close();
                tmp.delete();
                long shot = 0;
                try {
                    Cursor cq = getContentResolver().query(u, new String[]{MediaStore.Images.ImageColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_MODIFIED}, null, null, null);
                    if (cq != null && cq.moveToFirst()) {
                        long tk = cq.getLong(0);
                        long md = cq.getLong(1);
                        if (tk > 0) shot = tk; else if (md > 0) shot = md * 1000L;
                    }
                    if (cq != null) cq.close();
                } catch (Exception ignored) {
                }
                return "{\"phase\":\"done\",\"ok\":true,\"bytes\":" + bytes + ",\"verified\":true,\"time\":" + shot + "}";
            } catch (Exception e) {
                String msg = e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "无详情" : e.getMessage());
                return "{\"phase\":\"err\",\"error\":" + quote(msg) + "}";
            }
        }

        /** 查询本地 MediaStore 是否存在同名文件（图片/视频/下载三个集合）：重装 App 后用于跳过已备份。
         *  Files 全集查询会被权限过滤掉下载目录，因此分别查三个集合；Downloads 无需权限必命中 CR3。 */
        @JavascriptInterface
        public boolean hasAllFilesAccess() {
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager();
        }

        @JavascriptInterface
        public void openAllFilesSettings() {
            runOnUiThread(() -> {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        Intent it = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:" + getPackageName()));
                        startActivity(it);
                    }
                } catch (Exception e) {
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                    } catch (Exception e2) {
                    }
                }
            });
        }

        /** 查询本地 MediaStore 中同名文件的拍摄时间（DATE_TAKEN 为 EXIF 时间 ms；无则用 DATE_MODIFIED 秒×1000）。
         *  返回 0 表示未找到/未知。 */
        private long queryLocalTime(String filename) {
            if (filename == null || filename.isEmpty()) return 0;
            String[] proj = new String[]{MediaStore.Images.ImageColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_MODIFIED};
            String sel = MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?";
            String[] args = new String[]{filename + "%"};
            ContentResolver cr = getContentResolver();
            Uri[] uris = new Uri[]{
                    MediaStore.Files.getContentUri("external"),
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI
            };
            for (Uri u : uris) {
                Cursor cur = null;
                try {
                    cur = cr.query(u, proj, sel, args, null);
                    if (cur != null && cur.moveToFirst()) {
                        long tk = cur.getLong(0);
                        long md = cur.getLong(1);
                        if (tk > 0) return tk;
                        if (md > 0) return md * 1000L;
                    }
                } catch (Exception ignored) {
                } finally {
                    if (cur != null) cur.close();
                }
            }
            return 0;
        }

        /** 批量返回 CanonBackup 目录下所有已备份文件的拍摄时间映射 {name: ms}。
         *  JS 启动/扫描完成后调用一次，让"谁最新排最前"按真实拍摄时间跨类型混排。 */
        @JavascriptInterface
        public String getBackedTimes() {
            JSONObject map = new JSONObject();
            try {
                String[] proj = new String[]{MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.Images.ImageColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_MODIFIED};
                Uri[] uris = new Uri[]{
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI
                };
                String sel = null, arg = null;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    sel = MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?";
                    arg = "%CanonBackup%";
                }
                for (Uri u : uris) {
                    Cursor cur = null;
                    try {
                        cur = getContentResolver().query(u, proj, sel, arg == null ? null : new String[]{arg}, null);
                        if (cur != null) {
                            int iName = cur.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
                            int iTaken = cur.getColumnIndex(MediaStore.Images.ImageColumns.DATE_TAKEN);
                            int iMod = cur.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED);
                            if (iName >= 0) {
                                while (cur.moveToNext()) {
                                    String nm = cur.getString(iName);
                                    if (nm == null || nm.length() == 0) continue;
                                    long t = 0;
                                    if (iTaken >= 0) { long tk = cur.getLong(iTaken); if (tk > 0) t = tk; }
                                    if (t <= 0 && iMod >= 0) { long md = cur.getLong(iMod); if (md > 0) t = md * 1000L; }
                                    if (t > 0) map.put(nm, t);
                                }
                            }
                        }
                    } catch (Exception ignored) {
                    } finally {
                        if (cur != null) cur.close();
                    }
                }
            } catch (Exception ignored) {
            }
            return map.toString();
        }

        @JavascriptInterface
        public boolean hasLocalFile(String filename) {
            if (filename == null || filename.isEmpty()) return false;
            String[] proj = new String[]{MediaStore.MediaColumns._ID};
            // 前缀匹配：兼容历史重复副本（IMG_xxx.CR3、IMG_xxx.CR3 (1)…）
            String sel = MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?";
            String[] args = new String[]{filename + "%"};
            // 0) 全部文件访问权限：直接文件系统检测（最可靠，重装后旧文件照样命中）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
                try {
                    File dl = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "CanonBackup");
                    if (dl.isDirectory()) {
                        String low = filename.toLowerCase(Locale.ROOT);
                        String[] fs = dl.list();
                        if (fs != null) {
                            for (String f : fs) {
                                if (f.toLowerCase(Locale.ROOT).startsWith(low)) return true;
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }
            ContentResolver cr = getContentResolver();
            // 1) Files 全集优先：权限授予后覆盖图片/视频/下载全部（Downloads 集合单独查在 Android13+ 只见当前安装期的文件）
            Cursor cur = null;
            try {
                cur = cr.query(MediaStore.Files.getContentUri("external"), proj, sel, args, null);
                if (cur != null && cur.moveToFirst()) return true;
            } catch (Exception ignored) {
            } finally {
                if (cur != null) cur.close();
            }
            // 2) 兜底：图片/视频/下载 三集合分别查
            Uri[] uris = new Uri[]{
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI
            };
            for (Uri u : uris) {
                cur = null;
                try {
                    cur = cr.query(u, proj, sel, args, null);
                    if (cur != null && cur.moveToFirst()) return true;
                } catch (Exception ignored) {
                } finally {
                    if (cur != null) cur.close();
                }
            }
            return false;
        }

        /** 检查并请求存储权限（Android 10+ 写入相册无需权限，此方法保证兼容并满足应用完整性）。 */
        @JavascriptInterface
        public boolean ensureStoragePermission() {
            if (Build.VERSION.SDK_INT >= 33) {
                if (checkSelfPermission("android.permission.READ_MEDIA_IMAGES") != PackageManager.PERMISSION_GRANTED) {
                    SharedPreferences sp = getSharedPreferences("canon", MODE_PRIVATE);
                    if (sp.getBoolean("asked_storage", false)) {
                        return false;   // 已请求过（含被拒）：不再反复弹窗，用户可去系统设置开启
                    }
                    sp.edit().putBoolean("asked_storage", true).apply();
                    requestPermissions(new String[]{
                            "android.permission.READ_MEDIA_IMAGES",
                            "android.permission.READ_MEDIA_VIDEO"}, REQ_STORAGE);
                    return false;
                }
                return true;
            }
            return true; // API 29-32 写入 MediaStore 无需存储权限
        }

        /** 断点续传下载（v1.2.0）：
         * 请求带 Range: bytes=offset-，写入 app 私有临时文件（可断电续传），
         * 完整下载后做大小校验，通过才拷入手机相册并清理临时文件。
         * 返回 JSON：{ok, complete, bytes(本次新增), offset(新偏移), total, verified, error}。 */
        @JavascriptInterface
        public String download(String url, String filename, long offset) {
            return downloadImpl(url, filename, offset);
        }

        private String downloadImpl(String url, String filename, long offset) {
            File base = getExternalFilesDir(null);
            if (base == null) base = getCacheDir();
            File dir = new File(base, "canon-tmp");
            if (!dir.exists()) dir.mkdirs();
            String safe = filename.replaceAll("[^A-Za-z0-9._-]", "_");
            File tmp = new File(dir, safe + ".part");
            HttpURLConnection c = null;
            try {
                long off = offset > 0 ? offset : tmp.length();   // 已有临时文件则从其大小续传
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(15000);
                c.setRequestProperty("Accept", "*/*");
                c.setRequestProperty("Accept-Encoding", "");   // 官方 sendRequest：禁 gzip
                c.setRequestProperty("User-Agent", "canon-backup/1.0");
                if (off > 0) c.setRequestProperty("Range", "bytes=" + off + "-");
                int code = c.getResponseCode();
                if (code == 502 || code == 503 || code == 504 || code == 429) {
                    return "{\"ok\":false,\"busy\":true,\"error\":\"HTTP " + code + "\"}";
                }
                if (code != 200 && code != 206) {
                    return "{\"ok\":false,\"error\":\"HTTP " + code + "\"}";
                }
                long total = -1;
                String cl = c.getHeaderField("Content-Length");
                if (cl != null && cl.trim().length() > 0) {
                    try { total = Long.parseLong(cl.trim()); } catch (NumberFormatException nfe) { total = -1; }
                    total = code == 206 ? off + total : total;
                }
                boolean append = code == 206;
                if (code == 200 && off > 0) { off = 0; append = false; }   // 相机不支持 Range，从头写

                InputStream in = new BufferedInputStream(c.getInputStream());
                OutputStream fos = new BufferedOutputStream(new FileOutputStream(tmp, append));
                byte[] buf = new byte[65536];
                int n;
                long written = 0;
                while ((n = in.read(buf)) != -1) { fos.write(buf, 0, n); written += n; }
                fos.close(); in.close(); c.disconnect(); c = null;

                long now = off + written;
                if (total > 0 && now < total) {
                    // 部分完成（网络断开/相机繁忙截断）：记录偏移供下次续传
                    return "{\"ok\":true,\"complete\":false,\"bytes\":" + written + ",\"offset\":" + now + ",\"total\":" + total + ",\"verified\":true}";
                }
                boolean verified = total <= 0 || now >= total;   // 大小校验：有 Content-Length 时偏移达标才完整
                if (total > 0 && now > total) verified = false;

                // 完整性通过 → 拷入相册/影片
                String lower = filename.toLowerCase(Locale.ROOT);
                boolean isVideo = lower.endsWith(".mp4") || lower.endsWith(".mov");
                String mime;
                Uri collection;
                String ddir;
                if (isVideo) {
                    mime = "video/mp4";
                    collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                    ddir = Environment.DIRECTORY_MOVIES + "/CanonBackup";
                } else {
                    mime = "image/jpeg";
                    collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                    ddir = Environment.DIRECTORY_PICTURES + "/CanonBackup";
                }
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cv.put(MediaStore.MediaColumns.RELATIVE_PATH, ddir);
                }
                Uri uri = getContentResolver().insert(collection, cv);
                if (uri == null) {
                    return "{\"ok\":false,\"error\":\"无法创建文件\"}";
                }
                InputStream fin = new BufferedInputStream(new java.io.FileInputStream(tmp));
                OutputStream fout = new BufferedOutputStream(getContentResolver().openOutputStream(uri));
                byte[] fbuf = new byte[65536];
                int fn;
                while ((fn = fin.read(fbuf)) != -1) fout.write(fbuf, 0, fn);
                fout.flush(); fout.close(); fin.close();
                tmp.delete();
                return "{\"ok\":true,\"complete\":true,\"bytes\":" + now + ",\"offset\":" + now + ",\"verified\":" + verified + "}";
            } catch (Exception e) {
                if (c != null) c.disconnect();
                String msg = e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "无详情" : e.getMessage());
                return "{\"ok\":false,\"error\":" + quote(msg) + "}";
            } finally {
                if (c != null) c.disconnect();
            }
        }

        @JavascriptInterface
        public int statusBarHeightPx() {
            // 精确读取状态栏高度（px）：沉浸式布局下让页面顶部安全区与状态栏对齐
            try {
                WindowInsets insets = getWindow().getDecorView().getRootWindowInsets();
                if (insets != null) {
                    if (Build.VERSION.SDK_INT >= 30) {
                        return insets.getInsets(WindowInsets.Type.systemBars()).top;
                    }
                    return insets.getSystemWindowInsetTop();
                }
            } catch (Exception e) { }
            int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) return getResources().getDimensionPixelSize(id);
            return (int)(24 * getResources().getDisplayMetrics().density);
        }

        /** 检测桥是否存在（供 JS 判断是否原生环境）。 */
        @JavascriptInterface
        public boolean available() {
            return true;
        }

        /** 事件轮询状态实时入日志面板（JS window.__evtLog），供用户复制日志排查。 */
        private void evtLog(final String msg) {
            runOnUiThread(() -> {
                try {
                    webView.evaluateJavascript("try{window.__evtLog && window.__evtLog(" + quote(msg) + ");}catch(e){}", null);
                } catch (Exception e) { /* 忽略 */ }
            });
        }

        // ================= 官方 CCAPI 事件实现（1:1 移植 Sample/Android 1.4.0f） =================
        // 对应官方类：EventThread（事件循环/停止）、HttpCommunication（sendRequest/getChunkResponse/readChunk）、
        //           OrgFormatDataSet（binary-unit 帧）、WebAPI.sendEventPolling/sendEventMonitoring。
        // 事件句柄：官方 EventThread 同一时刻仅一个在途事件请求；stopEvent 对应官方 EventThread.stopThread。
        private volatile Thread evtThread;
        private volatile Socket evtSocket;
        private volatile HttpURLConnection evtConn;
        private volatile boolean evtStopped;

        /** 官方 binary-unit 帧解析（OrgFormatDataSet.java 1:1，Big-Endian）：
         *  Start 0xFF00(-256) + DataType(1B) + DataSize(4B) + Data + End 0xFFFF(-1)；
         *  DataType：0x00=LV 图像 / 0x01=LV 信息 / 0x02=事件 JSON；半包抛 BufferUnderflow 丢弃（官方容错）。 */
        private static class OrgFormatDataSet {
            private final byte[] eventData;
            OrgFormatDataSet(byte[] bytes) {
                byte[] ev = null;
                if (bytes != null) {
                    java.nio.ByteBuffer bb =
                            java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.BIG_ENDIAN);
                    try {
                        while (bb.position() < bb.limit()) {
                            short startByte = bb.getShort();              // Start Byte (2B)
                            if (startByte == (short) 0xFF00) {
                                byte dataType = bb.get();                 // Data Type (1B)
                                int dataSize = bb.getInt();               // Data Size (4B)
                                byte[] data = new byte[dataSize];
                                bb.get(data, 0, dataSize);                // Data
                                short endByte = bb.getShort();            // End Byte (2B)
                                if (dataType == 0x02) ev = data;          // 仅取事件
                                if (endByte != (short) 0xFFFF) break;
                            } else {
                                break;
                            }
                        }
                    } catch (java.nio.BufferUnderflowException e) { /* 官方：半包丢弃 */ }
                }
                this.eventData = ev;
            }
            byte[] getEventData() { return eventData; }
        }

        /** 官方 HttpCommunication.readLine 1:1：逐字节读到 CRLF，超过 256 字符判错。 */
        private static String readLineRaw(InputStream in) throws java.io.IOException {
            StringBuilder sb = new StringBuilder();
            int cnt = 0;
            while (true) {
                int ch = in.read();
                if (ch < 0) throw new java.io.IOException("eof");
                if (ch == '\r') {
                    int ch2 = in.read();
                    if (ch2 == '\n') break;
                    sb.append((char) ch2);
                } else {
                    sb.append((char) ch);
                }
                if (++cnt > 256) throw new java.io.IOException("line too long");
            }
            return sb.toString();
        }

        /** 官方 HttpCommunication.readBytes 1:1：精确读满 length 字节（不足则到 EOF）。 */
        private static byte[] readBytesRaw(InputStream in, int length) throws java.io.IOException {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] tmp = new byte[1024];
            int got = 0;
            while (got < length) {
                int want = Math.min(tmp.length, length - got);
                int r = in.read(tmp, 0, want);
                if (r < 0) break;
                bos.write(tmp, 0, r);
                got += r;
            }
            return bos.toByteArray();
        }

        /** 事件结果回调 JS（cb 为 JS 全局函数名）。 */
        private void evtCb(final String cb, final String v) {
            final String js = "try{" + cb + "(" + quote(v) + ");}catch(e){}";
            runOnUiThread(() -> { try { webView.evaluateJavascript(js, null); } catch (Exception e) { } });
        }

        /** 官方事件轮询 GET event/polling（WebAPI.sendEventPolling + HttpCommunication.sendRequest 1:1）：
         *  普通请求-响应（非 chunked），响应体即事件 JSON 文本；超时取官方 DEFAULT_TIMEOUT=60000（长轮询挂起）。
         *  query 由 JS 按官方 EventMethod 选定：ver110 端点默认 timeout=long，其余默认 continue=on。
         *  一次请求对应官方 EventThread 一轮，返回后由 JS 立即发起下一轮。 */
        @JavascriptInterface
        public void eventWatch(String url, String cb) {
            evtStopped = false;
            Thread t = new Thread(() -> {
                evtThread = Thread.currentThread();
                String out;
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(url).openConnection();
                    evtConn = c;
                    c.setRequestMethod("GET");
                    c.setConnectTimeout(60000);                 // 官方 DEFAULT_TIMEOUT
                    c.setReadTimeout(60000);
                    c.setRequestProperty("Accept-Encoding", ""); // 与官方 sendRequest 一致
                    int code = c.getResponseCode();
                    if (code != 200) {
                        out = "HTTP " + code;
                    } else {
                        int len = c.getContentLength();
                        InputStream in = c.getInputStream();
                        byte[] body;
                        if (len > 0) body = readBytesRaw(in, Math.min(len, 8 * 1024 * 1024));
                        else {
                            ByteArrayOutputStream bos = new ByteArrayOutputStream();
                            byte[] tmp = new byte[4096]; int r;
                            while ((r = in.read(tmp)) != -1) bos.write(tmp, 0, r);
                            body = bos.toByteArray();
                        }
                        out = new String(body, java.nio.charset.StandardCharsets.UTF_8);
                    }
                } catch (java.net.SocketTimeoutException e) {
                    out = "TIMEOUT";
                } catch (Exception e) {
                    out = "ERR " + e.getClass().getSimpleName();
                } finally {
                    evtConn = null;
                    if (c != null) { try { c.disconnect(); } catch (Exception e) { } }
                }
                evtCb(cb, out);
            });
            t.start();
        }

        /** 官方事件流 GET event/monitoring（HttpCommunication.getChunkResponse/readChunk +
         *  EventThread.onChunkResult 1:1）：原生 Socket 手写 HTTP/1.1，标准 HTTP chunked 传输，
         *  每个 chunk 即一个 binary-unit，经 OrgFormatDataSet 取 0x02 事件 JSON 并【持续回调】（多帧），
         *  仅当 size=0 流结束 / 60s 无任何 chunk / 异常 / 被 stopEvent 停止时回调一次结束信号：
         *  CLOSED / TIMEOUT / HTTP xxx / ERR xxx / STOPPED，JS 据此决定重连。 */
        @JavascriptInterface
        public void monitorWatch(String url, String cb) {
            evtStopped = false;
            Thread t = new Thread(() -> {
                evtThread = Thread.currentThread();
                Socket sock = null;
                String out = "CLOSED";
                try {
                    URL u = new URL(url);
                    sock = new Socket(u.getHost(), u.getPort());
                    evtSocket = sock;
                    sock.setSoTimeout(60000);                  // 官方 DEFAULT_TIMEOUT（心跳帧会持续重置）
                    InputStream in = sock.getInputStream();
                    OutputStream os = sock.getOutputStream();
                    // 官方请求行 + 请求头（HttpCommunication.getChunkResponse 原样）
                    StringBuilder req = new StringBuilder();
                    req.append("GET ").append(u.getFile()).append(" HTTP/1.1\r\n");
                    req.append("HOST: ").append(u.getHost()).append("\r\n");
                    req.append("Accept-Encoding: gzip\r\n");
                    req.append("\r\n");
                    os.write(req.toString().getBytes("ISO-8859-1"));
                    os.flush();
                    // 响应行（跳过 HTTP 100 Continue）
                    int code = 0;
                    String line;
                    while (true) {
                        line = readLineRaw(in);
                        if (line.startsWith("HTTP/1.")) {
                            java.util.regex.Matcher m =
                                    java.util.regex.Pattern.compile("\\s(\\d+)\\s(.+)").matcher(line);
                            if (m.find()) {
                                code = Integer.parseInt(m.group(1));
                                if (code != 100) break;
                            }
                        }
                    }
                    // 响应头（读到空行）
                    java.util.Map<String, String> hdr = new java.util.HashMap<>();
                    line = readLineRaw(in);
                    while (line != null && !line.isEmpty() && line.contains(":")) {
                        String[] kv = line.split(":", 2);
                        if (kv.length == 2) hdr.put(kv[0].trim(), kv[1].trim());
                        line = readLineRaw(in);
                    }
                    if (code == 200) {
                        // 官方 readChunk：十六进制 size 行 -> size 字节数据 -> 2B CRLF；size=0 结束
                        while (!evtStopped && !Thread.currentThread().isInterrupted()) {
                            String sizeLine = readLineRaw(in);
                            int size;
                            try { size = Integer.parseInt(sizeLine.trim(), 16); }
                            catch (NumberFormatException e) { out = "ERR BadChunk"; break; }
                            if (size == 0) { out = "CLOSED"; break; }
                            byte[] chunk = readBytesRaw(in, size);
                            readBytesRaw(in, 2);                 // 尾随 CRLF
                            // 官方 EventThread.onChunkResult：OrgFormat 解析，事件帧回调，线程存活则继续
                            OrgFormatDataSet org = new OrgFormatDataSet(chunk);
                            byte[] ev = org.getEventData();
                            if (ev != null) {
                                evtCb(cb, new String(ev, java.nio.charset.StandardCharsets.UTF_8));
                            }
                        }
                        if (evtStopped) out = "STOPPED";
                    } else {
                        long cl = 0;
                        try { if (hdr.containsKey("Content-Length")) cl = Long.parseLong(hdr.get("Content-Length")); }
                        catch (NumberFormatException e) { /* 忽略 */ }
                        if (cl > 0) readBytesRaw(in, (int) Math.min(cl, 4096));
                        out = "HTTP " + code;
                    }
                } catch (java.net.SocketTimeoutException e) {
                    out = "TIMEOUT";
                } catch (Exception e) {
                    out = evtStopped ? "STOPPED" : ("ERR " + e.getClass().getSimpleName());
                } finally {
                    evtSocket = null;
                    if (sock != null) { try { sock.close(); } catch (Exception e) { } }
                }
                evtCb(cb, out);   // 结束信号（帧已在循环中持续回调）
            });
            t.start();
        }

        /** 官方停止事件监听（EventThread.stopThread 1:1）：
         *  POLLING_CONTINUE/SHORT/LONG -> DELETE event/polling；MONITORING -> DELETE event/monitoring。
         *  先解除在途阻塞读（关闭 Socket/连接、中断线程），再发官方 DELETE 清理相机侧事件会话。
         *  kind="polling"|"monitoring"；url=对应事件端点完整 URL（无则只中断不发 DELETE）。 */
        @JavascriptInterface
        public void stopEvent(String kind, String url) {
            evtStopped = true;
            try { if (evtSocket != null) evtSocket.close(); } catch (Exception e) { }
            try { if (evtConn != null) evtConn.disconnect(); } catch (Exception e) { }
            try { if (evtThread != null) evtThread.interrupt(); } catch (Exception e) { }
            if (url == null || url.length() == 0) return;
            apiExec.execute(() -> {
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setRequestMethod("DELETE");
                    c.setConnectTimeout(6000);
                    c.setReadTimeout(6000);
                    c.setRequestProperty("Accept-Encoding", "");
                    c.getResponseCode();
                } catch (Exception e) { /* 相机可能已断开，忽略 */ }
                finally { if (c != null) { try { c.disconnect(); } catch (Exception e) { } } }
            });
        }

        /** 异步 GET（requestText 的异步版）：后台线程请求，完成回调 cb(<JSON 字符串>)。 */
        @JavascriptInterface
        public void asyncGet(String url, String cb) {
            apiExec.execute(() -> {
                final String r = doGet(url, 20000);   // 相机忙时枚举目录慢，放宽读超时到 20s
                final String js = "try{" + cb + "(" + quote(r) + ");}catch(e){}";
                runOnUiThread(() -> webView.evaluateJavascript(js, null));
            });
        }

        /** 异步事件长轮询（pollEvents 的异步版）：后台线程挂起最长 80s，不冻结界面。 */
        @JavascriptInterface
        public void asyncPoll(String url, String cb) {
            apiExec.execute(() -> {
                final String r = doGet(url, 80000);
                final String js = "try{" + cb + "(" + quote(r) + ");}catch(e){}";
                runOnUiThread(() -> webView.evaluateJavascript(js, null));
            });
        }

        /** 异步断点续传下载（download 的异步版）：后台线程下载，完成回调 cb(<JSON 字符串>)。 */
        @JavascriptInterface
        public void asyncDownload(String url, String filename, long offset, String cb) {
            apiExec.execute(() -> {
                final String r = downloadImpl(url, filename, offset);
                final String js = "try{" + cb + "(" + quote(r) + ");}catch(e){}";
                runOnUiThread(() -> webView.evaluateJavascript(js, null));
            });
        }

        /** 生成并缓存缩略图（串行队列执行，避免并发压相机）：
         * 首次从相机下载一次原图 → 采样缩放为 256px JPEG 存本地缓存；之后命中缓存立即返回。
         * 完成后回调 JS：cb(<href>, <本地 file:// URL 或空字符串>)。 */
        @JavascriptInterface
        public void thumb(final String url, final String filename, final String href, final String cb) {
            thumbExec.execute(() -> {
                String r = doThumb(url, filename);
                final String js = "try{" + cb + "(" + quote(href) + "," + quote(r) + ");}catch(e){}";
                runOnUiThread(() -> webView.evaluateJavascript(js, null));
            });
        }

        private String doThumb(String url, String filename) {
            File dir = new File(getCacheDir(), "thumb");
            if (!dir.exists()) dir.mkdirs();
            String safe = filename.replaceAll("[^A-Za-z0-9._-]", "_");
            File out = new File(dir, safe + ".jpg");
            if (out.exists()) return out.toURI().toString();   // 本地缓存命中
            File tmp = new File(dir, safe + ".tmp");
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(4000);
                c.setReadTimeout(60000);
                c.setRequestProperty("Accept", "*/*");
                c.setRequestProperty("Accept-Encoding", "");   // 官方 sendRequest：禁 gzip
                c.setRequestProperty("User-Agent", "canon-backup/1.0");
                int code = c.getResponseCode();
                if (code != 200) return "";
                InputStream in = new BufferedInputStream(c.getInputStream());
                OutputStream fos = new BufferedOutputStream(new FileOutputStream(tmp));
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) != -1) fos.write(buf, 0, n);
                fos.close(); in.close(); c.disconnect(); c = null;
            } catch (Exception e) {
                if (c != null) c.disconnect();
                tmp.delete();
                return "";
            }
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(tmp.getAbsolutePath(), o);
                int sample = 1;
                while (o.outWidth / sample > 512 || o.outHeight / sample > 512) sample *= 2;
                BitmapFactory.Options o2 = new BitmapFactory.Options();
                o2.inSampleSize = sample;
                Bitmap bm = BitmapFactory.decodeFile(tmp.getAbsolutePath(), o2);
                if (bm == null) { tmp.delete(); return ""; }
                Bitmap tb = Bitmap.createScaledBitmap(bm, 256, 256, true);
                FileOutputStream fos = new FileOutputStream(out);
                tb.compress(Bitmap.CompressFormat.JPEG, 80, fos);
                fos.close();
                if (tb != bm) bm.recycle();
                tb.recycle();
                tmp.delete();
                return out.toURI().toString();
            } catch (Exception e) {
                tmp.delete();
                return "";
            }
        }

        /** 网络诊断：Wi-Fi / 本机 IP / 同网段 / TCP 端口 / HTTP 接口，逐项返回结果文本。 */
        @JavascriptInterface
        public String diagnose(String ip, String port) {
            StringBuilder sb = new StringBuilder();
            try {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                WifiInfo wi = wm.getConnectionInfo();
                int ipInt = wi.getIpAddress();
                String localIp = String.format(Locale.ROOT, "%d.%d.%d.%d",
                        ipInt & 0xff, (ipInt >> 8) & 0xff, (ipInt >> 16) & 0xff, (ipInt >> 24) & 0xff);
                String ssid = wi.getSSID();
                sb.append("Wi-Fi: ").append(ssid == null || ssid.length() == 0 ? "(未连接 Wi-Fi)" : ssid.replace("\"", ""))
                        .append("  手机IP: ").append(localIp);
                String[] a = localIp.split("\\.");
                String[] b = ip.split("\\.");
                boolean same = a.length == 4 && b.length == 4
                        && a[0].equals(b[0]) && a[1].equals(b[1]) && a[2].equals(b[2]);
                sb.append("\n网段: 相机IP ").append(ip).append(same ? " 与手机同网段(/24)" : " 与手机不在同一网段!");
            } catch (Exception e) {
                sb.append("Wi-Fi信息获取失败: ").append(e.getMessage());
            }
            boolean tcp = false;
            try {
                Socket sock = new Socket();
                sock.connect(new InetSocketAddress(ip, Integer.parseInt(port)), 2500);
                sock.close();
                tcp = true;
            } catch (Exception e) { /* 不通 */ }
            sb.append("\nTCP: ").append(ip).append(":").append(port).append(tcp ? " 端口可达" : " 连接超时/失败（相机未开机/不在同一网络）");
            String http = doGet("http://" + ip + ":" + port + "/ccapi/ver140/contents", 5000);
            sb.append("\nHTTP /contents: ").append(http);
            return sb.toString();
        }

        /** 复制文本到剪贴板（供"复制日志"使用）。 */
        @JavascriptInterface
        public void copyText(String text) {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("canon-log", text));
            } catch (Exception e) { /* 忽略 */ }
        }

        /** 悬浮窗权限：授予后 App 可从后台随时重新拉起前台服务（Android 官方豁免），通知被划掉即秒恢复。 */
        @JavascriptInterface
        public boolean hasOverlayPermission() {
            return Build.VERSION.SDK_INT >= 23 && Settings.canDrawOverlays(MainActivity.this);
        }

        @JavascriptInterface
        public void openOverlaySettings() {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Exception e) {
                try { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)); } catch (Exception e2) { /* 忽略 */ }
            }
        }

        /** 电池优化白名单：防止系统杀后台进程，保活更稳。 */
        @JavascriptInterface
        public void openBatterySettings() {
            try {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Exception e) {
                try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); } catch (Exception e2) { /* 忽略 */ }
            }
        }

        /** 备份完成通知：App 退到后台也能在通知栏看到成功/失败数量。 */
        @JavascriptInterface
        public void notifyBackupDone(int done, int fail) {
            try {
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm == null) return;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    NotificationChannel ch = new NotificationChannel("backup", "后台备份", NotificationManager.IMPORTANCE_LOW);
                    ch.setDescription("佳能备份通知");
                    nm.createNotificationChannel(ch);
                }
                Intent i = new Intent(MainActivity.this, MainActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                PendingIntent pi = PendingIntent.getActivity(MainActivity.this, 2, i,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? new Notification.Builder(MainActivity.this, "backup")
                        : new Notification.Builder(MainActivity.this);
                b.setSmallIcon(android.R.drawable.stat_sys_download);
                b.setContentTitle("佳能备份完成");
                b.setContentText("成功 " + done + " 个" + (fail > 0 ? " · 失败 " + fail + " 个" : ""));
                b.setContentIntent(pi);
                b.setAutoCancel(true);
                nm.notify(1002, b.build());
            } catch (Exception e) { /* 忽略：通知失败不影响备份 */ }
        }

        private String quote(String s) {
            if (s == null) s = "";
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + "\"";
        }
    }
}
