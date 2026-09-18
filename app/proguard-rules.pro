# Keep WorkManager worker constructors referenced by class name.
-keep class com.mazhar.apkappstore.UpdateWorker { <init>(...); }

# Keep JSON model parsing code paths used directly by JSONObject.
-keep class org.json.** { *; }

# Suppress optional annotation warnings from dependencies.
-dontwarn javax.annotation.**
