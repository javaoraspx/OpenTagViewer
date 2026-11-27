# OpenTagViewer —— release 混淆规则
#
# 从 1.2.3 起 release 开启 R8（isMinifyEnabled / isShrinkResources）。这里只写「不写就会坏」
# 的规则：能靠 consumer rules 解决的问题不重复写，能用具体类名的地方不用通配符——通配符是
# 体积和反射错误的常见来源。

# ---------------------------------------------------------------------------
# 崩溃栈可读性
# ---------------------------------------------------------------------------
# 保留行号，否则线上崩溃日志里全是 `a.a(Unknown Source)`，等于没有日志。
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 注解在运行时被读取（Jackson / Room / Lombok 生成物）
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, Exceptions

# ---------------------------------------------------------------------------
# 高德地图 SDK
# ---------------------------------------------------------------------------
# 高德 AAR 自带 consumer rules，类名与 native 方法由它自己保护。这里只补几条它没覆盖、
# 而我们的代码依赖的部分。
#
# 1) MapsInitializer 的静态状态必须在 Application 里就能写（我们直接调用，不是反射）。
-keep class com.amap.api.maps.MapsInitializer { *; }

# 2) 高德按类名反射创建地图视图，R8 重命名后会找不到。
-keep class com.amap.api.maps.MapView { *; }
-keep class com.amap.api.maps.TextureMapView { *; }

# 3) SDK 内部存在按字段名访问的模型类，字段名不能改。
-keepclassmembers class com.amap.api.maps.model.** { *; }

# ---------------------------------------------------------------------------
# 本项目 —— 反射 / 序列化 / 注解处理
# ---------------------------------------------------------------------------
# Jackson：数据类被按字段名反序列化。字段名一改，接口解析就全空。
-keepclassmembers class dev.wander.android.opentagviewer.data.model.** {
    <fields>;
    <init>(...);
}
-keepclassmembers class dev.wander.android.opentagviewer.db.room.entity.** {
    <fields>;
    <init>(...);
}
# Lombok 生成的 @Data 类：getter/setter 名与字段名一一对应，被 R8 改名后
# Room 的列映射和 DataStore 的键就对不上了。
-keepclassmembers class dev.wander.android.opentagviewer.db.repo.model.** {
    <fields>;
    *** get*();
    void set*(***);
}

# Room 生成的 *Impl 由 Room 用反射实例化。
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# ---------------------------------------------------------------------------
# Chaquopy / Python 桥
# ---------------------------------------------------------------------------
# Python 侧会按名字访问部分 Java 类，这些类名在 python/ 目录里以字符串出现，R8 看不到。
-keep class dev.wander.android.opentagviewer.python.** { *; }
-dontwarn com.chaquo.python.**

# Python 侧通过 Chaquopy 的 JavaObject 按方法名字符串反射调用 AnisetteSource 的实现。
#
# 这个方法表是 identity.py 与 main.py 里 `localAnisette.<name>()` 的全部调用：
#   ensureReady, unavailableReason, isChangingMachineIdentity, otp, machine,
#   router, recordSessionProvenance, wasSessionEstablishedLocally,
#   hardwareProfileJson, deviceIdsJson, serial, describe
#
# 这些调用在 Java 代码里没有任何一处出现，所以 R8 会认为它们无人使用：无参的
# serial()/hardwareProfileJson()/deviceIdsJson()/otp()/machine()/router()/describe()
# 被整段删除，ensureReady()/unavailableReason()/wasSessionEstablishedLocally()
# 被改名成 a/b/c。Python 于是拿到 `'v' object has no attribute 'serial'`（v 是
# EmulatedMacAnisette 混淆后的名字），把本地 Anisette 判为失败、回退到远端服务器，
# 而服务器一旦 500/502 登录就彻底失败。原始未开 R8 的构建没有这个问题。
#
# 只保留被反射调用的方法就够了，不必 keep 整个实现类：Python 拿到的是对象引用，
# 不按类名查找，类名随它混淆。失配从「登录失败」降级为「日志里类名难读」。
-keep interface dev.wander.android.opentagviewer.anisette.AnisetteSource { *; }
-keepclassmembers class * implements dev.wander.android.opentagviewer.anisette.AnisetteSource {
    public boolean ensureReady();
    public java.lang.String unavailableReason();
    public boolean isChangingMachineIdentity();
    public java.lang.String otp();
    public java.lang.String machine();
    public java.lang.String router();
    public void recordSessionProvenance(boolean);
    public boolean wasSessionEstablishedLocally();
    public java.lang.String hardwareProfileJson();
    public java.lang.String deviceIdsJson();
    public java.lang.String serial();
    public java.lang.String describe();
}

# ---------------------------------------------------------------------------
# 第三方库的已知噪音
# ---------------------------------------------------------------------------
# 这些类在编译期存在、运行时按需加载，R8 会因为「找不到」而报警，缺了它们不影响运行。
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
-dontwarn javax.lang.model.element.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.codehaus.mojo.**
-dontwarn java.beans.**
