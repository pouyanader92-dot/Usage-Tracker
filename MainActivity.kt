package com.example.usagetracker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Tracker.ensureRunning(this)   // بدون هیچ سوییچ؛ ثبت خودکار شروع می‌شود
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme { Screen() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Tracker.ensureRunning(this)
    }
}

private val fa = Locale("fa")

private fun fmt(ms: Long): String {
    val nf = NumberFormat.getInstance(fa)
    val m = ms / 60000
    val h = m / 60
    val mm = m % 60
    return when {
        m == 0L -> if (ms > 0) "کمتر از یک دقیقه" else "۰ دقیقه"
        h == 0L -> "${nf.format(mm)} دقیقه"
        else -> "${nf.format(h)} ساعت و ${nf.format(mm)} دقیقه"
    }
}

private fun clock(ms: Long): String {
    val nf = NumberFormat.getInstance(fa).apply { minimumIntegerDigits = 2; isGroupingUsed = false }
    val t = maxOf(ms, 0L) / 1000
    return "${nf.format(t / 3600)}:${nf.format(t % 3600 / 60)}:${nf.format(t % 60)}"
}

private data class Snap(
    val totals: List<Pair<Long, Long>>,
    val sessions: List<Pair<Long, Long>>,
    val openStart: Long
)

@Composable
private fun Screen() {
    val ctx = LocalContext.current
    var snap by remember { mutableStateOf<Snap?>(null) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var batteryOk by remember { mutableStateOf(true) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    LaunchedEffect(Unit) {
        while (true) {
            snap = withContext(Dispatchers.IO) {
                Snap(Tracker.dailyTotals(ctx), Tracker.todaySessions(ctx), Tracker.openStart(ctx))
            }
            now = System.currentTimeMillis()
            batteryOk = ctx.getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(ctx.packageName)
            delay(1000)
        }
    }

    val s = snap
    Surface(Modifier.fillMaxSize()) {
        if (s == null) return@Surface
        Column(
            Modifier.fillMaxSize().statusBarsPadding()
                .verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("زمان استفاده از گوشی", fontSize = 24.sp)

            if (!batteryOk) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("برای کار در پس‌زمینه، محدودیت باتری باید برداشته بشه.")
                        Button(onClick = {
                            ctx.startActivity(
                                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                    .setData(Uri.parse("package:${ctx.packageName}"))
                            )
                        }) { Text("اجازه دادن") }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    if (s.openStart > 0L) {
                        Text("جلسه‌ی فعلی (از باز شدن قفل)")
                        Text(clock(now - s.openStart), fontSize = 32.sp)
                    } else {
                        Text("گوشی الان قفل یا خاموش است")
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("مجموع امروز")
                    Text(fmt(s.totals.first().second), fontSize = 28.sp)
                }
            }

            Text("جلسه‌های امروز")
            if (s.sessions.isEmpty()) {
                Text("هنوز جلسه‌ای ثبت نشده")
            } else {
                val hm = SimpleDateFormat("HH:mm", fa)
                s.sessions.asReversed().take(30).forEachIndexed { idx, (a, b) ->
                    val label = if (s.openStart > 0L && idx == 0)
                        "${hm.format(Date(a))} تا اکنون"
                    else "${hm.format(Date(a))} تا ${hm.format(Date(b))}"
                    Row(Modifier.fillMaxWidth()) {
                        Text(label, Modifier.weight(1f))
                        Text(fmt(b - a))
                    }
                }
            }

            Text("۷ روز اخیر")
            val dayFmt = SimpleDateFormat("EEEE d/M", fa)
            s.totals.forEach { (day, ms) ->
                Row(Modifier.fillMaxWidth()) {
                    Text(dayFmt.format(Date(day)), Modifier.weight(1f))
                    Text(fmt(ms))
                }
            }
        }
    }
}
