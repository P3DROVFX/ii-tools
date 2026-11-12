# Apache SSHD (the file server) finds ciphers, factories and its I/O service by name.
-keep class org.apache.sshd.** { *; }
-dontwarn org.apache.sshd.**
-dontwarn org.slf4j.**
-dontwarn javax.management.**
-dontwarn org.bouncycastle.**
-dontwarn net.i2p.crypto.eddsa.**
-dontwarn org.apache.tomcat.jni.**
