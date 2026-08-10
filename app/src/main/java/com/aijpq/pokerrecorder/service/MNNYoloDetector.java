package com.aijpq.pokerrecorder.service;

import android.graphics.Bitmap;

/** 保持旧JNI类名和签名，仅暴露够级项目需要的最小MNN调用。 */
public final class MNNYoloDetector implements AutoCloseable {
    private long handle;

    static {
        System.loadLibrary("mnnyolodetector");
    }

    public static final class DetectionResult {
        private final float x;
        private final float y;
        private final float width;
        private final float height;
        private final float confidence;
        private final float classId;

        public DetectionResult(
                float x,
                float y,
                float width,
                float height,
                float confidence,
                float classId
        ) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.confidence = confidence;
            this.classId = classId;
        }

        public float getX() { return x; }
        public float getY() { return y; }
        public float getWidth() { return width; }
        public float getHeight() { return height; }
        public float getConfidence() { return confidence; }
        public int getClassId() { return (int) classId; }
    }

    public boolean initialize(String modelPath, String cachePath, boolean useOpenCl, int threads) {
        if (handle == 0L) handle = nativeCreate();
        return handle != 0L && nativeInit(
                handle,
                modelPath,
                cachePath,
                useOpenCl ? 3 : 0,
                threads,
                1,
                0,
                320,
                320,
                false,
                false
        );
    }

    public DetectionResult[] detect(Bitmap bitmap, float confidence, float nms) {
        return nativeDetectObjects(handle, bitmap, confidence, nms);
    }

    public String lastError() {
        return handle == 0L ? "MNN句柄未创建" : nativeGetLastError(handle);
    }

    @Override
    public void close() {
        if (handle != 0L) {
            nativeRelease(handle);
            handle = 0L;
        }
    }

    private native long nativeCreate();
    private native DetectionResult[] nativeDetectObjects(
            long handle,
            Bitmap bitmap,
            float confidence,
            float nms
    );
    private native int nativeGetActualBackend(long handle);
    private native String nativeGetLastError(long handle);
    private native boolean nativeInit(
            long handle,
            String modelPath,
            String cachePath,
            int forwardType,
            int threads,
            int precision,
            int architecture,
            int inputWidth,
            int inputHeight,
            boolean updateCacheAfterInference,
            boolean updateCacheOnRelease
    );
    private native boolean nativeIsInitialized(long handle);
    private native void nativeRelease(long handle);
    private native void nativeSaveDebugImage(byte[] bytes, int width, int height, String path);
    private native void nativeSetRuntimeCacheWritePolicy(
            long handle,
            boolean updateAfterInference,
            boolean updateOnRelease
    );
}
