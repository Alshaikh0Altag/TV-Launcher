package com.arabrock.tvlauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arabrock.tvlauncher.data.SettingsStore
import com.arabrock.tvlauncher.security.PinManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class PinMode { VERIFY, SETUP, CHANGE, RECOVERY }

/**
 * PIN entry with the D-pad (number pad) or the remote's number keys.
 * VERIFY -> onSuccess. SETUP/CHANGE -> enter + confirm. RECOVERY -> recovery code, then new PIN.
 */
@Composable
fun PinScreen(initialMode: PinMode, onSuccess: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val pins = remember { PinManager.get(context) }
    val settings = remember { SettingsStore.get(context) }
    val scope = rememberCoroutineScope()

    var mode by remember { mutableStateOf(initialMode) }
    var entry by remember { mutableStateOf("") }
    var firstEntry by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var recoveryCodeToShow by remember { mutableStateOf<String?>(null) }
    var recoveryInput by remember { mutableStateOf("") }
    var lockout by remember { mutableStateOf(pins.lockoutRemainingSec()) }

    LaunchedEffect(lockout) {
        if (lockout > 0) { delay(1000); lockout = pins.lockoutRemainingSec() }
    }

    val title = when (mode) {
        PinMode.VERIFY -> "أدخل رمز المسؤول"
        PinMode.SETUP -> if (firstEntry == null) "إنشاء رمز مسؤول جديد (4–8 أرقام)" else "أعد إدخال الرمز للتأكيد"
        PinMode.CHANGE -> if (firstEntry == null) "أدخل الرمز الجديد (4–8 أرقام)" else "أعد إدخال الرمز الجديد"
        PinMode.RECOVERY -> "أدخل رمز الاسترداد (12 حرفاً)"
    }

    fun submit() {
        if (busy) return
        when (mode) {
            PinMode.VERIFY -> {
                if (entry.length < 4) { message = "الرمز من 4 إلى 8 أرقام"; return }
                busy = true
                val pin = entry
                scope.launch {
                    val r = withContext(Dispatchers.Default) { pins.verify(pin, settings.value.failDelayBaseSec) }
                    busy = false
                    entry = ""
                    when (r) {
                        is PinManager.Result.Ok -> { pins.unlockFor(settings.value.lockTimeoutSec); onSuccess() }
                        is PinManager.Result.Wrong -> {
                            message = if (r.lockoutSec > 0) "رمز خاطئ. الانتظار ${r.lockoutSec} ثانية" else "رمز خاطئ"
                            lockout = pins.lockoutRemainingSec()
                        }
                        is PinManager.Result.LockedOut -> { lockout = r.seconds }
                    }
                }
            }
            PinMode.SETUP, PinMode.CHANGE -> {
                if (entry.length !in 4..8) { message = "الرمز يجب أن يكون من 4 إلى 8 أرقام"; return }
                val first = firstEntry
                if (first == null) { firstEntry = entry; entry = ""; message = null; return }
                if (first != entry) { message = "الرمزان غير متطابقين، ابدأ من جديد"; firstEntry = null; entry = ""; return }
                busy = true
                val pin = entry
                val isChange = mode == PinMode.CHANGE && pins.isPinSet
                scope.launch {
                    val code = withContext(Dispatchers.Default) {
                        if (isChange) { pins.changePin(pin); null } else pins.setPin(pin)
                    }
                    busy = false
                    entry = ""
                    pins.unlockFor(settings.value.lockTimeoutSec)
                    if (code != null) recoveryCodeToShow = code else onSuccess()
                }
            }
            PinMode.RECOVERY -> Unit
        }
    }

    val firstKey = remember { FocusRequester() }
    LaunchedEffect(mode, recoveryCodeToShow) { runCatching { firstKey.requestFocus() } }

    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.Background)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || mode == PinMode.RECOVERY || recoveryCodeToShow != null) return@onPreviewKeyEvent false
                val digit = when (e.key) {
                    Key.Zero, Key.NumPad0 -> "0"; Key.One, Key.NumPad1 -> "1"; Key.Two, Key.NumPad2 -> "2"
                    Key.Three, Key.NumPad3 -> "3"; Key.Four, Key.NumPad4 -> "4"; Key.Five, Key.NumPad5 -> "5"
                    Key.Six, Key.NumPad6 -> "6"; Key.Seven, Key.NumPad7 -> "7"; Key.Eight, Key.NumPad8 -> "8"
                    Key.Nine, Key.NumPad9 -> "9"; else -> null
                }
                when {
                    digit != null -> { if (entry.length < 8) entry += digit; true }
                    e.key == Key.Back && entry.isNotEmpty() -> { entry = entry.dropLast(1); true }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val shown = recoveryCodeToShow
        if (shown != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("تم حفظ الرمز", color = Palette.Accent, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text("رمز الاسترداد — اكتبه واحتفظ به في مكان آمن. لن يظهر مرة أخرى:", color = Palette.Text, fontSize = 16.sp, modifier = Modifier.padding(top = 16.dp))
                Text(shown.chunked(4).joinToString("-"), color = Palette.Warning, fontSize = 34.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(20.dp))
                TvButton("حفظته، متابعة", Modifier.focusRequester(firstKey)) { onSuccess() }
            }
            return@Box
        }

        if (mode == PinMode.RECOVERY) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, color = Palette.Text, fontSize = 22.sp)
                BasicTextField(
                    value = recoveryInput,
                    onValueChange = { recoveryInput = it.take(16) },
                    singleLine = true,
                    textStyle = TextStyle(color = Palette.Text, fontSize = 24.sp),
                    modifier = Modifier
                        .padding(20.dp)
                        .width(420.dp)
                        .background(Palette.Panel, RoundedCornerShape(8.dp))
                        .border(2.dp, Palette.Accent, RoundedCornerShape(8.dp))
                        .padding(12.dp)
                        .focusRequester(firstKey),
                )
                if (lockout > 0) Text("محاولات كثيرة. انتظر $lockout ثانية", color = Palette.Danger, fontSize = 15.sp)
                message?.let { Text(it, color = Palette.Danger, fontSize = 15.sp) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 12.dp)) {
                    TvButton("تحقق") {
                        val code = recoveryInput
                        scope.launch {
                            val r = withContext(Dispatchers.Default) { pins.verifyRecovery(code, settings.value.failDelayBaseSec) }
                            when (r) {
                                is PinManager.Result.Ok -> { message = null; firstEntry = null; mode = PinMode.SETUP }
                                is PinManager.Result.Wrong -> { message = "رمز الاسترداد غير صحيح"; lockout = pins.lockoutRemainingSec() }
                                is PinManager.Result.LockedOut -> lockout = r.seconds
                            }
                        }
                    }
                    TvButton("إلغاء") { onCancel() }
                }
            }
            return@Box
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = Palette.Text, fontSize = 22.sp)
            Row(Modifier.padding(vertical = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                for (i in 0 until maxOf(4, entry.length)) {
                    Box(
                        Modifier.size(20.dp).background(
                            if (i < entry.length) Palette.Accent else Palette.PanelHigh, CircleShape
                        )
                    )
                }
            }
            if (lockout > 0) {
                Text("محاولات خاطئة كثيرة. حاول بعد $lockout ثانية", color = Palette.Danger, fontSize = 15.sp)
            } else {
                message?.let { Text(it, color = Palette.Danger, fontSize = 15.sp) }
            }
            if (busy) Text("جارٍ التحقق…", color = Palette.Muted, fontSize = 14.sp)

            val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "✓"))
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                rows.forEachIndexed { r, row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEachIndexed { c, k ->
                            val mod = Modifier.width(90.dp).let { if (r == 0 && c == 0) it.focusRequester(firstKey) else it }
                            TvButton(k, mod, fontSize = 22.sp) {
                                when (k) {
                                    "⌫" -> entry = entry.dropLast(1)
                                    "✓" -> if (lockout <= 0 || mode != PinMode.VERIFY) submit()
                                    else -> if (entry.length < 8) entry += k
                                }
                            }
                        }
                    }
                }
            }
            Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton("إلغاء") { onCancel() }
                if (mode == PinMode.VERIFY) TvButton("نسيت الرمز") { message = null; mode = PinMode.RECOVERY }
            }
        }
    }
}
