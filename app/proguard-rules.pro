# Worm keeps Media3 session/service classes via their own consumer rules.

# libtorrent4j: SWIG/JNI bindings are looked up by name from native code.
-keep class org.libtorrent4j.swig.** { *; }
-keep class org.libtorrent4j.** { native <methods>; }

# Widget buttons: Glance creates ActionCallback classes by name.
-keep class * implements androidx.glance.appwidget.action.ActionCallback { <init>(); }

# Reader JavaScript bridge (also covered by the default rules; explicit for safety).
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
