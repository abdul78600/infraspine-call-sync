package com.infraspine.callsync.ui.player

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object WaveformDecoder {

    private const val NUM_BARS = 150
    private const val TIMEOUT_US = 2000L
    private const val MAX_SAMPLES = 8000  // cap memory: stop after this many RMS chunks

    suspend fun decode(context: Context, fileUri: String): FloatArray = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, Uri.parse(fileUri), null)

            val audioTrack = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return@withContext flatWave()

            extractor.selectTrack(audioTrack)
            val format = extractor.getTrackFormat(audioTrack)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return@withContext flatWave()

            val codec = runCatching { MediaCodec.createDecoderByType(mime) }.getOrNull()
                ?: return@withContext flatWave()

            codec.configure(format, null, null, 0)
            codec.start()

            val rmsChunks = mutableListOf<Float>()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (idx >= 0) {
                        val buf = codec.getInputBuffer(idx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(idx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIdx >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    val buf = codec.getOutputBuffer(outIdx)
                    if (buf != null && info.size > 0 && rmsChunks.size < MAX_SAMPLES) {
                        buf.position(info.offset)
                        val shorts = ShortArray(info.size / 2)
                        buf.asShortBuffer().get(shorts)
                        if (shorts.isNotEmpty()) {
                            var sum = 0.0
                            for (s in shorts) sum += s.toLong() * s.toLong()
                            rmsChunks.add((Math.sqrt(sum / shorts.size) / Short.MAX_VALUE).toFloat())
                        }
                    }
                    if (rmsChunks.size >= MAX_SAMPLES) outputDone = true
                    codec.releaseOutputBuffer(outIdx, false)
                }
            }

            codec.stop()
            codec.release()

            if (rmsChunks.isEmpty()) return@withContext flatWave()
            downsampleAndNormalize(rmsChunks)
        } catch (e: Exception) {
            flatWave()
        } finally {
            extractor.release()
        }
    }

    private fun downsampleAndNormalize(chunks: List<Float>): FloatArray {
        val result = FloatArray(NUM_BARS)
        val step = chunks.size.toFloat() / NUM_BARS
        for (i in 0 until NUM_BARS) {
            val from = (i * step).toInt()
            val to = ((i + 1) * step).toInt().coerceAtMost(chunks.size)
            result[i] = if (from < to) chunks.subList(from, to).max() ?: 0f else 0f
        }
        val max = result.max()?.takeIf { it > 0f } ?: return result
        for (i in result.indices) result[i] = (result[i] / max).coerceIn(0.05f, 1f)
        return result
    }

    private fun flatWave() = FloatArray(NUM_BARS) { 0.3f }
}
