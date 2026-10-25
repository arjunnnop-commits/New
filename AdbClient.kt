package com.yooitachiijod.glassfastboot

import android.hardware.usb.*
import android.os.Build
import android.util.Base64
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.PKCS8EncodedKeySpec

/** Minimal ADB-over-USB client: CNXN + AUTH + sideload-host (recovery "Apply update from ADB"). */
class AdbClient private constructor(
    private val conn: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint,
    private val log: (String) -> Unit,
    private val keyFile: File
) : AutoCloseable {

    companion object {
        private const val A_CNXN = 0x4e584e43
        private const val A_AUTH = 0x48545541
        private const val A_OPEN = 0x4e45504f
        private const val A_OKAY = 0x59414b4f
        private const val A_WRTE = 0x45545257
        private const val A_CLSE = 0x45534c43

        fun findInterface(dev: UsbDevice): UsbInterface? {
            for (i in 0 until dev.interfaceCount) {
                val f = dev.getInterface(i)
                if (f.interfaceClass == 0xFF && f.interfaceSubclass == 0x42 && f.interfaceProtocol == 0x01) return f
            }
            return null
        }

        fun open(mgr: UsbManager, dev: UsbDevice, log: (String) -> Unit, keyFile: File): AdbClient {
            val iface = findInterface(dev) ?: throw FastbootException("ADB interface not found")
            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null
            for (i in 0 until iface.endpointCount) {
                val e = iface.getEndpoint(i)
                if (e.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (e.direction == UsbConstants.USB_DIR_IN) inEp = e else outEp = e
                }
            }
            if (inEp == null || outEp == null) throw FastbootException("ADB endpoints not found")
            val c = mgr.openDevice(dev) ?: throw FastbootException("Could not open USB device")
            if (!c.claimInterface(iface, true)) { c.close(); throw FastbootException("Could not claim USB interface") }
            return AdbClient(c, iface, inEp, outEp, log, keyFile)
        }
    }

    private var maxData = 4096
    private val chunk = if (Build.VERSION.SDK_INT >= 28) 64 * 1024 else 16 * 1024

    private class Pkt(val cmd: Int, val a0: Int, val a1: Int, val data: ByteArray)

    private fun writeAll(buf: ByteArray, len: Int) {
        var off = 0
        while (off < len) {
            val n = minOf(len - off, chunk)
            val r = conn.bulkTransfer(epOut, buf, off, n, 15000)
            if (r <= 0) throw FastbootException("ADB USB write fail")
            off += r
        }
    }

    private fun readAll(buf: ByteArray, len: Int, timeout: Int) {
        var off = 0
        while (off < len) {
            val r = conn.bulkTransfer(epIn, buf, off, len - off, timeout)
            if (r < 0) throw FastbootException("ADB USB read timeout/error")
            off += r
        }
    }

    private fun send(cmd: Int, a0: Int, a1: Int, data: ByteArray = ByteArray(0)) {
        var sum = 0
        for (b in data) sum += b.toInt() and 0xFF
        val h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        h.putInt(cmd).putInt(a0).putInt(a1).putInt(data.size).putInt(sum).putInt(cmd.inv())
        writeAll(h.array(), 24)
        if (data.isNotEmpty()) writeAll(data, data.size)
    }

    private fun read(timeout: Int): Pkt {
        val hb = ByteArray(24)
        readAll(hb, 24, timeout)
        val bb = ByteBuffer.wrap(hb).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = bb.int; val a0 = bb.int; val a1 = bb.int; val len = bb.int
        val data = if (len > 0) ByteArray(len).also { readAll(it, len, timeout) } else ByteArray(0)
        return Pkt(cmd, a0, a1, data)
    }

    // ---------- auth ----------
    private fun privateKey(): RSAPrivateCrtKey {
        val kf = KeyFactory.getInstance("RSA")
        if (!keyFile.exists()) {
            val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            keyFile.writeBytes(kp.private.encoded)
        }
        return kf.generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes())) as RSAPrivateCrtKey
    }

    private fun sign(token: ByteArray): ByteArray {
        val prefix = byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)
        val s = Signature.getInstance("NONEwithRSA")
        s.initSign(privateKey())
        s.update(prefix + token)
        return s.sign()
    }

    private fun le(v: BigInteger, len: Int): ByteArray {
        val out = ByteArray(len)
        val be = v.toByteArray()
        for (i in 0 until len) {
            val idx = be.size - 1 - i
            out[i] = if (idx >= 0) be[idx] else 0
        }
        return out
    }

    private fun publicKeyBlob(): ByteArray {
        val k = privateKey()
        val n = k.modulus
        val two32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = two32.subtract(n.mod(two32).modInverse(two32)).toLong()
        val rr = BigInteger.ONE.shiftLeft(4096).mod(n)
        val bb = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(64); bb.putInt(n0inv.toInt())
        bb.put(le(n, 256)); bb.put(le(rr, 256))
        bb.putInt(k.publicExponent.toInt())
        val b64 = Base64.encodeToString(bb.array(), Base64.NO_WRAP)
        return (b64 + " glassfastboot@android\u0000").toByteArray()
    }

    fun connect(): String {
        send(A_CNXN, 0x01000001, 1024 * 1024, "host::\u0000".toByteArray())
        var signed = false
        while (true) {
            val p = read(60000)
            when (p.cmd) {
                A_CNXN -> {
                    maxData = p.a1.coerceIn(4096, chunk)
                    return String(p.data).trimEnd('\u0000')
                }
                A_AUTH -> if (p.a0 == 1) {
                    if (!signed) { send(A_AUTH, 2, 0, sign(p.data)); signed = true }
                    else { log("Tap 'Allow USB debugging' on the target phone"); send(A_AUTH, 3, 0, publicKeyBlob()) }
                }
                else -> throw FastbootException("Unexpected ADB packet")
            }
        }
    }

    private fun waitOkay() {
        while (true) {
            val p = read(60000)
            if (p.cmd == A_OKAY) return
            if (p.cmd == A_CLSE) throw FastbootException("Device closed the stream")
            throw FastbootException("Unexpected ADB packet during sideload")
        }
    }

    /** Streams a ROM zip to recovery's "Apply update from ADB" mode. */
    fun sideload(ch: FileChannel, size: Long, onFrac: (Float) -> Unit) {
        val bs = 64 * 1024
        val totalBlocks = (size + bs - 1) / bs
        send(A_OPEN, 1, 0, "sideload-host:$size:$bs\u0000".toByteArray())
        var remote = 0
        var done = false
        while (!done) {
            val p = read(600000)
            when (p.cmd) {
                A_OKAY -> if (remote == 0) remote = p.a0
                A_WRTE -> {
                    if (remote == 0) remote = p.a0
                    send(A_OKAY, 1, remote)
                    val s = String(p.data)
                    when {
                        s.startsWith("DONEDONE") -> done = true
                        s.startsWith("FAIL") -> throw FastbootException("Recovery rejected the request: $s")
                        else -> {
                            val block = s.filter { it.isDigit() }.toLong()
                            val off = block * bs
                            if (off >= size) throw FastbootException("Block $block is outside the file")
                            val len = minOf(bs.toLong(), size - off).toInt()
                            val data = ByteArray(len)
                            val bb = ByteBuffer.wrap(data)
                            var pos = off
                            while (bb.hasRemaining()) {
                                val n = ch.read(bb, pos)
                                if (n <= 0) throw FastbootException("File read error")
                                pos += n
                            }
                            var sent = 0
                            while (sent < len) {
                                val n = minOf(maxData, len - sent)
                                send(A_WRTE, 1, remote, data.copyOfRange(sent, sent + n))
                                waitOkay()
                                sent += n
                            }
                            onFrac(((block + 1).toFloat() / totalBlocks).coerceAtMost(1f))
                        }
                    }
                }
                A_CLSE -> done = true
            }
        }
    }

    override fun close() {
        try { conn.releaseInterface(iface) } catch (_: Exception) { }
        try { conn.close() } catch (_: Exception) { }
    }
}
