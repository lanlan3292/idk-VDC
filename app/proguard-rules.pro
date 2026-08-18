# Keep Shizuku
-keep class rikka.shizuku.** { *; }
-keep interface rikka.shizuku.** { *; }

# Keep our server protocol constants if needed
-keepclassmembers class com.vdcontroller.client.BackendClient {
    public static <fields>;
}
