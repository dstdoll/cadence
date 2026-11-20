# Models are (de)serialized by kotlinx.serialization; its plugin generates the serializers R8 needs.

# The native layer reports errors by calling these two static methods by name (JNI cannot see
# renames): see NativeWrapper.onNativeEvent / onNativeNotice and native/APPLE_ERRORS.md.
-keepclassmembers class app.cadence.backend.engine.media.NativeWrapper {
    public static void onNativeEvent(int, java.lang.String, java.lang.String);
    public static void onNativeNotice(java.lang.String, java.lang.String, java.lang.String, java.lang.String);
}
