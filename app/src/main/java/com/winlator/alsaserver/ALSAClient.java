package com.winlator.alsaserver;

import android.content.Context;
import android.media.AudioManager;

import com.winlator.math.Mathf;
import com.winlator.sysvshm.SysVSharedMemory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Winlator ALSA server 客戶端狀態；PCM 寫入改走 AAudio（x86aaudio）。
 */
public class ALSAClient {
    public static final boolean USE_SHARED_MEMORY = true;
    public static final byte BUFFER_OFFSET = 4;

    static {
        System.loadLibrary("x86aaudio");
    }

    public enum DataType {
        U8(1), S16LE(2), S16BE(2), FLOATLE(4), FLOATBE(4);
        public final byte byteCount;
        DataType(int byteCount) { this.byteCount = (byte) byteCount; }
    }

    public static class Options {
        public static final short DEFAULT_LATENCY_MILLIS = 40;
        public static final float DEFAULT_VOLUME = 1.0f;
        public short latencyMillis = DEFAULT_LATENCY_MILLIS;
        public float volume = DEFAULT_VOLUME;
    }

    private DataType dataType = DataType.S16LE;
    private byte channels = 2;
    private int sampleRate = 0;
    private int position;
    private int bufferSize;
    private byte frameBytes;
    private ByteBuffer sharedBuffer;
    private ByteBuffer auxBuffer;
    private ByteBuffer writeScratch; // U8→S16 / endian
    protected final Options options;
    private static short framesPerBuffer = 256;
    private boolean prepared;

    public ALSAClient(Options options) {
        this.options = options != null ? options : new Options();
    }

    public void release() {
        if (sharedBuffer != null) {
            SysVSharedMemory.unmapSHMSegment(sharedBuffer, sharedBuffer.capacity());
            sharedBuffer = null;
        }
        auxBuffer = null;
        writeScratch = null;
        nativeAAudioRelease();
        prepared = false;
        position = 0;
    }

    public void prepare() {
        position = 0;
        frameBytes = (byte) (channels * dataType.byteCount);
        release();
        if (!isValidBufferSize()) return;

        int enc;
        switch (dataType) {
            case FLOATLE:
            case FLOATBE:
                enc = 2;
                break;
            case U8:
                enc = 1; // 升 S16 於 write
                break;
            default:
                enc = 1;
                break;
        }
        int bufferFrames = Math.max(bufferSize, (int) framesPerBuffer);
        int rc = nativeAAudioPrepare(sampleRate, channels, enc, bufferFrames);
        if (rc != 0) return;
        nativeAAudioStart();
        prepared = true;
        if (options.volume != 1.0f) {
            // AAudio 無簡易 volume；由系統混音器處理
        }
    }

    public void start() {
        if (prepared) nativeAAudioStart();
    }

    public void stop() {
        nativeAAudioStop();
    }

    public void pause() {
        nativeAAudioPause();
    }

    public void drain() {
        // AAudio 無 flush 等價；停→再開可選，這裡 no-op
    }

    public void writeDataToTrack(ByteBuffer data) {
        if (!prepared || data == null) return;
        data.position(0);
        ByteBuffer out = data;
        int nbytes = data.remaining();

        if (dataType == DataType.U8) {
            // 升 S16 LE
            int samples = nbytes;
            if (writeScratch == null || writeScratch.capacity() < samples * 2) {
                writeScratch = ByteBuffer.allocateDirect(samples * 2).order(ByteOrder.LITTLE_ENDIAN);
            }
            writeScratch.clear();
            for (int i = 0; i < samples; i++) {
                int u = data.get(i) & 0xff;
                short s = (short) ((u - 128) << 8);
                writeScratch.putShort(s);
            }
            writeScratch.flip();
            out = writeScratch;
            nbytes = writeScratch.remaining();
        } else if (dataType == DataType.S16BE || dataType == DataType.FLOATBE) {
            // 轉 LE
            if (writeScratch == null || writeScratch.capacity() < nbytes) {
                writeScratch = ByteBuffer.allocateDirect(nbytes).order(ByteOrder.LITTLE_ENDIAN);
            }
            writeScratch.clear();
            ByteBuffer be = data.duplicate().order(ByteOrder.BIG_ENDIAN);
            if (dataType == DataType.S16BE) {
                while (be.remaining() >= 2) writeScratch.putShort(be.getShort());
            } else {
                while (be.remaining() >= 4) writeScratch.putFloat(be.getFloat());
            }
            writeScratch.flip();
            out = writeScratch;
            nbytes = writeScratch.remaining();
        } else {
            out.order(ByteOrder.LITTLE_ENDIAN);
        }

        int written = nativeAAudioWrite(out, nbytes);
        if (written > 0) position += written;
        data.rewind();
    }

    public int pointer() {
        return prepared ? nativeAAudioPointer() : 0;
    }

    public void setDataType(DataType dataType) { this.dataType = dataType; }
    public void setChannels(int channels) { this.channels = (byte) channels; }
    public void setSampleRate(int sampleRate) { this.sampleRate = sampleRate; }
    public void setBufferSize(int bufferSize) { this.bufferSize = bufferSize; }

    public ByteBuffer getSharedBuffer() { return sharedBuffer; }

    public void setSharedBuffer(ByteBuffer sharedBuffer) {
        if (sharedBuffer != null) {
            auxBuffer = ByteBuffer.allocateDirect(getBufferSizeInBytes()).order(ByteOrder.LITTLE_ENDIAN);
            this.sharedBuffer = sharedBuffer.order(ByteOrder.LITTLE_ENDIAN);
        } else {
            auxBuffer = null;
            this.sharedBuffer = null;
        }
    }

    public ByteBuffer getAuxBuffer() { return auxBuffer; }
    public DataType getDataType() { return dataType; }
    public byte getChannels() { return channels; }
    public int getSampleRate() { return sampleRate; }
    public int getBufferSize() { return bufferSize; }
    public int getBufferSizeInBytes() { return bufferSize * frameBytes; }

    public static int latencyMillisToBufferSize(int latencyMillis, int channels, DataType dataType, int sampleRate) {
        byte frameBytes = (byte) (channels * dataType.byteCount);
        int bufferSize = (int) Mathf.roundTo((latencyMillis * sampleRate) / 1000.0f, framesPerBuffer, false);
        return bufferSize * frameBytes;
    }

    private boolean isValidBufferSize() {
        // bufferSize 為 frames（與 getBufferSizeInBytes 一致）；勿對 frameBytes 取模
        return frameBytes > 0 && bufferSize > 0;
    }

    public static void assignFramesPerBuffer(Context context) {
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            String framesPerBufferStr = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER);
            framesPerBuffer = Short.parseShort(framesPerBufferStr);
            if (framesPerBuffer == 0) framesPerBuffer = 256;
        } catch (Exception e) {
            framesPerBuffer = 256;
        }
    }

    private static native int nativeAAudioPrepare(int sampleRate, int channels, int pcmEncoding, int bufferFrames);
    private static native int nativeAAudioStart();
    private static native int nativeAAudioStop();
    private static native int nativeAAudioPause();
    private static native int nativeAAudioWrite(ByteBuffer direct, int nbytes);
    private static native int nativeAAudioPointer();
    private static native void nativeAAudioRelease();
}
