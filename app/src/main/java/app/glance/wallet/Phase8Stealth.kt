package app.glance.wallet

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.glance.wallet.core.security.SecurityPreferencesStore
import app.glance.wallet.core.security.StealthMode
import app.glance.wallet.core.security.StealthNote
import java.util.UUID
import kotlinx.coroutines.launch
import kotlin.math.sqrt

internal fun calculatorUnlockTriggered(tokens: List<String>): Boolean = tokens.takeLast(3) == listOf("5", "×", "=")
internal fun isValidNotesCodeword(value: String): Boolean = value.length in 4..64
internal fun notesCodewordMatches(codeword: String, candidate: String): Boolean = codeword == candidate
internal fun streetMaskedSats(): String = "•••• sats"
internal fun streetMaskedFiat(): String = "••••"

/** Keeps a stealth launcher behind its disguise whenever the activity is backgrounded. */
internal class StealthDisguiseGate(private val launchMode: StealthMode) {
    var isGlanceOpen by mutableStateOf(launchMode == StealthMode.OFF)
        private set

    fun openGlance() {
        isGlanceOpen = true
    }

    fun reenterDisguise() {
        isGlanceOpen = launchMode == StealthMode.OFF
    }
}

/** Pure state makes the sensor threshold/window behavior independently testable. */
internal data class ShakeState(val firstPeakMillis: Long? = null, val cooldownUntilMillis: Long = 0, val toggled: Boolean = false) {
    fun recordPeak(atMillis: Long): ShakeState = when {
        atMillis < cooldownUntilMillis -> copy(toggled = false)
        firstPeakMillis == null || atMillis - firstPeakMillis > SHAKE_WINDOW_MILLIS -> copy(firstPeakMillis = atMillis, toggled = false)
        else -> ShakeState(cooldownUntilMillis = atMillis + SHAKE_COOLDOWN_MILLIS, toggled = true)
    }
}

private const val SHAKE_THRESHOLD = 12f
private const val SHAKE_WINDOW_MILLIS = 500L
private const val SHAKE_COOLDOWN_MILLIS = 1_000L

internal class StreetModeShakeDetector(context: Context, private val onToggle: () -> Unit) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var gravity = floatArrayOf(0f, 0f, 0f)
    private var state = ShakeState()

    fun start() { manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) } }
    fun stop() { manager.unregisterListener(this) }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    override fun onSensorChanged(event: SensorEvent) {
        gravity = FloatArray(3) { index -> gravity[index] * 0.8f + event.values[index] * 0.2f }
        val linear = FloatArray(3) { index -> event.values[index] - gravity[index] }
        val magnitude = sqrt(linear.sumOf { (it * it).toDouble() }).toFloat()
        if (magnitude >= SHAKE_THRESHOLD) {
            state = state.recordPeak(System.currentTimeMillis())
            if (state.toggled) onToggle()
        }
    }
}

@Composable internal fun StreetModeShakeEffect(enabled: Boolean, onToggle: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    DisposableEffect(enabled) {
        val detector = StreetModeShakeDetector(context, onToggle)
        if (enabled) detector.start()
        onDispose(detector::stop)
    }
}

internal fun applyLauncherIdentity(context: Context, mode: StealthMode) {
    val packageManager = context.packageManager
    val components = mapOf(
        StealthMode.OFF to "GlanceLauncherAlias",
        StealthMode.CALCULATOR to "CalculatorLauncherAlias",
        StealthMode.NOTES to "NotesLauncherAlias",
    )
    components.forEach { (candidate, className) ->
        packageManager.setComponentEnabledSetting(
            ComponentName(context.packageName, "${context.packageName}.$className"),
            if (candidate == mode) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }
}

@Composable internal fun CalculatorDisguise(onOpenGlance: () -> Unit) {
    var expression by remember { mutableStateOf("") }
    var display by remember { mutableStateOf("0") }
    fun press(token: String) {
        expression = if (token == "C") "" else expression + token
        if (token == "=") {
            if (calculatorUnlockTriggered(expression.dropLast(1).chunkedTokens() + "=")) { onOpenGlance(); return }
            display = simpleCalculation(expression.dropLast(1)) ?: "Error"
            expression = ""
        } else display = if (expression.isBlank()) "0" else expression
    }
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(display, style = MaterialTheme.typography.displayMedium)
        listOf(listOf("C", "÷", "×", "−"), listOf("7", "8", "9", "+"), listOf("4", "5", "6", "+"), listOf("1", "2", "3", "="), listOf("0", ".")).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) { row.forEach { token -> Button(onClick = { press(token) }, modifier = Modifier.widthIn(min = 64.dp)) { Text(token) } } }
        }
    }
}

