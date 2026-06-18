package com.lifebattery

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.*

// ── Data ──────────────────────────────────────────────────────────────────────

data class LogEntry(val label: String, val delta: Float, val time: String)

data class Action(val label: String, val delta: Float) {
    val isDrain get() = delta < 0
    fun encode() = "$label|$delta"
    companion object {
        fun decode(s: String): Action? {
            val p = s.split("|"); if (p.size != 2) return null
            val d = p[1].toFloatOrNull() ?: return null
            return Action(p[0], d)
        }
    }
}

val DEFAULT_ACTIONS = listOf(
    Action("Ate Outside", -15f),
    Action("5000+ Steps", +2f),
    Action("Meditation", +1f),
    Action("Hit Gym", +2f),
    Action("Read Book", +1f),
)

fun getTodayStr(): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

fun getNowTimeStr(): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

fun saveLog(prefs: SharedPreferences, label: String, delta: Float) {
    val key = "log_${getTodayStr()}"
    val existing = prefs.getString(key, "") ?: ""
    val newEntry = "${label}|${delta}|${getNowTimeStr()}"
    val updated = if (existing.isEmpty()) newEntry else "$existing\n$newEntry"
    prefs.edit().putString(key, updated).apply()
}

fun loadTodayLog(prefs: SharedPreferences): List<LogEntry> {
    val raw = prefs.getString("log_${getTodayStr()}", "") ?: ""
    if (raw.isEmpty()) return emptyList()
    return raw.split("\n").mapNotNull { line ->
        val parts = line.split("|")
        if (parts.size == 3) LogEntry(parts[0], parts[1].toFloatOrNull() ?: 0f, parts[2]) else null
    }.reversed()
}

fun loadActions(prefs: SharedPreferences): List<Action> {
    val raw = prefs.getString("actions", null)
    if (raw == null) return DEFAULT_ACTIONS
    if (raw.isEmpty()) return emptyList()
    return raw.split("\n").mapNotNull { Action.decode(it) }
}

fun saveActions(prefs: SharedPreferences, list: List<Action>) {
    prefs.edit().putString("actions", list.joinToString("\n") { it.encode() }).apply()
}

// ── Activity ──────────────────────────────────────────────────────────────────

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppRoot() }
    }
}

@Composable
fun AppRoot() {
    var screen by remember { mutableStateOf("home") }
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("life_battery_v1", Context.MODE_PRIVATE) }
    var actions by remember { mutableStateOf(loadActions(prefs)) }
    var resetTick by remember { mutableIntStateOf(0) }

    when (screen) {
        "home" -> LifeBatteryScreen(
            prefs = prefs,
            actions = actions,
            resetTick = resetTick,
            onOpenSettings = { screen = "settings" }
        )
        "settings" -> SettingsScreen(
            prefs = prefs,
            actions = actions,
            onChange = {
                actions = it
                saveActions(prefs, it)
            },
            onReset = {
                resetAppState(prefs)
                LifeBatteryWidget.refreshAll(context)
                resetTick++
            },
            onBack = { screen = "home" }
        )
    }
}

fun resetAppState(prefs: SharedPreferences) {
    val editor = prefs.edit()
    editor.putFloat("battery", 100f)
    editor.remove("last_drain_day")
    prefs.all.keys.filter { it.startsWith("log_") }.forEach { editor.remove(it) }
    editor.apply()
}

// ── Colors ────────────────────────────────────────────────────────────────────

val BgDark = Color(0xFF0F0F1A)
val CardDark = Color(0xFF1C1C2E)
val Green = Color(0xFF4ADE80)
val Yellow = Color(0xFFFBBF24)
val Orange = Color(0xFFF97316)
val Red = Color(0xFFEF4444)
val TextPrimary = Color(0xFFF1F5F9)
val TextMuted = Color(0xFF94A3B8)

fun batteryColor(pct: Float) = when {
    pct >= 60f -> Green
    pct >= 35f -> Yellow
    pct >= 15f -> Orange
    else -> Red
}

// ── Home Screen ───────────────────────────────────────────────────────────────

