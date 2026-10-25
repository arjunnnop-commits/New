package com.yooitachiijod.glassfastboot

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun App(vm: FlashViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.addFiles(it) }
    val archivePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> u?.let { vm.importArchive(it) } }
    val sideloadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> u?.let { vm.sideload(it) } }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var tab by rememberSaveable { mutableStateOf(0) }
    val on = vm.connected && !vm.busy

    GlassBackground {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header
            Column(Modifier.padding(top = 8.dp, start = 4.dp)) {
                Text("Glass Fastboot", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text(
                    "by @yooitachiijod",
                    style = TextStyle(brush = Brush.linearGradient(listOf(Ice, Violet, Pink)), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                )
            }

            // Device
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(10.dp).background(if (vm.connected || vm.adbConnected) Color(0xFF4ADE80) else Color(0xFFF87171), CircleShape))
                    Text(
                        when {
                            vm.connected -> "Fastboot device connected"
                            vm.adbConnected -> "ADB / recovery device connected — open the Custom ROM tab"
                            else -> "Waiting for device — connect with an OTG cable"
                        },
                        color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp
                    )
                }
                if (vm.connected) {
                    listOf("product", "current-slot", "unlocked", "is-userspace", "max-download-size", "serialno").forEach { k ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(k, color = Color.White.copy(.6f), fontSize = 12.sp)
                            Text(vm.info[k] ?: "…", color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                } else if (!vm.adbConnected) {
                    GlassButton("Rescan", onClick = { vm.scan() })
                }
            }

            // Mode tabs
            GlassTabs(listOf("Fastboot ROM", "Custom ROM", "Slots"), tab) { tab = it }

            when (tab) {
                // ---------------- Fastboot ROM ----------------
                0 -> GlassCard {
                    Text("Fastboot ROM", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Import a ROM zip/tgz: every .img inside is listed, then unpacked and flashed one by one. You can also add single .img files.",
                        color = Color.White.copy(.8f), fontSize = 12.sp
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassButton("📦 Import ROM zip/tgz", enabled = !vm.busy, onClick = { archivePicker.launch(arrayOf("*/*")) })
                        GlassButton("＋ Add .img files", enabled = !vm.busy, onClick = { picker.launch(arrayOf("*/*")) })
                    }
                    if (vm.items.isNotEmpty()) {
                        Text("${vm.items.size} image(s) in the list", color = Color.White.copy(.6f), fontSize = 12.sp)
                    }
                    vm.items.forEach { item ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(item.name, color = Color.White, fontSize = 12.sp, maxLines = 1)
                                Text("${item.size / 1048576} MB", color = Color.White.copy(.5f), fontSize = 10.sp)
                            }
                            BasicTextField(
                                value = item.partition, onValueChange = { item.partition = it.trim() }, singleLine = true,
                                textStyle = TextStyle(color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                                cursorBrush = SolidColor(Ice),
                                modifier = Modifier.width(110.dp).clip(RoundedCornerShape(12.dp))
                                    .background(Color.White.copy(.10f)).padding(horizontal = 10.dp, vertical = 8.dp)
                            )
                            Text("✕", color = Color.White.copy(.7f), modifier = Modifier.clickable(enabled = !vm.busy) { vm.items.remove(item) }.padding(6.dp))
                        }
                    }
                    Text("Flash slot: ${vm.slotMode}  (change it in the Slots tab)", color = Color.White.copy(.6f), fontSize = 12.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Disable verity/verification (vbmeta)", color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Switch(vm.disableVerity, { vm.disableVerity = it })
                    }
                    if (vm.busy) LinearProgressIndicator(progress = { vm.progress }, modifier = Modifier.fillMaxWidth(), color = Ice, trackColor = Color.White.copy(.15f))
                    GlassButton("⚡ Flash all images", accent = true, enabled = vm.connected && vm.items.isNotEmpty() && !vm.busy, onClick = { vm.flashAll() })
                }

                // ---------------- Custom ROM (sideload) ----------------
                1 -> GlassCard {
                    Text("Custom ROM (Sideload)", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (vm.adbConnected) vm.adbBanner else "Not connected",
                        color = Color.White.copy(.6f), fontSize = 11.sp, fontFamily = FontFamily.Monospace
                    )
                    Text(
                        "1. On the target phone, boot into recovery.\n" +
                            "2. Choose “Apply update” → “Apply from ADB”.\n" +
                            "3. Connect it to this phone with an OTG cable.\n" +
                            "4. Tap the button below and pick the ROM zip.",
                        color = Color.White.copy(.8f), fontSize = 12.sp
                    )
                    if (vm.busy) LinearProgressIndicator(progress = { vm.progress }, modifier = Modifier.fillMaxWidth(), color = Ice, trackColor = Color.White.copy(.15f))
                    GlassButton("📦 Sideload ROM ZIP", accent = true, enabled = vm.adbConnected && !vm.busy, onClick = { sideloadPicker.launch(arrayOf("*/*")) })
                }

                // ---------------- Slots ----------------
                else -> GlassCard {
                    Text("Slots", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    listOf("current-slot", "slot-count").forEach { k ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(k, color = Color.White.copy(.6f), fontSize = 12.sp)
                            Text(if (vm.connected) (vm.info[k] ?: "…") else "—", color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                    Text("Set active slot", color = Color.White.copy(.6f), fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassButton("Slot A", enabled = on) { vm.setActive("a") }
                        GlassButton("Slot B", enabled = on) { vm.setActive("b") }
                    }
                    Text("Flash target slot", color = Color.White.copy(.6f), fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("current", "a", "b", "all").forEach { GlassChip(it, vm.slotMode == it) { vm.slotMode = it } }
                    }
                    Text(
                        when (vm.slotMode) {
                            "a" -> "Images are flashed to the _a partitions only."
                            "b" -> "Images are flashed to the _b partitions only."
                            "all" -> "Images are flashed to both _a and _b partitions."
                            else -> "Images are flashed to the currently active slot."
                        },
                        color = Color.White.copy(.8f), fontSize = 12.sp
                    )
                }
            }

            // Actions
            GlassCard {
                Text("Quick actions", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassButton("Reboot", enabled = on) { vm.reboot("reboot") }
                    GlassButton("Bootloader", enabled = on) { vm.reboot("reboot-bootloader") }
                    GlassButton("Recovery", enabled = on) { vm.reboot("reboot-recovery") }
                    GlassButton("FastbootD", enabled = on) { vm.reboot("reboot-fastboot") }
                    GlassButton("getvar all", enabled = on) { vm.raw("getvar:all") }
                    GlassButton("Unlock BL", enabled = on) { confirm = "Unlocking the bootloader can wipe all data." to { vm.unlock() } }
                    GlassButton("Lock BL", enabled = on) { confirm = "Locking the bootloader with a custom ROM installed can brick the phone!" to { vm.lock() } }
                    GlassButton("Wipe data", enabled = on) { confirm = "userdata + metadata will be erased." to { vm.wipe() } }
                }
            }

            // Console
            GlassCard {
                Text("Console", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                val ls = rememberLazyListState()
                LaunchedEffect(vm.logLines.size) { if (vm.logLines.isNotEmpty()) ls.animateScrollToItem(vm.logLines.lastIndex) }
                LazyColumn(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(.35f)).padding(10.dp), state = ls) {
                    items(vm.logLines.toList()) { Text(it, color = Color(0xFFB6F7C1), fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
                }
                var cmd by remember { mutableStateOf("") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        value = cmd, onValueChange = { cmd = it }, singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                        cursorBrush = SolidColor(Ice),
                        decorationBox = { inner -> Box { if (cmd.isEmpty()) Text("getvar:product", color = Color.White.copy(.35f), fontSize = 13.sp, fontFamily = FontFamily.Monospace); inner() } },
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(.10f)).padding(12.dp)
                    )
                    GlassButton("Send", enabled = vm.connected && !vm.busy && cmd.isNotBlank()) { vm.raw(cmd.trim()); cmd = "" }
                }
            }

            Text("Made with ♥ by @yooitachiijod", color = Color.White.copy(.45f), fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp))
        }
    }

    confirm?.let { (msg, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            containerColor = Color(0xFF1B1F3B),
            title = { Text("Are you sure?", color = Color.White) },
            text = { Text(msg, color = Color.White.copy(.8f)) },
            confirmButton = { TextButton({ action(); confirm = null }) { Text("Confirm") } },
            dismissButton = { TextButton({ confirm = null }) { Text("Cancel") } }
        )
    }
}