private fun String.chunkedTokens(): List<String> = map { if (it == '×') "×" else it.toString() }
private fun simpleCalculation(input: String): String? = runCatching {
    val operator = input.firstOrNull { it in "+−×÷" }
    if (operator == null) return@runCatching input.toDouble().toString()
    val parts = input.split(operator)
    require(parts.size == 2)
    val left = parts[0].toDouble(); val right = parts[1].toDouble()
    val answer = when (operator) { '+' -> left + right; '−' -> left - right; '×' -> left * right; else -> left / right }
    if (answer % 1.0 == 0.0) answer.toLong().toString() else answer.toString()
}.getOrNull()

@Composable internal fun NotesDisguise(preferences: SecurityPreferencesStore, notes: List<StealthNote>, codeword: String?, onOpenGlance: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var editingId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Notes", style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(text, { text = it }, label = { Text("New note") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            val value = text.trim()
            if (codeword != null && notesCodewordMatches(codeword, value)) onOpenGlance()
            else if (value.isNotBlank()) {
                val now = System.currentTimeMillis()
                scope.launch { preferences.update { current ->
                    val replaced = editingId?.let { id -> current.stealthNotes.map { note -> if (note.id == id) note.copy(body = value, updatedAtMillis = now) else note } }
                    val updated = replaced ?: (current.stealthNotes + StealthNote(UUID.randomUUID().toString(), value, now, now))
                    current.copy(stealthNotes = updated)
                } }
                text = ""
                editingId = null
            }
        }) { Text("Save") }
        LazyColumn { items(notes, key = { it.id }) { note ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(note.body, Modifier.clickable { text = note.body; editingId = note.id }, style = MaterialTheme.typography.bodyLarge)
                Button(onClick = { scope.launch { preferences.update { it.copy(stealthNotes = it.stealthNotes.filterNot { saved -> saved.id == note.id }) } } }) { Text("Delete") }
            }
        } }
    }
}

@Composable internal fun StealthModeDialog(
    context: Context,
    preferences: SecurityPreferencesStore,
    currentMode: StealthMode,
    currentCodeword: String?,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(currentMode) }
    var codeword by remember { mutableStateOf(currentCodeword.orEmpty()) }
    var confirmation by remember { mutableStateOf(currentCodeword.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Stealth mode") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Choose the only launcher identity shown on this device.")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(StealthMode.OFF to "Glance", StealthMode.CALCULATOR to "Calculator", StealthMode.NOTES to "Notes").forEach { (mode, label) -> Button(onClick = { selected = mode }) { Text(if (selected == mode) "✓ $label" else label) } }
            }
            if (selected == StealthMode.NOTES) {
                OutlinedTextField(codeword, { codeword = it }, label = { Text("Unlock codeword") }, singleLine = true)
                OutlinedTextField(confirmation, { confirmation = it }, label = { Text("Confirm codeword") }, singleLine = true)
            }
            error?.let { Text(it, color = GlanceWarning) }
        } },
        confirmButton = { Button(onClick = {
            val normalized = codeword.trim()
            if (selected == StealthMode.NOTES && (!isValidNotesCodeword(normalized) || normalized != confirmation)) {
                error = "Use the same 4–64 character codeword twice."
            } else scope.launch {
                preferences.update { it.copy(stealthMode = selected, notesCodeword = if (selected == StealthMode.NOTES) normalized else it.notesCodeword) }
                applyLauncherIdentity(context, selected)
                onDismiss()
            }
        }) { Text("Save") } },
        dismissButton = { Button(onClick = onDismiss) { Text("Cancel") } },
    )
}
