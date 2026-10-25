package com.yooitachiijod.glassfastboot

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

/** A zip entry that is only unpacked when it is its turn to be flashed. */
class ZipSource(val uri: Uri?, val file: File?, val entry: ZEntry)

class FlashItem(
    val uri: Uri, val name: String, val size: Long, partition: String,
    val temp: File? = null, val zip: ZipSource? = null
) {
    var partition by mutableStateOf(partition)
}

private class Imported {
    var count = 0
    var payload = false
}

class FlashViewModel(app: Application) : AndroidViewModel(app) {
    companion object { const val ACTION_PERM = "com.yooitachiijod.glassfastboot.USB_PERMISSION" }

    private val ctx get() = getApplication<Application>()
    private val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
    private var client: FastbootClient? = null
    private var adb: AdbClient? = null

    var connected by mutableStateOf(false); private set
    var adbConnected by mutableStateOf(false); private set
    var adbBanner by mutableStateOf(""); private set
    var busy by mutableStateOf(false); private set
    var progress by mutableFloatStateOf(0f); private set
    val info = mutableStateMapOf<String, String>()
    val items = mutableStateListOf<FlashItem>()
    val logLines = mutableStateListOf<String>()
    var slotMode by mutableStateOf("current")
    var disableVerity by mutableStateOf(false)
    private var maxDownload = 256L * 1024 * 1024

    fun log(m: String) {
        logLines.add(m)
        if (logLines.size > 500) logLines.removeAt(0)
    }

    // ---------- USB connect ----------
    fun scan() {
        if (client != null || adb != null || busy) return
        for (d in usb.deviceList.values) {
            val isFb = FastbootClient.findInterface(d) != null
            val isAdb = AdbClient.findInterface(d) != null
            if (!isFb && !isAdb) continue
            if (usb.hasPermission(d)) {
                if (isFb) connect(d) else connectAdb(d)
            } else {
                val pi = PendingIntent.getBroadcast(
                    ctx, 0, Intent(ACTION_PERM).setPackage(ctx.packageName), PendingIntent.FLAG_MUTABLE
                )
                usb.requestPermission(d, pi)
            }
            return
        }
    }

