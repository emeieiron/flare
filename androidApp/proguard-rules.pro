# Flare release rules. Most libraries ship their own; these cover the ones that don't.

# The Aptos SDK signs through fastkrypto, a Rust library whose UniFFI bindings reach native code
# through JNA. JNA finds native functions by the bound interface's method names and reads Structure
# fields by name, so renaming either breaks signing at runtime. Neither library ships rules.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class xyz.mcxross.fastkrypto.** { *; }
-dontwarn java.awt.**

# Readable stack traces from release crashes; the mapping file restores the names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
