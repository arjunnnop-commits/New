package com.yooitachiijod.glassfastboot

import android.hardware.usb.*
import android.os.Build
import java.io.InputStream

class FastbootException(msg: String) : Exception(msg)

class Response(val kind: String, val msg: String)

/** Minimal fastboot protocol over Android USB Host API (no root needed). */
class FastbootClient private constructor(
    private val conn: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint,
    private val log: (String) -> Unit
) : AutoCloseable {

    companion object {
        fun findInterface(dev: UsbDevice): UsbInterface? {
            for (i in 0 until dev.interfaceCount) {
                val f = dev.getInterface(i)
                if (f.interfaceClass == 0xFF && f.interfaceSubclass == 0x42 && f.interfaceProtocol == 0x03) return f
            }
            return null
        }

        fun open(mgr: UsbManager, dev: UsbDevice, log: (String) -> Unit): FastbootClient {
            val iface = findInterface(dev) ?: throw FastbootException("Fastboot interface not found")
            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null
            for (i in 0 until iface.endpointCount) {
                val e = iface.getEndpoint(i)
                if (e.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (e.direction == UsbConstants.USB_DIR_IN) inEp = e else outEp = e
                }
            }
            if (inEp == null || outEp == null) throw FastbootException("Bulk endpoints not found")
            val c = mgr.openDevice(dev) ?: throw FastbootException("Could not open USB device")
            if (!c.claimInterface(iface, true)) {
                c.close(); throw FastbootException("Could not claim USB interface")
            }
            return FastbootClient(c, iface, inEp, outEp, log)
        }
    }

    private fun writeFully(buf: ByteArray, len: Int, timeout: Int = 30000) {
        val n = conn.bulkTransfer(epOut, buf, 0, len, timeout)
        if (n != len) throw FastbootException("USB write fail ($n/$len)")
    }

    private fun readResponse(timeoutMs: Int, onInfo: (String) -> Unit): Response {
        val buf = ByteArray(512)
        while (true) {
            val n = conn.bulkTransfer(epIn, buf, 0, buf.size, timeoutMs)
            if (n < 0) throw FastbootException("USB read timeout/error")
            val s = String(buf, 0, n, Charsets.UTF_8)
            when {
                s.startsWith("INFO") -> onInfo(s.substring(4))
                s.startsWith("TEXT") -> onInfo(s.substring(4))
                s.startsWith("OKAY") -> return Response("OKAY", s.substring(4))
                s.startsWith("DATA") -> return Response("DATA", s.substring(4))
                s.startsWith("FAIL") -> throw FastbootException(s.substring(4).ifEmpty { "FAIL" })
                else -> throw FastbootException("Unknown response: $s")
            }
        }
    }

    @Synchronized
    fun send(cmd: String, timeoutMs: Int = 30000, onInfo: (String) -> Unit = {}): Response {
        val b = cmd.toByteArray(Charsets.UTF_8)
        writeFully(b, b.size)
        return readResponse(timeoutMs, onInfo)
    }

    fun command(cmd: String, timeoutMs: Int = 30000, onInfo: (String) -> Unit = {}): String =
        send(cmd, timeoutMs, onInfo).msg

    /** For reboot commands: device may disconnect before replying. */
    @Synchronized
    fun sendNoReply(cmd: String) {
        val b = cmd.toByteArray(Charsets.UTF_8)
        try {
            writeFully(b, b.size)
            readResponse(2000) {}
        } catch (_: Exception) { }
    }

    fun download(input: InputStream, size: Long, onFrac: (Float) -> Unit) {
        val r = send("download:%08x".format(size))
        if (r.kind != "DATA") throw FastbootException("Download refused: ${r.msg}")
        val chunk = if (Build.VERSION.SDK_INT >= 28) 64 * 1024 else 16 * 1024
        val buf = ByteArray(chunk)
        var sent = 0L
        while (sent < size) {
            val want = minOf(chunk.toLong(), size - sent).toInt()
            var read = 0
            while (read < want) {
                val n = input.read(buf, read, want - read)
                if (n < 0) throw FastbootException("Image file ended unexpectedly")
                read += n
            }
            writeFully(buf, want)
            sent += want
            onFrac(sent.toFloat() / size)
        }
        val end = readResponse(120000) { log(it) }
        if (end.kind != "OKAY") throw FastbootException("Download finish fail")
    }

    fun flash(partition: String, input: InputStream, size: Long, onFrac: (Float) -> Unit) {
        download(input, size, onFrac)
        command("flash:$partition", 600000) { log(it) }
    }

    fun currentSlot(): String =
        try { command("getvar:current-slot").trim('_') } catch (_: Exception) { "a" }

    /** Adds _a/_b suffix for A/B partitions like the official fastboot client. */
    fun resolveTargets(part: String, mode: String): List<String> {
        if (part.endsWith("_a") || part.endsWith("_b")) return listOf(part)
        val hasSlot = try { command("getvar:has-slot:$part") == "yes" } catch (_: Exception) { false }
        if (!hasSlot) return listOf(part)
        return when (mode) {
            "all" -> listOf("${part}_a", "${part}_b")
            "a" -> listOf("${part}_a")
            "b" -> listOf("${part}_b")
            else -> listOf("${part}_${currentSlot()}")
        }
    }

    override fun close() {
        try { conn.releaseInterface(iface) } catch (_: Exception) { }
        try { conn.close() } catch (_: Exception) { }
    }
}
