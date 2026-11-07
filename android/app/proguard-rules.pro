# sherpa-onnx: нативный код ищет классы, поля и методы конфигов по имени (JNI),
# а в AAR пустой proguard.txt, поэтому держим весь пакет.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# Колбэк прогресса диаризации: OfflineSpeakerDiarization.processWithCallback вызывает из C++
# метод invoke(IIJ)Ljava/lang/Integer; у переданного объекта. Без правила R8 его вырезает.
-keepclassmembers class * implements kotlin.jvm.functions.Function3 {
    java.lang.Integer invoke(int, int, long);
}
