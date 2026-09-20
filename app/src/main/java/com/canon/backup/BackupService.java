package com.canon.backup;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/** 前台服务：让「佳能备份」退到后台/锁屏后仍能继续自动备份。
 *  通知常驻，进程优先级提升，系统不会随意回收。 */
public class BackupService extends Service {

    private static final int NOTIF_ID = 1;
    private static long lastRestart = 0;   // 防死循环：10 秒内不重复自重启

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel("backup", "后台备份", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("佳能备份在后台自动备份照片");
            if (Build.VERSION.SDK_INT >= 33) ch.setBlockable(false);   // 禁止用户在通知设置里关闭该渠道
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Android 14+（targetSdk 34）要求 startForeground 带服务类型，否则抛异常闪退
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, buildNotification());
        }
        return START_STICKY;
    }

    private Notification buildNotification() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, "backup")
                : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.stat_sys_download);
        b.setContentTitle("佳能备份运行中");
        b.setContentText("退到后台也会继续自动备份");
        b.setContentIntent(pi);
        b.setOngoing(true);
        return b.build();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // 通知被系统/用户清除时前台服务可能随之被停 → 立即重新挂上前台通知（保活通知不可划除）
        restartIfNeeded();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        // 被划掉/被系统停 → 主动重启恢复保活（10 秒节流防死循环）
        restartIfNeeded();
        super.onDestroy();
    }

    private void restartIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastRestart < 10000) return;
        lastRestart = now;
        try {
            Intent si = new Intent(this, BackupService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(si);
            } else {
                startService(si);
            }
        } catch (Exception e) { /* 后台启动受限时靠 onResume 恢复 */ }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
