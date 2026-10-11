# jsoup ссылается на необязательные библиотеки, которых в приложении нет
-dontwarn com.google.re2j.**
-dontwarn org.jspecify.**
-dontwarn javax.annotation.**

# Понятные отчёты о сбоях: не переименовываем классы и сохраняем номера строк
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# OkHttp ссылается на необязательные TLS-библиотеки
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
