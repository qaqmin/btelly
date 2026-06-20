# R8 Compatibility Mode 配合 proguard-android-optimize.txt 使用
# 保留行号方便排查崩溃
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Gson / Retrofit 依赖泛型与匿名内部类签名做运行时反射
-keepattributes Signature
-keepattributes InnerClasses,EnclosingMethod
-keepattributes Exceptions
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keep,allowobfuscation class * extends com.google.gson.reflect.TypeToken

# Retrofit — 保留 API 接口的全部方法及泛型签名
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}
-keepclassmembers,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-keep class com.btelly.network.api.** { *; }

# Network response wrappers（泛型类型信息不能被 R8 优化掉）
-keep class com.btelly.network.response.** { *; }

# Gson custom TypeAdapters
-keep class com.btelly.model.adapter.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Model classes
-keep class com.btelly.model.** { *; }

# SponsorBlock / 空降助手
-keep class com.btelly.feature.player.sponsor.** { *; }

# Koin DI
-keep class com.btelly.di.** { *; }
-keep class com.btelly.repository.** { *; }
-keep class com.btelly.event.** { *; }
-keep class com.btelly.core.common.** { *; }
-keep class com.btelly.network.** { *; }

# ViewModel (Koin 反射创建)
-keep class * extends androidx.lifecycle.ViewModel { <init>(...); }
-keep class com.btelly.feature.**ViewModel { *; }

# AkDanmaku（内嵌弹幕引擎，Ashley ECS 使用反射创建组件）
-keep class com.kuaishou.akdanmaku.** { *; }
-keep class com.badlogicgames.ashley.** { *; }
-keep class com.badlogicgames.gdx.** { *; }

# 通用 Android 优化
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit
-dontwarn retrofit2.**

# 移除日志（release 构建）
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Kotlin 协程
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep custom views
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.content.AttributeSet);
}
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.content.AttributeSet, int);
}

# Keep enum
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Parcelable
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

-keep class okio.** { *; }
-dontwarn okio.**

# 腾讯 X5 / TBS 内核
# dalvik.system.VMStack 为 Android 运行时隐藏类，不在 compile SDK 中，TBS 的 DexLoader 在运行时通过反射调用
-dontwarn dalvik.system.VMStack
# X5 内核核心 API（com.tencent.smtt.*）通过反射加载，不能被混淆或裁剪
-keep class com.tencent.smtt.** { *; }
-keep interface com.tencent.smtt.** { *; }
# TBS 下载/加载入口
-keep class com.tencent.tbs.** { *; }
-keep interface com.tencent.tbs.** { *; }
# X5 附属动态加载类（含 DexLoader 等内部实现）
-keep class com.tencent.smtt.export.external.** { *; }
-keep class com.tencent.smtt.sdk.** { *; }
-keep class com.tencent.smtt.utils.** { *; }
# X5 运行时依赖的隐藏类
-dontwarn com.tencent.smtt.**
-dontwarn com.tencent.tbs.**