    private fun connectAdb(d: UsbDevice) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val a = AdbClient.open(usb, d, ::log, File(ctx.filesDir, "adbkey.pk8"))
                adb = a
                val banner = a.connect()
                adbBanner = banner; adbConnected = true
                log("✔ ADB connected: $banner")
                if (!banner.contains("sideload")) log("ℹ For sideload, boot to recovery and choose 'Apply update from ADB'")
            } catch (e: Exception) {
                adb?.close(); adb = null
                log("✖ ADB: ${e.message}")
            }
        }
    }

    private fun connect(d: UsbDevice) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val c = FastbootClient.open(usb, d, ::log)
                client = c
                fun v(n: String) = try { c.command("getvar:$n") } catch (_: Exception) { "?" }
                for (k in listOf("product", "current-slot", "slot-count", "unlocked", "is-userspace", "serialno", "secure")) info[k] = v(k)
                val md = v("max-download-size")
                info["max-download-size"] = md
                md.removePrefix("0x").toLongOrNull(16)?.let { if (it > 0) maxDownload = it }
                connected = true
                log("✔ Fastboot device connected (${info["product"]})")
            } catch (e: Exception) {
                client?.close(); client = null
                log("✖ Connect: ${e.message}")
            }
        }
    }

    fun onDetached() {
        if (client != null || adb != null) log("⏏ Device disconnected")
        disconnect()
    }

    private fun disconnect() {
        client?.close(); client = null
        adb?.close(); adb = null
        connected = false; adbConnected = false; adbBanner = ""; info.clear()
    }

    // ---------- files ----------
    private fun meta(u: Uri): Pair<String, Long> {
        var name = "file"; var size = 0L
        ctx.contentResolver.query(u, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0) name = c.getString(ni)
                if (si >= 0) size = c.getLong(si)
            }
        }
        return name to size
    }

    private fun guessPartition(name: String): String {
        val stem = name.substringBeforeLast('.')
        return when {
            stem.startsWith("bootloader-") -> "bootloader"
            stem.startsWith("radio-") -> "radio"
            else -> stem
        }
    }

    /** super / userdata go last, everything else keeps its order. */
    private fun weight(n: String) = if (n.startsWith("super") || n.startsWith("userdata")) 1 else 0

    fun addFiles(uris: List<Uri>) {
        for (u in uris) {
            val (name, size) = meta(u)
            items.add(FlashItem(u, name, size, guessPartition(name)))
        }
    }

    /** Opens the archive (content uri or a cached file) as a seekable channel. */
    private fun <T> withArchive(uri: Uri?, file: File?, block: (FileChannel) -> T): T {
        if (file != null) return RandomAccessFile(file, "r").use { block(it.channel) }
        val u = uri ?: throw FastbootException("Archive source missing")
        val pfd = ctx.contentResolver.openFileDescriptor(u, "r") ?: throw FastbootException("Cannot open the archive")
        return pfd.use { p -> FileInputStream(p.fileDescriptor).use { block(it.channel) } }
    }

    private fun lazyItem(src: ZipSource): FlashItem {
        val base = src.entry.name.substringAfterLast('/')
        val u = src.uri ?: src.file?.let { Uri.fromFile(it) } ?: Uri.EMPTY
        return FlashItem(u, base, src.entry.usize, guessPartition(base), null, src)
    }

    private fun isImg(e: ZEntry): Boolean {
        val base = e.name.substringAfterLast('/')
        return !e.name.endsWith("/") && base.endsWith(".img", ignoreCase = true) && base != "super_empty.img"
    }

    /** Lists every .img in the zip without unpacking anything, so ALL images show up at once. */
    private fun indexZip(uri: Uri, dir: File, res: Imported): List<FlashItem> {
        val found = ArrayList<FlashItem>()
        val entries = withArchive(uri, null) { ch -> ZipIndex.list(ch) }
        val nested = ArrayList<ZEntry>()
        for (e in entries) {
            if (e.name.endsWith("/")) continue
            val base = e.name.substringAfterLast('/')
            when {
                isImg(e) -> found.add(lazyItem(ZipSource(uri, null, e)))
                base == "payload.bin" -> res.payload = true
                base.endsWith(".zip", ignoreCase = true) && base.startsWith("image", ignoreCase = true) -> nested.add(e)
            }
        }
        // Pixel-style factory zips keep the real images inside image-*.zip
        for (e in nested) {
            val base = e.name.substringAfterLast('/')
            val inner = File(dir, "nested_${System.nanoTime()}.zip")
            try {
                val need = e.usize + (64L shl 20)
                if (dir.usableSpace < need) throw FastbootException("not enough free storage to open $base (needs ${need / 1048576} MB)")
                log("  opening $base (${e.usize / 1048576} MB) …")
                withArchive(uri, null) { ch -> ZipIndex.extractEntry(ch, e, inner) { } }
                val innerEntries = withArchive(null, inner) { ch -> ZipIndex.list(ch) }
                for (ie in innerEntries) {
                    if (isImg(ie)) found.add(lazyItem(ZipSource(null, inner, ie)))
                    else if (ie.name.substringAfterLast('/') == "payload.bin") res.payload = true
                }
            } catch (ex: Exception) {
                inner.delete()
                log("  ✖ $base: ${ex.message}")
            }
        }
        return found
    }

    /** ROM zip / tgz → every .img inside is listed and queued for flashing. */
    fun importArchive(uri: Uri) {
        if (busy) return
        busy = true; progress = 0f
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val (name, _) = meta(uri)
                val lower = name.lowercase()
                log("📦 Reading $name …")
                val dir = File(ctx.cacheDir, "rom")
                dir.deleteRecursively(); dir.mkdirs()
                val res = Imported()
                val isTar = lower.endsWith(".tgz") || lower.endsWith(".tar.gz") || lower.endsWith(".tar")
                var done = false
                if (!isTar) {
                    try {
                        val found = indexZip(uri, dir, res).sortedBy { weight(it.name) }
                        found.forEach { items.add(it) }
                        res.count = found.size
                        done = true
                    } catch (e: Exception) {
                        log("ℹ Direct zip index failed (${e.message}) — using sequential extraction")
                        res.payload = false
                    }
                }
                if (!done) {
                    val r = ctx.contentResolver.openInputStream(uri)?.use { s ->
                        Archive.extractImages(s, name, dir, ::log) { f ->
                            items.add(FlashItem(Uri.fromFile(f), f.name, f.length(), guessPartition(f.name), f))
                            res.count++
                        }
                    } ?: throw FastbootException("Cannot open the archive")
                    if (r.hasPayload) res.payload = true
                }
                when {
                    res.count > 0 -> log("✔ ${res.count} image(s) listed — check the partition names, then tap Flash")
                    res.payload -> log("This is an OTA zip (payload.bin) — fastboot can't flash it. Use the Custom ROM tab and sideload it.")
                    else -> log("No .img files found in this archive")
                }
            } catch (e: Exception) {
                log("✖ Import: ${e.message}")
            } finally { busy = false }
        }
    }

    // ---------- operations ----------
    private fun runOp(title: String, block: (FastbootClient) -> Unit) {
        val c = client ?: run { log("No fastboot device connected"); return }
        if (busy) return
        busy = true; progress = 0f
        viewModelScope.launch(Dispatchers.IO) {
            try {
                log("▶ $title")
                block(c)
                log("✔ $title — done")
            } catch (e: Exception) {
                log("✖ ${e.message}")
            } finally { busy = false; progress = 0f }
        }
    }

    /** Unpacks one zip entry into the cache right before it is flashed (one image at a time). */
    private fun materialize(item: FlashItem, onFrac: (Float) -> Unit): File {
        val z = item.zip ?: throw FastbootException("Not an archive entry: ${item.name}")
        val dir = File(ctx.cacheDir, "rom"); dir.mkdirs()
        val need = z.entry.usize + (64L shl 20)
        val free = dir.usableSpace
        if (free < need) throw FastbootException(
            "Not enough free storage to unpack ${item.name}: needs ${need / 1048576} MB, only ${free / 1048576} MB free"
        )
        val out = File(dir, "x_${System.nanoTime()}.img")
        try {
            withArchive(z.uri, z.file) { ch -> ZipIndex.extractEntry(ch, z.entry, out, onFrac) }
        } catch (e: Exception) {
            out.delete(); throw e
        }
        return out
    }

    private fun hint(msg: String?) {
        val m = (msg ?: "").lowercase()
        if ("partition" in m || "not allowed" in m || "not found" in m || "unknown" in m) {
            log("  ℹ Logical partitions (system, vendor, product, system_ext …) can only be flashed from FastbootD — tap “FastbootD” in Quick actions, or flash super.img instead.")
        }
    }

    fun flashAll() {
        val list = items.toList().sortedBy { weight(it.name) }
        if (list.isEmpty()) return
        runOp("Flash ${list.size} image(s)") { c ->
            val failed = ArrayList<String>()
            list.forEachIndexed { i, item ->
                log("→ ${item.name}  ⇒  ${item.partition}")
                var tmp: File? = null
                try {
                    val lazy = item.zip != null
                    val base = if (lazy) 0.4f else 0f
                    val uri = if (lazy) {
                        log("  unpacking ${item.name} (${item.size / 1048576} MB) …")
                        val f = materialize(item) { fr -> progress = (i + fr * base) / list.size }
                        tmp = f
                        Uri.fromFile(f)
                    } else item.uri
                    flashOne(c, item, uri) { fr -> progress = (i + base + fr * (1f - base)) / list.size }
                    if (item.zip != null || item.temp != null) items.remove(item)
                    item.temp?.delete()
                } catch (e: Exception) {
                    failed.add(item.name)
                    log("  ✖ ${item.name}: ${e.message}")
                    hint(e.message)
                } finally {
                    tmp?.delete()
                }
            }
            if (failed.isNotEmpty()) {
                throw FastbootException("${failed.size} of ${list.size} image(s) failed: ${failed.joinToString()} (they stay in the list so you can retry)")
            }
        }
    }

    private fun flashOne(c: FastbootClient, item: FlashItem, uri: Uri, onFrac: (Float) -> Unit) {
        for (target in c.resolveTargets(item.partition, slotMode)) {
            val pfd = ctx.contentResolver.openFileDescriptor(uri, "r")
                ?: throw FastbootException("Cannot open file: ${item.name}")
            pfd.use {
                val size = it.statSize
                FileInputStream(it.fileDescriptor).use { fis ->
                    val ch = fis.channel
                    if (disableVerity && target.startsWith("vbmeta") && size < 4 * 1024 * 1024) {
                        val b = ByteArray(size.toInt()); var r = 0
                        ch.position(0)
                        while (r < b.size) { val n = fis.read(b, r, b.size - r); if (n < 0) break; r += n }
                        if (b.size > 124 && String(b, 0, 4) == "AVB0") {
                            b[123] = (b[123].toInt() or 3).toByte()
                            log("  vbmeta: verity+verification disabled")
                        }
                        c.flash(target, ByteArrayInputStream(b), b.size.toLong(), onFrac)
                        return@use
                    }
                    if (size <= maxDownload) {
                        ch.position(0)
                        log("  flashing $target (${size / 1048576} MB)")
                        c.flash(target, fis, size, onFrac)
                    } else {
                        val layout = if (Sparse.isSparse(ch)) Sparse.parse(ch) else Sparse.rawLayout(size)
                        val pieces = Sparse.plan(layout, maxDownload)
                        log("  big image → ${pieces.size} piece(s) for $target")
                        pieces.forEachIndexed { idx, pc ->
                            log("  piece ${idx + 1}/${pieces.size}")
                            val f = File(ctx.cacheDir, "piece_$idx.simg")
                            try {
                                Sparse.write(ch, layout, pc, f)
                                FileInputStream(f).use { s ->
                                    c.flash(target, s, f.length()) { fr -> onFrac((idx + fr) / pieces.size) }
                                }
                            } finally { f.delete() }
                        }
                    }
                }
            }
        }
    }

    fun raw(cmd: String) = runOp(cmd) { c ->
        val r = c.command(cmd, 120000) { log(it) }
        if (r.isNotEmpty()) log(r)
    }

    fun reboot(cmd: String) {
        val c = client ?: return
        viewModelScope.launch(Dispatchers.IO) {
            log("↻ $cmd")
            c.sendNoReply(cmd)
            disconnect()
        }
    }

    private fun refreshSlot(c: FastbootClient) {
        try { info["current-slot"] = c.command("getvar:current-slot") } catch (_: Exception) { }
    }

    fun setActive(slot: String) = runOp("Set active slot $slot") { c ->
        c.command("set_active:$slot")
        refreshSlot(c)
    }

    fun unlock() = runOp("Unlock bootloader (confirm on the phone)") { c ->
        try { c.command("flashing unlock", 120000) { log(it) } }
        catch (e: Exception) { log("flashing unlock failed → trying oem unlock"); c.command("oem unlock", 120000) { log(it) } }
    }

    fun lock() = runOp("Lock bootloader") { c ->
        try { c.command("flashing lock", 120000) { log(it) } }
        catch (e: Exception) { c.command("oem lock", 120000) { log(it) } }
    }

    fun wipe() = runOp("Wipe data") { c ->
        c.command("erase:userdata", 300000) { log(it) }
        try { c.command("erase:metadata", 120000) { log(it) } } catch (e: Exception) { log("metadata skipped: ${e.message}") }
    }

    fun sideload(uri: Uri) {
        val a = adb ?: run { log("No ADB / recovery device connected"); return }
        if (busy) return
        busy = true; progress = 0f
        viewModelScope.launch(Dispatchers.IO) {
            try {
                log("▶ Sideload started (keep 'Apply update from ADB' open on the phone)")
                val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: throw FastbootException("Cannot open file")
                pfd.use { p ->
                    FileInputStream(p.fileDescriptor).use { fis -> a.sideload(fis.channel, p.statSize) { f -> progress = f } }
                }
                log("✔ Sideload complete — check the result on the phone's recovery screen")
            } catch (e: Exception) {
                log("✖ ${e.message}")
            } finally { busy = false; progress = 0f }
        }
    }

    override fun onCleared() { client?.close(); adb?.close() }
}
