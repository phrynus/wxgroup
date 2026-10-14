# 入口类不能被混淆/裁剪，xposed_init 里写的是全限定名
-keep class com.example.wxgroup.XposedEntry { *; }

# 反射访问的字段名要保持原样（微信侧的字段名，不涉及本模块）
-keepclassmembers class * { *** field_*; }
