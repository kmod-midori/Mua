# Preserve all application/library code, including Shizuku entry points and hidden API overrides.
-keep class moe.reimu.** { *; }

# Compile-time stub for the hidden framework interface used by FakeContext.
-keep class android.content.IContentProvider { *; }
