# BiliDiy R8 规则（release）
# 崩溃栈保留行号，方便定位线上问题
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 收紧包名/成员名，增加逆向阅读成本
-repackageclasses 'a'
-allowaccessmodification
-overloadaggressively
-adaptclassstrings

# Kotlin / Java 注解与泛型签名
-keepattributes Signature,InnerClasses,EnclosingMethod,Annotation

# 没有反射、没有 getIdentifier 动态取资源，因此不做整包 keep
# 组件（Activity）由 Manifest 声明，R8 自动保留

# Media3 的 PlayerView 在布局里用全类名引用，兜底保留其公共 API
-keep class androidx.media3.ui.PlayerView { *; }

# 弹幕层由 XML 全类名反射实例化，保留类和三个构造函数
-keep class com.gdk.biliapp.DanmakuView { *; }
-keepclassmembers class com.gdk.biliapp.DanmakuView {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# 移除调试日志
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}

# 第三方库可能引用的可选注解
-dontwarn org.jetbrains.annotations.**
-dontwarn javax.annotation.**
