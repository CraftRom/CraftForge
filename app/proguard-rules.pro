# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Зберігаємо номери рядків для стектрейсів
-keepattributes SourceFile,LineNumberTable

# Retrofit & Gson
-keep class retrofit2.** { *; }
-keep class com.google.gson.** { *; }
-keepattributes Signature
-keepattributes *Annotation*

# Glide
-keep class com.bumptech.glide.** { *; }
-keep interface com.bumptech.glide.** { *; }
-keep enum com.bumptech.glide.** { *; }

# Lifecycle & ViewModel
-keep class androidx.lifecycle.** { *; }

# Navigation
-keep class androidx.navigation.** { *; }

# DataBinding
-keep class androidx.databinding.** { *; }
-keepclassmembers class * {
    @androidx.databinding.* <fields>;
}