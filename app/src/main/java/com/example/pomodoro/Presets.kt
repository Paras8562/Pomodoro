package com.example.pomodoro

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

fun Opts.summary(): String = buildString {
    append(fmt(total))
    if (brk == true) {
        append(" · $f/$s min")
        if (useLong) append(" · long $l every $every")
        if (flow) append(" · Flow State")
    } else append(" · no breaks")
}

@Composable
fun PresetsScreen(vm: SessionViewModel, ui: Ui, onStarted: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val presets by vm.presets.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Preset?>(null) }
    var creating by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Preset?>(null) }

    fun start(p: Preset) {
        if (vm.hasActiveSession()) confirm = p else { vm.startPreset(p); onStarted() }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Title("Presets", "Tap a preset to start it. Use the pencil to edit it.")
        if (presets.isEmpty()) {
            Text(
                "No presets yet. Save the setups you use most, like “Deep work” or “Quick sprint”, and start them in one tap.",
                color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 20.dp),
            )
        }
        presets.forEach { p ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(22.dp)).background(cs.surfaceContainer)
                    .clickable { start(p) }.padding(start = 20.dp, top = 14.dp, bottom = 14.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(p.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(p.opts.summary(), fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                }
                IconButton(onClick = { editing = p }) { Icon(Icons.Rounded.Edit, "Edit ${p.name}") }
            }
        }
        Button(onClick = { creating = true }, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)) {
            Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("New preset")
        }
    }

    if (creating) {
        PresetEditor(null, ui.opts, { name, o -> vm.savePreset(null, name, o); creating = false }, null) { creating = false }
    }
    editing?.let { p ->
        PresetEditor(
            p, p.opts, { name, o -> vm.savePreset(p.id, name, o); editing = null },
            { vm.deletePreset(p.id); editing = null },
        ) { editing = null }
    }
    confirm?.let { p ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Replace current session?") },
            text = { Text("Starting “${p.name}” will replace the session you have in progress.") },
            confirmButton = { TextButton(onClick = { confirm = null; vm.startPreset(p); onStarted() }) { Text("Start") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetEditor(initial: Preset?, base: Opts, onSave: (String, Opts) -> Unit, onDelete: (() -> Unit)?, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var o by remember { mutableStateOf(initial?.opts ?: base.copy(brk = true)) }
    var confirmDelete by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = cs.surfaceContainer,
    ) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).imePadding().verticalScroll(rememberScrollState())) {
            Text(if (initial == null) "New preset" else "Edit preset", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 16.dp))
            OutlinedTextField(
                value = name, onValueChange = { name = it.take(24) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text("Label") }, placeholder = { Text("e.g. Deep work") },
            )
            Spacer(Modifier.height(16.dp))
            SliderRow("Total work time", fmt(o.total), o.total, 10, 540, 5) { v -> o = o.copy(total = v) }
            SwitchRow("Plan breaks", o.brk == true) { v -> o = o.copy(brk = v) }
            if (o.brk == true) {
                SliderRow("Focus length", "${o.f} min", o.f, 5, 90, 5) { v -> o = o.copy(f = v) }
                SliderRow("Short break", "${o.s} min", o.s, 1, 30, 1) { v -> o = o.copy(s = v) }
                SwitchRow("Include long breaks", o.useLong) { v -> o = o.copy(useLong = v) }
                if (o.useLong) {
                    SliderRow("Long break length", "${o.l} min", o.l, 5, 60, 5) { v -> o = o.copy(l = v) }
                    SliderRow("Long break after", "${o.every} focus sessions", o.every, 2, 8, 1) { v -> o = o.copy(every = v) }
                }
                SwitchRow("Flow State", o.flow) { v -> o = o.copy(flow = v) }
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onDelete != null) {
                    TextButton(
                        onClick = { if (confirmDelete) onDelete() else confirmDelete = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = cs.error),
                    ) { Text(if (confirmDelete) "Tap again to delete" else "Delete") }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onSave(name.trim(), o.copy(brk = o.brk == true)) }, enabled = name.isNotBlank()) { Text("Save") }
            }
        }
    }
}
