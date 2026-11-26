package dev.wander.android.opentagviewer.ui.maps;

import androidx.annotation.Nullable;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 构造"导航到这个点"的跳转链接，按手机上装的是哪个地图 App 分别拼。
 *
 * <p><b>为什么不能只用 {@code geo:}。</b> 原来的实现是一条
 * {@code geo:lat,lng?q=lat,lng}，交给系统选择器。国际上这样没问题——Google 地图、Organic
 * Maps、OsmAnd 都按 RFC 5870 解析 {@code q}。但国内的三个地图（高德、百度、腾讯）各有自己的
 * scheme，而且对 {@code geo:} 的处理并不一致：它们能"打开"这个链接，却常常只把点标在地图上，
 * 不认里面的目的地，用户看到的是地图开着、导航没开始。表现就和"没带目的地"一样。
 *
 * <p>所以这里的策略是：认识的 App 就用它自己的协议，把目的地和坐标系一起明确传过去；
 * 不认识的（含 {@code geo:} 本身）仍然走通用链接，让系统选择器去处理。
 *
 * <p><b>坐标系是这里最容易错的地方。</b> 这个 App 内部一律存 WGS-84（GPS 原始坐标），只有画
 * 在自己的高德底图上时才转 GCJ-02。跳出去导航时如果直接甩 WGS-84 给一个以为是 GCJ-02 的
 * App，中国的定位会偏出几百米——在导航里就是把你导到隔壁街区。每家要求的单位还不一样：
 *
 * <ul>
 *   <li>高德：{@code dev=1} 表示"未加密，需要国测加密"，正好是 WGS-84，让它自己转</li>
 *   <li>百度：{@code coord_type=wgs84}</li>
 *   <li>腾讯：不传坐标系参数（它的 Android 文档没有这个参数），按 WGS-84 原样给</li>
 *   <li>{@code geo:}：RFC 5870 就是 WGS-84，原样传</li>
 * </ul>
 *
 * <p>纯 Java，不碰 Android，所以这些字符串有 JVM 测试照着。
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class MapNavigationLinks {

    /** 高德地图的包名。 */
    public static final String PACKAGE_AMAP = "com.autonavi.minimap";

    /** 百度地图的包名。 */
    public static final String PACKAGE_BAIDU = "com.baidu.BaiduMap";

    /** 腾讯地图的包名。 */
    public static final String PACKAGE_TENCENT = "com.tencent.map";

    /**
     * 高德、百度都要求填"调用方应用名"，不填它们不保证服务。
     *
     * <p>用包名而不是界面显示名：它是稳定的、唯一的，也不会因为改翻译而变。
     */
    private static final String SOURCE_APPLICATION = "com.javaoraspx.opentagviewer";

    /** 百度要求的 {@code src} 格式是 {@code andr.companyName.appName}。 */
    private static final String BAIDU_SRC = "andr.javaoraspx.opentagviewer";

    /** 腾讯要求填在腾讯位置服务申请的开发者 key；没有也仍然可用，只是不保证服务统计。 */
    private static final String TENCENT_REFERER = "OpenTagViewer";

    /**
     * 这个包名对应的导航链接，没有专属协议时返回 null（调用方改用 {@link #generic}）。
     *
     * @param pointName 目的地名称，显示在地图 App 的导航页上；可以为 null
     */
    @Nullable
    public static String forPackage(final String packageName,
                                    final double latitudeWgs84,
                                    final double longitudeWgs84,
                                    @Nullable final String pointName) {
        if (PACKAGE_AMAP.equals(packageName)) {
            return amap(latitudeWgs84, longitudeWgs84, pointName);
        }
        if (PACKAGE_BAIDU.equals(packageName)) {
            return baidu(latitudeWgs84, longitudeWgs84, pointName);
        }
        if (PACKAGE_TENCENT.equals(packageName)) {
            return tencent(latitudeWgs84, longitudeWgs84, pointName);
        }
        return null;
    }

    /**
     * 高德：{@code androidamap://navi}。
     *
     * <p>{@code dev=1} 是关键——文档写的是"lat 和 lon 还没有国测加密，需要加密"，也就是
     * 接受 WGS-84 由它自己转 GCJ-02。传 {@code dev=0} 则要求我们先把坐标转好，传错就是
     * 几百米的偏差。
     *
     * <p>不带 {@code style}：Android 版 8.2.6 起该参数被忽略，以用户本地偏好为准，
     * 传了也只会让人以为生效了。
     */
    static String amap(final double lat, final double lon, @Nullable final String pointName) {
        final StringBuilder uri = new StringBuilder("androidamap://navi?sourceApplication=")
                .append(SOURCE_APPLICATION)
                .append("&lat=").append(format(lat))
                .append("&lon=").append(format(lon))
                .append("&dev=1");
        if (isUsableName(pointName)) {
            uri.append("&poiname=").append(encode(pointName));
            // dev=0 时 poiname 才保证被当作目的地名称用；dev=1 下它只是标注。
            // 两者都带上，高德自己决定用哪个。
        }
        return uri.toString();
    }

    /**
     * 百度：{@code baidumap://map/direction}，{@code destination=latlng:lat,lng|name:...}。
     *
     * <p>用 {@code direction} 而不是 {@code navi}：{@code navi} 的一批参数（含
     * {@code navi/common}）依赖 9.5 以上版本，而 {@code direction} 是百度文档里最稳定、
     * 覆盖版本最广的驾车路线入口，进来就是路线规划页，点一下开始导航。
     *
     * <p><b>整串 destination 只编码一次。</b> 名字里的中文如果先单独编码、再随整串编码，
     * 就会变成 {@code %25E8...} —— 百度那边解出来的是一串百分号，导航页的目的地名是乱码。
     * 所以这里把"原始"的 destination 拼好，最后统一编码一次。
     */
    static String baidu(final double lat, final double lon, @Nullable final String pointName) {
        final StringBuilder destination = new StringBuilder("latlng:")
                .append(format(lat)).append(',').append(format(lon));
        if (isUsableName(pointName)) {
            // 未编码的名字，交给下面这一次 encode 处理。
            destination.append("|name:").append(pointName);
        }

        return new StringBuilder("baidumap://map/direction?destination=")
                .append(encode(destination.toString()))
                .append("&coord_type=wgs84")
                .append("&mode=driving")
                .append("&src=").append(BAIDU_SRC)
                .toString();
    }

    /**
     * 腾讯：{@code qqmap://map/routeplan}。
     *
     * <p>{@code fromcoord=CurrentLocation} 让腾讯用自己的定位做起点，我们不必请求定位权限
     * 也不该假装知道用户在哪儿。
     *
     * <p><b>坐标按 WGS-84 原样传。</b> 腾讯的 Android 文档里 {@code routeplan} 的参数表没有
     * {@code coord_type}，只有 web 版的 {@code routeplan} 才有（取值 {@code 1} 表示 GPS）。
     * 手机端既然没声明这个参数，我们就不能假设它默认按 GCJ-02 解——先在这里转一道 GCJ-02
     * 反而可能让它在已经做过转换的基础上再偏一次。传原始 WGS-84 是依据最少、也最可能是
     * 它默认行为的选择。若实测发现腾讯端偏移，再补 {@code coord_type=1} 即可，改动只在
     * 这一处。
     */
    static String tencent(final double lat, final double lon, @Nullable final String pointName) {
        final StringBuilder uri = new StringBuilder("qqmap://map/routeplan?type=drive")
                .append("&fromcoord=CurrentLocation")
                .append("&tocoord=").append(format(lat)).append(',').append(format(lon))
                .append("&referer=").append(TENCENT_REFERER);
        if (isUsableName(pointName)) {
            uri.append("&to=").append(encode(pointName));
        }
        return uri.toString();
    }

    /**
     * 通用链接，给 {@code geo:} 以及我们不认识的地图 App 用。
     *
     * <p>{@code q=lat,lng(name)} 这种写法是 RFC 5870 里的"带标签的坐标"，Google 地图和
     * OsmAnd 都认，会把目的地名字一并显示出来。之前是 {@code q=lat,lng}，名字丢了。
     *
     * <p>坐标保持 WGS-84：{@code geo:} 规定的就是 WGS-84，转成 GCJ-02 反而会让不认识国测
     * 偏移的 App（绝大多数国外 App）偏掉。
     */
    public static String generic(final double lat, final double lon, @Nullable final String pointName) {
        final StringBuilder uri = new StringBuilder("geo:")
                .append(format(lat)).append(',').append(format(lon));
        if (isUsableName(pointName)) {
            uri.append("?q=").append(format(lat)).append(',').append(format(lon))
                    .append('(').append(encode(pointName)).append(')');
        }
        return uri.toString();
    }

    /**
     * 百度地图的方向链接要求整段 destination 先编码，所以内部的 '|' ':' ',' 也得转义。
     *
     * <p><b>{@code URLEncoder} 而不是 {@code android.net.Uri.encode}。</b> 两者对 URI 的处理
     * 不一样，而且 Uri.encode 在 JVM 单元测试里是桩，返回 null —— 这个类正是靠纯 Java 才
     * 测得到的（见 MapNavigationLinksTest）。URLEncoder 把空格写成 '+'，在 URI 的 query
     * 里同样是空格，各家地图都能正确解出来；这里本来也没有需要区分两者的字符。
     */
    private static String encode(final String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (final UnsupportedEncodingException e) {
            // UTF-8 是 Java 保证支持的，走不到这里；真走到了，宁可传原文也不要让导航失败。
            return value;
        }
    }

    /** 七位小数约等于 1 厘米，够用，也不会因为浮点尾数让同一点出现两种写法。 */
    private static String format(final double value) {
        return String.format(Locale.ROOT, "%.7f", value);
    }

    /**
     * 名字为空、全空白、或者只有一个 emoji 的占位时不该往 URL 里塞。
     *
     * <p>标签名可能是用户随手起的单个空格，也可能是未设置时的空串；这两种都会让高德/百度
     * 的导航页显示一个空标题。
     */
    static boolean isUsableName(@Nullable final String name) {
        return name != null && !name.trim().isEmpty();
    }
}
