package de.codevoid.androsnd

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import java.io.EOFException
import java.io.IOException

/**
 * A [DataSource] for live Icecast/SHOUTcast streams that heals its own connection.
 *
 * A live stream is not a seekable byte range: a server that loses a client and is asked
 * to resume at a byte offset ignores the range and sends the current live edge instead,
 * so ExoPlayer's default byte-offset resume splices live bytes at the wrong offset and
 * wedges the playhead. This source sidesteps that entirely. On any read error or EOF it
 * reopens the URL at the live edge itself, resetting its framing, and keeps returning
 * bytes across the reconnect. Because it never throws and never signals end-of-input while
 * open, the player's loader never errors, so the timeshift buffer already accumulated is
 * kept rather than dumped by a re-prepare. If an offline stretch outlasts the buffer the
 * splice is a jump to live, which beats silence.
 *
 * It also de-interleaves ICY metadata itself. ICY framing is stateful — a metadata block
 * every `icy-metaint` bytes — and a reconnect restarts that framing, so a reconnecting
 * source placed *below* ExoPlayer's own ICY de-interleaver would desync its byte counter
 * and corrupt both audio and titles. Instead this source requests `Icy-MetaData: 1`, strips
 * the metadata blocks, reports `StreamTitle` through [onStreamTitle], and hides `icy-metaint`
 * from [getResponseHeaders] so ExoPlayer does not try to de-interleave a second time.
 */
@UnstableApi
class LiveStreamDataSource(
    private val onStreamTitle: (String) -> Unit
) : DataSource {

    class Factory(private val onStreamTitle: (String) -> Unit) : DataSource.Factory {
        override fun createDataSource(): DataSource = LiveStreamDataSource(onStreamTitle)
    }

    private val transferListeners = mutableListOf<TransferListener>()
    private val httpFactory = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setDefaultRequestProperties(mapOf("Icy-MetaData" to "1"))

    @Volatile private var inner: HttpDataSource? = null
    @Volatile private var closed = false
    private var uri: Uri? = null

    // ICY de-interleave state, touched only on the loader thread (in read()). metaint is the
    // audio-byte interval between metadata blocks (0 = the server sent none); bytesUntilMeta
    // counts down to the next block. metaLenByte is the reusable 1-byte block-length scratch.
    private var metaint = 0
    private var bytesUntilMeta = 0
    private val metaLenByte = ByteArray(1)

    override fun addTransferListener(transferListener: TransferListener) {
        transferListeners.add(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        closed = false
        uri = dataSpec.uri
        openInner()
        return C.LENGTH_UNSET.toLong()
    }

    // Always opens at the live edge (no range), so the framing restarts cleanly and the
    // server never has a byte offset to ignore.
    private fun openInner() {
        val src = httpFactory.createDataSource()
        for (l in transferListeners) src.addTransferListener(l)
        src.open(DataSpec.Builder().setUri(uri!!).build())
        metaint = parseMetaint(src)
        bytesUntilMeta = metaint
        inner = src
    }

    private fun parseMetaint(src: HttpDataSource): Int {
        val entry = src.responseHeaders.entries
            .firstOrNull { it.key.equals("icy-metaint", ignoreCase = true) }
        return entry?.value?.firstOrNull()?.toIntOrNull() ?: 0
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        var reconnectAttempts = 0
        while (!closed) {
            try {
                val src = inner
                if (src != null) {
                    if (metaint <= 0) {
                        val n = src.read(buffer, offset, length)
                        if (n != C.RESULT_END_OF_INPUT) return n
                    } else {
                        if (bytesUntilMeta == 0) readMetadataBlock()
                        val n = src.read(buffer, offset, minOf(length, bytesUntilMeta))
                        if (n != C.RESULT_END_OF_INPUT) {
                            bytesUntilMeta -= n
                            return n
                        }
                    }
                }
            } catch (e: IOException) {
                // A dropout, or a close racing this read; fall through to back off and reopen.
            }
            // The source errored, ended, or is not open. A live drop is not a track end, so
            // reopen at the live edge. Back off first — one quick retry, then a calm cadence —
            // so neither a failing reopen nor a server that accepts a connection and instantly
            // closes it can spin this loop hot.
            sleepInterruptible(if (reconnectAttempts == 0) IMMEDIATE_RETRY_MS else RETRY_CADENCE_MS)
            reconnectAttempts++
            if (closed) break
            closeInnerQuietly()
            try {
                openInner()
            } catch (e: IOException) {
                // Reopen failed; the loop backs off and tries again.
            }
        }
        return C.RESULT_END_OF_INPUT
    }

    private fun readMetadataBlock() {
        readFully(metaLenByte, 1)
        // Reset the counter before reading the block: if the block read fails mid-way the
        // reconnect resets it again anyway, and this keeps the audio path aligned when the
        // block is empty (the common case).
        bytesUntilMeta = metaint
        val size = (metaLenByte[0].toInt() and 0xFF) * 16
        if (size == 0) return
        val block = ByteArray(size)
        readFully(block, size)
        val title = STREAM_TITLE.find(String(block, Charsets.UTF_8)) ?: return
        onStreamTitle(title.groupValues[1].trim())
    }

    private fun readFully(dst: ByteArray, len: Int) {
        var got = 0
        while (got < len) {
            val src = inner ?: throw EOFException()
            val n = src.read(dst, got, len - got)
            if (n == C.RESULT_END_OF_INPUT) throw EOFException()
            got += n
        }
    }

    private fun sleepInterruptible(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (!closed && System.currentTimeMillis() < end) {
            try {
                Thread.sleep(100)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    override fun getUri(): Uri? = uri

    // Report no headers so the player's ICY path stays out entirely: without icy-metaint it
    // does not de-interleave a stream this source already de-interleaves, and without the
    // other icy-* headers it does not publish its own station-name metadata that would fight
    // the titles delivered through onStreamTitle.
    override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()

    override fun close() {
        closed = true
        closeInnerQuietly()
    }

    private fun closeInnerQuietly() {
        try {
            inner?.close()
        } catch (e: IOException) {
            // A close during a dropout is expected; nothing to recover.
        }
        inner = null
    }

    companion object {
        private const val IMMEDIATE_RETRY_MS = 200L
        private const val RETRY_CADENCE_MS = 5000L
        private val STREAM_TITLE = Regex("StreamTitle='(.*?)';")
    }
}
