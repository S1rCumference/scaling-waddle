# Optional runtimes are resolved by name at startup, so keep their entry points.
-keep class com.recorder.core.asr.SherpaAsrEnginePlugin { *; }
-keep class com.recorder.core.llm.local.LlamaCppPlugin { *; }
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class android.llama.cpp.** { *; }
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
