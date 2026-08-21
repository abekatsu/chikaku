# kotlinx.serialization — @Serializable クラスのメタデータを保持する
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
    static **$* *;
}
-keepclassmembers class **$* implements kotlinx.serialization.internal.GeneratedSerializer {
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# kotlinx.serialization: @Serializable が生成する serializer をリフレクションで引くため、
# 該当クラスと生成メンバを残す。
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.damburisoft.chikaku.watch.data.** {
    *** Companion;
}
-keepclasseswithmembers class com.damburisoft.chikaku.watch.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp が参照するオプショナルな実装。存在しなくても動く。
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
