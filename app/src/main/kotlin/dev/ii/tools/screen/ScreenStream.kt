package dev.ii.tools.screen

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * One second-screen stream: the socket `adb reverse` brings from the PC, H.264 into a
 * hardware decoder that renders straight onto [surface], and the fingers back.
 *
 * Packets (screen.rs): `[u32 length][u8 kind][u64 pts µs][Annex-B]`, kind 0 the codec
 * config, 2 a key frame. Each decoded frame is shown the moment it is out: there is no
 * clock to wait for, the PC sends a frame only when its screen changed.
 */
class ScreenStream(
    private val port: Int,
    private val token: String,
    private val width: Int,
    private val height: Int,
    private val surface: Surface,
    private val onEnd: (failed: Boolean) -> Unit,
) {
    @Volatile private var running = true
    private var socket: Socket? = null
    private var out: OutputStream? = null
    private var codec: MediaCodec? = null
    private val codecThread = HandlerThread("screen-codec").apply { start() }
    private val inputs = LinkedBlockingQueue<Int>()
    // Touches are written off the UI thread, in order.
    private val writer = Executors.newSingleThreadExecutor()

    fun start() {
        Thread({ run() }, "screen-stream").start()
    }

    private fun run() {
        var failed = false
        try {
            val s = Socket()
            s.tcpNoDelay = true
            s.connect(InetSocketAddress("127.0.0.1", port), 5000)
            socket = s
            out = s.getOutputStream().also { it.write("$token\n".toByteArray()); it.flush() }
            var codec = decoder()
            this.codec = codec
            val inp = DataInputStream(BufferedInputStream(s.getInputStream(), 1 shl 16))
            var buf = ByteArray(1 shl 18)
            var configured = false
            while (running) {
                val len = inp.readInt()
                if (len < 9 || len > (32 shl 20)) error("bad packet")
                val kind = inp.readByte().toInt()
                val pts = inp.readLong()
                val n = len - 9
                if (buf.size < n) buf = ByteArray(n)
                inp.readFully(buf, 0, n)
                // A second codec config: the PC's capture started again (a new scale or
                // mode), maybe at another size. A fresh decoder takes it cleanly.
                if (kind == 0 && configured) {
                    synchronized(this) {
                        runCatching { codec.stop() }
                        runCatching { codec.release() }
                        inputs.clear()
                        codec = decoder()
                        this.codec = codec
                    }
                }
                if (kind == 0) configured = true
                val idx = inputs.poll(1, TimeUnit.SECONDS) ?: continue
                val ib = codec.getInputBuffer(idx) ?: continue
                ib.clear()
                if (ib.capacity() < n) {
                    codec.queueInputBuffer(idx, 0, 0, pts, 0)
                    continue
                }
                ib.put(buf, 0, n)
                val flags = when (kind) {
                    0 -> MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                    2 -> MediaCodec.BUFFER_FLAG_KEY_FRAME
                    else -> 0
                }
                codec.queueInputBuffer(idx, 0, n, pts, flags)
            }
        } catch (e: Exception) {
            failed = running
        } finally {
            val wasRunning = running
            stop()
            if (wasRunning) onEnd(failed)
        }
    }

    private fun decoder(): MediaCodec {
        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val lowLatency = runCatching {
            codec.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
        }.getOrDefault(false)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            if (lowLatency) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
        }
        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(c: MediaCodec, index: Int) {
                inputs.offer(index)
            }

            override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                runCatching { c.releaseOutputBuffer(index, true) }
            }

            override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {
                running = false
                runCatching { socket?.close() }
            }

            override fun onOutputFormatChanged(c: MediaCodec, format: MediaFormat) {}
        }, Handler(codecThread.looper))
        codec.configure(format, surface, null, 0)
        codec.start()
        return codec
    }

    /** Every finger down: `[id, x, y]`, x and y in 0–1 of the picture. */
    fun touches(fingers: List<Triple<Int, Float, Float>>) {
        val n = fingers.size.coerceAtMost(10)
        val pkt = ByteArray(2 + n * 5)
        pkt[0] = 1
        pkt[1] = n.toByte()
        fingers.take(n).forEachIndexed { i, (id, x, y) ->
            val xi = (x.coerceIn(0f, 1f) * 65535).toInt()
            val yi = (y.coerceIn(0f, 1f) * 65535).toInt()
            val at = 2 + i * 5
            pkt[at] = id.toByte()
            pkt[at + 1] = (xi shr 8).toByte(); pkt[at + 2] = xi.toByte()
            pkt[at + 3] = (yi shr 8).toByte(); pkt[at + 4] = yi.toByte()
        }
        send(pkt)
    }

    /** Text from the phone's keyboard, typed on the PC as is. */
    fun text(text: String) {
        val bytes = text.take(1024).toByteArray()
        val pkt = ByteArray(3 + bytes.size)
        pkt[0] = 2
        pkt[1] = (bytes.size shr 8).toByte(); pkt[2] = bytes.size.toByte()
        bytes.copyInto(pkt, 3)
        send(pkt)
    }

    /** One named key (XKB: Return, BackSpace, Left…) with [mods] (shift, ctrl, alt, super). */
    fun key(name: String, mods: Collection<String> = emptyList()) {
        val bytes = name.take(32).toByteArray()
        val mask = mods.sumOf { listOf("shift", "ctrl", "alt", "super").indexOf(it).let { i -> if (i < 0) 0 else 1 shl i } }
        val pkt = ByteArray(3 + bytes.size)
        pkt[0] = 3
        pkt[1] = bytes.size.toByte()
        bytes.copyInto(pkt, 2)
        pkt[2 + bytes.size] = mask.toByte()
        send(pkt)
    }

    private fun send(pkt: ByteArray) {
        val o = out ?: return
        if (!writer.isShutdown) runCatching { writer.execute { runCatching { o.write(pkt); o.flush() } } }
    }

    @Synchronized
    fun stop() {
        running = false
        runCatching { socket?.close() }
        writer.shutdown()
        codec?.let { c -> runCatching { c.stop() }; runCatching { c.release() } }
        codec = null
        codecThread.quitSafely()
    }
}
