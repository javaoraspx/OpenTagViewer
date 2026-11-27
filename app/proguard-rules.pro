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
# **整个 SDK 一起 keep，不挑类。** 挑类试过一次，代价是一次线上级崩溃。
#
# 高德的 AAR 自带 consumer rules，我们以前只补它漏掉的几条（MapsInitializer 的静态状态、
# 按类名反射创建的 MapView / TextureMapView、按字段名访问的 model）。这些确实必要，但都不
# 够——因为 SDK 的**原生库按字符串回 Java 找类**，而那些字符串在 .so 里面，R8 看不见，
# 也改不出一条对应的映射。我们唯一能做的事就是「别改名」。
#
# 1.2.9 的 R8 构建就栽在这一步。libAMapOpenMapJNI.so 在自己的 JNI_OnLoad 里第一件事是
# FindClass("com/autonavi/base/amap/mapcore/ClassTools")，紧接着对它 GetStaticMethodID：
#
#   Pending exception java.lang.ClassNotFoundException:
#     com.autonavi.base.amap.mapcore.ClassTools
#   JNI DETECTED ERROR IN APPLICATION: java_class == null
#     in call to GetStaticMethodID ... from Runtime.nativeLoad
#   Fatal signal 6 (SIGABRT) in tid NNNN (GLThread)
#
# AAR 的 consumer rules 只 keep 了 com.autonavi 下少数几个类（MsgProcessor、GLMapEngine、
# GLMapState、NetworkProxyManager、GLOverlayBundle、AMapNativeGlOverlayLayer），ClassTools
# 不在名单里，被改名成 m1.i0.u，FindClass 拿到 null，原生侧的 CHECK 直接 abort。栈是 GL
# 线程的 onSurfaceCreated → System.loadLibrary，所以表现是「地图一显示就闪退」，之后每次
# 进地图都崩；而且这是原生 abort，Java 侧 try/catch 接不住。
#
# 这不是一个类的问题。libAMapSDK_MAP_v11_3_100.so 里一共出现 90 个 com/amap、com/autonavi
# 的类名，1.2.9 的 dex 里其中 86 个已经不存在（MarkerOptions、PolylineOptions、
# GlyphLoader……），只是还没走到那条路径而已。逐个补是在拿崩溃换崩溃，所以整包 keep。
#
# 代价是 com.amap / com.autonavi 下的死代码不再被裁掉，dex 大一点；换来的是原生库按名字
# 找的任何东西都找得到。宁可大，不可崩。
-keep class com.amap.** { *; }
-keep class com.autonavi.** { *; }
-keep class com.loc.** { *; }

# ---------------------------------------------------------------------------
# 本项目 —— 反射 / 序列化 / 注解处理
# ---------------------------------------------------------------------------
# Jackson 反序列化：ObjectMapper.readValue() 按字段名赋值，并且**必须**先有一个无参
# 构造函数来 new 出实例。这些类都由 Lombok @NoArgsConstructor 生成该构造，但 Java 源码里
# 没有任何一处显式调用它——R8 只看显式调用，会把这个"没人调用"的构造整段删掉，Jackson
# 于是报 "Cannot construct instance of X (no Creators, like default constructor, exist)"。
#
# 1.2.3 开 R8 之后埋下的坑：1.2.8 修好了 Anisette 的同类反射问题，登录终于能过，于是启动
# 流程第一次真正走到 UserAuthRepository.getUserAuth() 的 readValue(UserAuthData.class)，
# 撞上被删掉的构造，AppleLoginActivity.onCreate 直接崩，形成"点图标就闪退"的崩溃循环。
#
# **1.2.9 补上构造之后，紧接着撞上第二层：类上的注解也没了。**
#
# keepclassmembers 只保证「类还活着的时候，它的成员不被删」，它不 keep 这个类；而
# -keepattributes 保留的注解属性只对 keep 住的类生效。于是运行时：
#   - @JsonIgnoreProperties(ignoreUnknown = true) 不存在 → FindMy.py 存下的
#     {"type": "account", "ids": ...} 直接抛 Unrecognized field "type"，又是启动即崩；
#   - @JsonProperty("account_name") 不存在 → JSON 里是 snake_case、字段是 camelCase，
#     账户名一律读成 null。
#
# 所以挂着这些注解、又靠字段名反序列化的包，必须 -keep 整个类：注解要活到运行时，
# 字段名一个都不能改。代价只是这些类不再被混淆（几十个类的名字而已）。
-keep class dev.wander.android.opentagviewer.db.repo.model.** { *; }
-keep class dev.wander.android.opentagviewer.service.web.sidestore.** { *; }

# data.model 与 room entity 没有注解参与，按字段名和构造反射即可，成员保住就够了。
-keepclassmembers class dev.wander.android.opentagviewer.data.model.** {
    <fields>;
    <init>(...);
}
-keepclassmembers class dev.wander.android.opentagviewer.db.room.entity.** {
    <fields>;
    <init>(...);
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

# 高德：整包 keep 之后，"找不到的类"第一次全部进入 R8 的可达图。
#
# 1.2.10 的 release 构建就死在这里（`minifyReleaseWithR8`），R8 报的一共就两个类：
#
#   ERROR: Missing classes detected while running R8. ...
#   ERROR: R8: Missing class com.amap.ams.gnss.GnssSoftLocator (referenced from: void
#     com.amap.location.fusion.original.a.a.<init>(boolean, com.amap.location.support.
#     handler.AmapLooper))
#   Missing class net.jafama.FastMath (referenced from: byte[] com.autonavi.aps.protocol.
#     aps.request.model.fields.context.b.e())
#
# 两个都是可选依赖：GnssSoftLocator 属于 GNSS 软定位模块（合包里没有），jafama 是一套快速
# 数学库，高德的空中协议代码拿它算三角函数。以前只 keep 三五个类，这些引用跟着死代码一起
# 被裁掉，R8 从来没报过；现在 com.amap / com.autonavi / com.loc 整包 keep，引用全部可达，
# 于是构建在最后一步失败。
#
# **-dontwarn 按"被缺的类名"匹配，不是按引用方。** com.autonavi 的代码引用 net.jafama，
# 写 com.autonavi.** 不管用，必须写 net.jafama.**——这是这次唯一需要单独补的一条。
#
# 缺的类不影响已存在的代码路径：真走到才会 NoClassDefFoundError，而走到它的前提是设备上
# 装了这些可选模块。所以按包静音，而不是去补一堆不存在的类。
-dontwarn com.amap.**
-dontwarn com.autonavi.**
-dontwarn com.loc.**
-dontwarn net.jafama.**
