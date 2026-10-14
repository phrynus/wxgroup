# 入口类不能被混淆/裁剪，xposed_init 里写的是全限定名
-keep class com.example.wxgroup.XposedEntry { *; }

# 钩子回调是重写框架方法（afterHookedMethod 等），模块内部没人调用它，
# 加一道保险，免得被 R8 当成「没人用的方法」删掉
-keep class * extends de.robv.android.xposed.XC_MethodHook { *; }
