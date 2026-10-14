# kotlinx.serialization ships its own R8 rules; keep the generated serializers of
# the Nemi models explicitly so tokens stored by one app version decode in the next.
-keep,includedescriptorclasses class com.guuslab.nemi.**$$serializer { *; }
-keepclassmembers class com.guuslab.nemi.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
