package app.glance.wallet

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import app.glance.wallet.core.security.SecurityPreferencesStore
import app.glance.wallet.core.security.StealthMode
import app.glance.wallet.core.security.StealthNote
import java.util.UUID
import kotlinx.coroutines.launch
import kotlin.math.sqrt

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

internal enum class CalculatorOperation(val symbol: String) {
    ADD("+"),
    SUBTRACT("-"),
    MULTIPLY("x"),
    DIVIDE("/"),
}

internal sealed interface CalculatorAction {
    data class Digit(val value: Int) : CalculatorAction
    data class Operation(val value: CalculatorOperation) : CalculatorAction
    data object Decimal : CalculatorAction
    data object Delete : CalculatorAction
    data object Clear : CalculatorAction
    data object Calculate : CalculatorAction
}

internal data class CalculatorState(
    val number1: String = "",
    val operation: CalculatorOperation? = null,
    val number2: String = "",
    val result: String? = null,
    val consecutiveEqualsTaps: Int = 0,
) {
    val display: String get() = result ?: (number1 + (operation?.symbol.orEmpty()) + number2).ifBlank { "0" }

    fun reduce(action: CalculatorAction): CalculatorState = when (action) {
        is CalculatorAction.Digit -> enterDigit(action.value).resetEqualsTapCount()
        is CalculatorAction.Operation -> enterOperation(action.value).resetEqualsTapCount()
        CalculatorAction.Decimal -> enterDecimal().resetEqualsTapCount()
        CalculatorAction.Delete -> delete().resetEqualsTapCount()
        CalculatorAction.Clear -> CalculatorState()
        CalculatorAction.Calculate -> calculate().copy(consecutiveEqualsTaps = consecutiveEqualsTaps + 1)
    }

    private fun resetEqualsTapCount(): CalculatorState = copy(consecutiveEqualsTaps = 0)

    private fun enterDigit(digit: Int): CalculatorState {
        require(digit in 0..9)
        val base = if (result != null) CalculatorState() else this
        return if (base.operation == null) {
            if (base.number1.length >= MAX_OPERAND_LENGTH) base else base.copy(number1 = base.number1 + digit)
        } else {
            if (base.number2.length >= MAX_OPERAND_LENGTH) base else base.copy(number2 = base.number2 + digit)
        }
    }

    private fun enterOperation(nextOperation: CalculatorOperation): CalculatorState = when {
        number1.isBlank() -> this
        number2.isNotBlank() -> calculate().enterOperation(nextOperation)
        else -> copy(operation = nextOperation, result = null)
    }

    private fun enterDecimal(): CalculatorState = when {
        result != null -> this
        operation == null && number1.isNotBlank() && '.' !in number1 -> copy(number1 = "$number1.")
        operation != null && number2.isNotBlank() && '.' !in number2 -> copy(number2 = "$number2.")
        else -> this
    }

    private fun delete(): CalculatorState = when {
        result != null -> CalculatorState()
        number2.isNotBlank() -> copy(number2 = number2.dropLast(1))
        operation != null -> copy(operation = null)
        number1.isNotBlank() -> copy(number1 = number1.dropLast(1))
        else -> this
    }

    private fun calculate(): CalculatorState {
        val left = number1.toDoubleOrNull() ?: return this
        val right = number2.toDoubleOrNull() ?: return this
        val value = when (operation) {
            CalculatorOperation.ADD -> left + right
            CalculatorOperation.SUBTRACT -> left - right
            CalculatorOperation.MULTIPLY -> left * right
            CalculatorOperation.DIVIDE -> if (right == 0.0) null else left / right
            null -> return this
        }
        val formatted = value?.takeIf(Double::isFinite)?.let(::formatCalculatorResult) ?: "Error"
        return copy(number1 = formatted, operation = null, number2 = "", result = formatted)
    }

    private companion object {
        const val MAX_OPERAND_LENGTH = 8
    }
}

private fun formatCalculatorResult(value: Double): String =
    java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

