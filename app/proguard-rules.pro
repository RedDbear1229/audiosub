# Add project specific ProGuard rules here.

# sherpa-onnx JNI classes
-keep class com.k2fsa.sherpa.onnx.** { *; }

# ONNX Runtime
-keep class ai.onnxruntime.** { *; }
-keep class ai.onnxruntime.extensions.** { *; }

# Apache Commons Compress (tar.bz2 extraction)
-keep class org.apache.commons.compress.** { *; }

# WorkManager workers
-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
