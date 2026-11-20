package com.example.usagetracker

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat

class TrackerService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    private val heartbeat = object : Runnable {
        override fun run() {
            Tracker.beat(this@TrackerService)
            handler.postDelayed(this, 15_000)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_USER_PRESENT -> Tracker.begin(c)   // قفل باز شد
                Intent.ACTION_SCREEN_ON -> {                      // صفحه روشن شد و قفلی در کار نیست
                    val km = c.getSystemService(KeyguardManager::class.java)
                    if (!km.isKeyguardLocked) Tracker.begin(c)
                }
                Intent.ACTION_SCREEN_OFF -> Tracker.end(c)        // صفحه خاموش شد
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("tracker", "ثبت زمان استفاده", NotificationManager.IMPORTANCE_LOW)
        )
        goForeground()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        handler.post(heartbeat)
    }

    private fun goForeground() {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notif: Notification = Notification.Builder(this, "tracker")
            .setContentTitle("ثبت زمان استفاده فعال است")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notif)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        // هم‌گام‌سازی وضعیت فعلی (بعد از ریستارت گوشی یا مرگ سرویس)
        val pm = getSystemService(PowerManager::class.java)
        val km = getSystemService(KeyguardManager::class.java)
        if (pm.isInteractive && !km.isKeyguardLocked) Tracker.begin(this) else Tracker.recover(this)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(heartbeat)
        runCatching { unregisterReceiver(receiver) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