@Composable
fun LifeBatteryScreen(
    prefs: SharedPreferences,
    actions: List<Action>,
    resetTick: Int,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    var battery by remember(resetTick) { mutableFloatStateOf(prefs.getFloat("battery", 100f)) }
    var todayLog by remember(resetTick) { mutableStateOf(loadTodayLog(prefs)) }
    var snackMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val today = getTodayStr()
        val lastDrain = prefs.getString("last_drain_day", "")
        if (lastDrain != today) {
            val drained = (battery - 10f).coerceAtLeast(0f)
            battery = drained
            prefs.edit()
                .putFloat("battery", drained)
                .putString("last_drain_day", today)
                .apply()
            saveLog(prefs, "Daily drain", -10f)
            todayLog = loadTodayLog(prefs)
            LifeBatteryWidget.refreshAll(context)
        }
    }

    fun applyChange(label: String, delta: Float) {
        val prev = battery
        val next = (battery + delta).coerceIn(0f, 100f)
        battery = next
        prefs.edit().putFloat("battery", next).apply()
        saveLog(prefs, label, next - prev)
        todayLog = loadTodayLog(prefs)
        LifeBatteryWidget.refreshAll(context)
        val sign = if (delta > 0) "+" else ""
        snackMsg = "$label  $sign${delta.toInt()}%  →  ${next.toInt()}%"
    }

    val animBattery by animateFloatAsState(
        targetValue = battery / 100f,
        animationSpec = tween(durationMillis = 600),
        label = "battery"
    )
    val color = batteryColor(battery)
    val drains = actions.filter { it.isDrain }
    val recharges = actions.filter { !it.isDrain }

    Box(Modifier.fillMaxSize().background(BgDark)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Spacer(Modifier.height(8.dp))

            // Top bar with settings
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Life Battery", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Text("Keep your energy charged", fontSize = 12.sp, color = TextMuted)
                }
                IconCircleButton(label = "⚙", onClick = onOpenSettings)
            }

            Spacer(Modifier.height(20.dp))

            BatteryWidget(animBattery, battery, color)

            Spacer(Modifier.height(28.dp))

            if (drains.isNotEmpty()) {
                SectionLabel("Drains")
                Spacer(Modifier.height(8.dp))
                ActionGrid(drains) { applyChange(it.label, it.delta) }
                Spacer(Modifier.height(20.dp))
            }

            if (recharges.isNotEmpty()) {
                SectionLabel("Recharges")
                Spacer(Modifier.height(8.dp))
                ActionGrid(recharges) { applyChange(it.label, it.delta) }
                Spacer(Modifier.height(20.dp))
            }

            if (actions.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = CardDark),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No actions yet", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text("Tap ⚙ to add drains & recharges", color = TextMuted, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(20.dp))
            }

            if (todayLog.isNotEmpty()) {
                SectionLabel("Today's Log")
                Spacer(Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = CardDark),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        todayLog.forEach { LogRow(it) }
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }

        snackMsg?.let { msg ->
            LaunchedEffect(msg) {
                kotlinx.coroutines.delay(2000)
                snackMsg = null
            }
            Box(
                Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(16.dp).padding(bottom = 24.dp)
            ) {
                Text(
                    msg, color = TextPrimary, fontSize = 14.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF2D2D44)).padding(14.dp)
                )
            }
        }
    }
}

// ── Settings Screen ───────────────────────────────────────────────────────────

@Composable
fun SettingsScreen(
    prefs: SharedPreferences,
    actions: List<Action>,
    onChange: (List<Action>) -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit
) {
    var label by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var isDrain by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showResetDialog by remember { mutableStateOf(false) }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            containerColor = CardDark,
            title = { Text("Reset App?", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "This will set battery back to 100% and clear all logs. Your custom actions will stay.",
                    color = TextMuted
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onReset()
                    showResetDialog = false
                }) {
                    Text("Reset", color = Red, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("Cancel", color = TextMuted)
                }
            }
        )
    }

    Box(Modifier.fillMaxSize().background(BgDark)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconCircleButton(label = "←", onClick = onBack)
                Spacer(Modifier.width(12.dp))
                Text("Settings", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            }

            Spacer(Modifier.height(20.dp))

            SectionLabel("Add New Action")
            Spacer(Modifier.height(10.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = CardDark),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        label = { Text("Name (e.g. Yoga)") },
                        singleLine = true,
                        colors = darkFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = amount,
                        onValueChange = { amount = it.filter { c -> c.isDigit() } },
                        label = { Text("Amount %") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = darkFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ToggleChip("Recharge (+)", !isDrain, Green) { isDrain = false }
                        ToggleChip("Drain (-)", isDrain, Red) { isDrain = true }
                    }

                    error?.let { Text(it, color = Red, fontSize = 12.sp) }

                    Button(
                        onClick = {
                            val n = label.trim()
                            val a = amount.toIntOrNull()
                            when {
                                n.isEmpty() -> error = "Enter a name"
                                a == null || a <= 0 -> error = "Enter a positive amount"
                                actions.any { it.label.equals(n, ignoreCase = true) } -> error = "Name already exists"
                                else -> {
                                    val delta = if (isDrain) -a.toFloat() else a.toFloat()
                                    onChange(actions + Action(n, delta))
                                    label = ""; amount = ""; error = null
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = if (isDrain) Red else Green),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Text("Add Action", color = BgDark, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            SectionLabel("Your Actions")
            Spacer(Modifier.height(8.dp))

            if (actions.isEmpty()) {
                Text("No actions. Add some above.", color = TextMuted, fontSize = 13.sp)
            } else {
                Card(
                    colors = CardDefaults.cardColors(containerColor = CardDark),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        actions.forEachIndexed { i, act ->
                            ActionEditRow(act) { onChange(actions.toMutableList().also { it.removeAt(i) }) }
                        }
                    }
                }
            }

            Spacer(Modifier.height(28.dp))

            SectionLabel("Danger Zone")
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { showResetDialog = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    contentColor = Red
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, Red),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Text("Reset App", color = Red, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Resets battery to 100% and clears logs. Keeps your custom actions.",
                color = TextMuted,
                fontSize = 11.sp
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
fun ActionEditRow(action: Action, onDelete: () -> Unit) {
    val color = if (action.isDrain) Red else Green
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(color))
        Spacer(Modifier.width(12.dp))
        Text(action.label, color = TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
        val sign = if (action.delta > 0) "+" else ""
        Text("$sign${action.delta.toInt()}%", color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(12.dp))
        TextButton(onClick = onDelete) {
            Text("✕", color = TextMuted, fontSize = 18.sp)
        }
    }
}

@Composable
fun ToggleChip(text: String, selected: Boolean, color: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) color else Color.Transparent
        ),
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) color else TextMuted),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(
            text,
            color = if (selected) BgDark else TextMuted,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp
        )
    }
}

