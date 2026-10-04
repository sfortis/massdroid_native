package net.asksakis.massdroidv2.data.sendspin

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug builds only: keeps each output calibration's microphone recording and
 * timing so it can be analysed offline, in `files/calibration/` next to the app
 * logs. Written to find out why Bluetooth results disagree between runs; the
 * recording stays on the phone like the logs do.
 */
internal object CalibrationRecordingDump {
    private const val TAG = "AcousticDump"
    private const val DIR = "calibration"

    fun save(
        context: Context,
        signal: ShortArray,
        played: NativeAcousticCalibrator.PlayResult,
        mic: TimestampedRecording,
        outcome: AcousticCalibrationCoordinator.Attempt,
    ) {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        runCatching {
            val dir = File(context.getExternalFilesDir(null), DIR).apply { mkdirs() }
            val signalFile = File(dir, "signal.wav")
            if (!signalFile.exists()) writeWav(signalFile, signal)
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val base = "$stamp-dev${played.routedOutputDeviceId}"
            writeWav(File(dir, "$base.wav"), mic.samples.toPcm())
            File(dir, "$base.json").writeText(
                """
                {"frame0Us":${played.frame0Us},"outSpreadUs":${played.spreadUs},
                "outSamples":${played.timestampSamples},"routedDevice":${played.routedOutputDeviceId},
                "outRate":${played.sampleRate},"frameOffset":${played.frameOffset},
                "micOriginUs":${mic.originUs},"micSpreadUs":${mic.timestampSpreadUs},
                "micSource":"${mic.source}","micRate":${TimestampedMicRecorder.RATE},
                "outcome":"${outcome.toString().replace("\"", "'")}"}
                """.trimIndent()
            )
            Log.d(TAG, "Saved $base")
        }.onFailure { Log.w(TAG, "Couldn't save the calibration recording", it) }
    }

    private fun FloatArray.toPcm(): ShortArray =
        ShortArray(size) { (this[it].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort() }

    /** Mono 16 bit WAV at the calibration rate. */
    private fun writeWav(file: File, pcm: ShortArray) {
        val rate = TimestampedMicRecorder.RATE
        val dataBytes = pcm.size * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + dataBytes); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(dataBytes)
        }
        val body = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        pcm.forEach { body.putShort(it) }
        DataOutputStream(FileOutputStream(file)).use {
            it.write(header.array())
            it.write(body.array())
        }
    }
}
