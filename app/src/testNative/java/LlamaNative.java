package com.lumena.android.llama;

import java.io.FileDescriptor;
import java.io.RandomAccessFile;

// Host smoke test of the real JNI bridge; no model or Android device required.
// Deliberately outside Gradle unit sources: this uses a host CPU shared library.
class LlamaNative {
    static { System.loadLibrary("lumena_llama"); }
    native String nativeVersion();
    native String nativeTemplateFallbackForArchitecture(String architecture);
    native String nativeProbeModelFd(int fd);
    native long nativeLoadModelFd(int fd, int gpuLayers);
    native String nativeLastError();
    native String nativeGenerate(long handle, String prompt, int context, int tokens,
                                float temperature, int threads, int batch);

    static void require(boolean condition) {
        if (!condition) throw new AssertionError("JNI smoke assertion failed");
    }

    public static void main(String[] args) throws Exception {
        LlamaNative bridge = new LlamaNative();
        require(bridge.nativeVersion().contains("llama.cpp"));
        require(bridge.nativeTemplateFallbackForArchitecture("gemma4").equals("gemma"));
        require(bridge.nativeTemplateFallbackForArchitecture("gemma").equals("gemma"));
        require(bridge.nativeTemplateFallbackForArchitecture("llama").isEmpty());
        try {
            bridge.nativeGenerate(0, "hello", 512, 16, 0.1f, 1, 32);
            throw new AssertionError("Missing model must throw, not return empty success");
        } catch (IllegalStateException expected) {
            require(expected.getMessage().contains("Embedded generation failed. No active model."));
        }
        require(bridge.nativeProbeModelFd(-1).startsWith("ERROR"));
        require(bridge.nativeLoadModelFd(-1, 0) == 0);
        try (RandomAccessFile pipe = new RandomAccessFile(args[0], "rw")) {
            var field = FileDescriptor.class.getDeclaredField("fd");
            field.setAccessible(true);
            int fd = field.getInt(pipe.getFD());
            require(bridge.nativeProbeModelFd(fd).contains("not seekable"));
            require(bridge.nativeLoadModelFd(fd, 0) == 0);
            require(bridge.nativeLastError().contains("not seekable"));
        }
        System.out.println("JNI smoke passed: Gemma template fallback, explicit generation failure, invalid FD, non-seekable FD");
    }
}