@Composable
fun darkFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    focusedBorderColor = Green,
    unfocusedBorderColor = TextMuted,
    focusedLabelColor = Green,
    unfocusedLabelColor = TextMuted,
    cursorColor = Green
)

// ── Battery Widget ────────────────────────────────────────────────────────────

@Composable
fun BatteryWidget(fillFraction: Float, battery: Float, color: Color) {
    val pct = battery.toInt()
    val statusText = when {
        battery >= 80f -> "Excellent"
        battery >= 60f -> "Good"
        battery >= 40f -> "Okay"
        battery >= 20f -> "Low"
        else -> "Critical"
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.BottomCenter) {
            Box(
                Modifier.width(120.dp).height(200.dp)
                    .border(3.dp, TextMuted, RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(12.dp))
            ) {
                Box(
                    Modifier.fillMaxWidth().fillMaxHeight(fillFraction)
                        .align(Alignment.BottomCenter)
                        .background(color.copy(alpha = 0.85f))
                )
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "$pct%", fontSize = 32.sp, fontWeight = FontWeight.ExtraBold,
                        color = if (fillFraction > 0.45f) BgDark else TextPrimary
                    )
                }
            }
            Box(
                Modifier.width(40.dp).height(10.dp).offset(y = (-198).dp)
                    .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                    .background(TextMuted)
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(statusText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = color)
        Text("Daily drain: -10% / day", fontSize = 12.sp, color = TextMuted, modifier = Modifier.padding(top = 2.dp))
    }
}

// ── Action Grid (2 per row) ───────────────────────────────────────────────────

@Composable
fun ActionGrid(items: List<Action>, onTap: (Action) -> Unit) {
    val rows = items.chunked(2)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { act -> ActionBtn(act) { onTap(act) } }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun RowScope.ActionBtn(action: Action, onClick: () -> Unit) {
    val color = if (action.isDrain) Red else Green
    val sign = if (action.delta > 0) "+" else ""
    Button(
        onClick = onClick,
        modifier = Modifier.weight(1f).height(64.dp),
        colors = ButtonDefaults.buttonColors(containerColor = CardDark),
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(8.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(action.label, fontSize = 13.sp, color = TextPrimary, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
            Text("$sign${action.delta.toInt()}%", fontSize = 12.sp, color = color, fontWeight = FontWeight.Bold)
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

@Composable
fun SectionLabel(text: String) {
    Text(
        text.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold,
        color = TextMuted, letterSpacing = 1.sp, modifier = Modifier.fillMaxWidth()
    )
}

@Composable
fun IconCircleButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.size(40.dp),
        colors = ButtonDefaults.buttonColors(containerColor = CardDark),
        shape = RoundedCornerShape(50),
        contentPadding = PaddingValues(0.dp)
    ) {
        Text(label, fontSize = 18.sp, color = TextPrimary)
    }
}

@Composable
fun LogRow(entry: LogEntry) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val dotColor = if (entry.delta >= 0) Green else Red
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(dotColor))
            Text(entry.label, fontSize = 13.sp, color = TextPrimary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            val sign = if (entry.delta >= 0) "+" else ""
            val deltaColor = if (entry.delta >= 0) Green else Red
            Text("$sign${entry.delta.toInt()}%", fontSize = 13.sp, color = deltaColor, fontWeight = FontWeight.Bold)
            Text(entry.time, fontSize = 11.sp, color = TextMuted)
        }
    }
}