internal fun calculatorUnlockTriggered(state: CalculatorState, action: CalculatorAction): Boolean =
    action == CalculatorAction.Calculate &&
        state.consecutiveEqualsTaps == 4

@Composable internal fun CalculatorDisguise(onOpenGlance: () -> Unit) {
    var state by remember { mutableStateOf(CalculatorState()) }
    fun press(action: CalculatorAction) {
        if (calculatorUnlockTriggered(state, action)) {
            onOpenGlance()
        } else {
            state = state.reduce(action)
        }
    }
    Column(
        modifier = Modifier.fillMaxSize().background(Color.Black).padding(8.dp),
        verticalArrangement = Arrangement.Bottom,
    ) {
        Text(
            text = state.display,
            modifier = Modifier.fillMaxWidth().padding(vertical = 30.dp).semantics { contentDescription = "Calculator display ${state.display}" },
            color = Color.White,
            fontSize = 80.sp,
            fontWeight = FontWeight.Light,
            textAlign = TextAlign.End,
            maxLines = 2,
        )
        CalculatorKeyRow(
            CalculatorKey("AC", CalculatorAction.Clear, CalculatorUtilityKey, 2f),
            CalculatorKey("Del", CalculatorAction.Delete, CalculatorUtilityKey),
            CalculatorKey("/", CalculatorAction.Operation(CalculatorOperation.DIVIDE), CalculatorOperatorKey),
            onPress = ::press,
        )
        CalculatorKeyRow(
            CalculatorKey("7", CalculatorAction.Digit(7), CalculatorNumberKey), CalculatorKey("8", CalculatorAction.Digit(8), CalculatorNumberKey),
            CalculatorKey("9", CalculatorAction.Digit(9), CalculatorNumberKey), CalculatorKey("x", CalculatorAction.Operation(CalculatorOperation.MULTIPLY), CalculatorOperatorKey),
            onPress = ::press,
        )
        CalculatorKeyRow(
            CalculatorKey("4", CalculatorAction.Digit(4), CalculatorNumberKey), CalculatorKey("5", CalculatorAction.Digit(5), CalculatorNumberKey),
            CalculatorKey("6", CalculatorAction.Digit(6), CalculatorNumberKey), CalculatorKey("-", CalculatorAction.Operation(CalculatorOperation.SUBTRACT), CalculatorOperatorKey),
            onPress = ::press,
        )
        CalculatorKeyRow(
            CalculatorKey("1", CalculatorAction.Digit(1), CalculatorNumberKey), CalculatorKey("2", CalculatorAction.Digit(2), CalculatorNumberKey),
            CalculatorKey("3", CalculatorAction.Digit(3), CalculatorNumberKey), CalculatorKey("+", CalculatorAction.Operation(CalculatorOperation.ADD), CalculatorOperatorKey),
            onPress = ::press,
        )
        CalculatorKeyRow(
            CalculatorKey("0", CalculatorAction.Digit(0), CalculatorNumberKey, 2f), CalculatorKey(".", CalculatorAction.Decimal, CalculatorNumberKey),
            CalculatorKey("=", CalculatorAction.Calculate, CalculatorOperatorKey),
            onPress = ::press,
        )
    }
}

private data class CalculatorKey(val label: String, val action: CalculatorAction, val color: Color, val span: Float = 1f)

private val CalculatorNumberKey = Color.DarkGray
private val CalculatorUtilityKey = Color(0xFF818181)
private val CalculatorOperatorKey = Color(0xFFFF9800)

@Composable private fun CalculatorKeyRow(vararg keys: CalculatorKey, onPress: (CalculatorAction) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        keys.forEach { key ->
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.weight(key.span).aspectRatio(key.span).clip(CircleShape).background(key.color)
                    .clickable { onPress(key.action) }.semantics { contentDescription = "Calculator key ${key.label}" },
            ) {
                Text(key.label, color = Color.White, fontSize = 36.sp)
            }
        }
    }
}

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
