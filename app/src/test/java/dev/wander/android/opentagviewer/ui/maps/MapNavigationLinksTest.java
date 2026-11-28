package dev.wander.android.opentagviewer.ui.maps;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The navigation links handed to each map application.
 *
 * <p>A plain JVM test. What matters here is not that a string is produced but that it carries the
 * destination and the <em>right</em> coordinate system: this app stores WGS-84 and each of the
 * three Chinese apps wants it expressed differently, so a wrong flag is not a cosmetic bug - it
 * is a few hundred metres of error once the user is following directions.
 */
public class MapNavigationLinksTest {

    /** 北京天安门附近，WGS-84。 */
    private static final double LAT = 39.90469;
    private static final double LON = 116.40717;

    private static final String NAME = "自行车";

    @Test
    public void knownPackagesEachGetTheirOwnProtocol() {
        assertTrue(MapNavigationLinks.forPackage(MapNavigationLinks.PACKAGE_AMAP, LAT, LON, NAME)
                .startsWith("androidamap://navi"));
        assertTrue(MapNavigationLinks.forPackage(MapNavigationLinks.PACKAGE_BAIDU, LAT, LON, NAME)
                .startsWith("baidumap://map/direction"));
        assertTrue(MapNavigationLinks.forPackage(MapNavigationLinks.PACKAGE_TENCENT, LAT, LON, NAME)
                .startsWith("qqmap://map/routeplan"));
    }

    @Test
    public void anUnknownPackageGetsNoSpecificLink() {
        assertNull(MapNavigationLinks.forPackage("com.example.maps", LAT, LON, NAME));
        assertNull(MapNavigationLinks.forPackage("com.google.android.apps.maps", LAT, LON, NAME));
    }

    // ---------------------------------------------------------------- 高德

    @Test
    public void amapCarriesTheDestination() {
        final String link = MapNavigationLinks.forPackage(
                MapNavigationLinks.PACKAGE_AMAP, LAT, LON, NAME);

        assertTrue("latitude missing: " + link, link.contains("lat=39.9046900"));
        assertTrue("longitude missing: " + link, link.contains("lon=116.4071700"));
    }

    /**
     * <b>dev=1 is the whole coordinate question.</b> Amap documents it as "lat and lon are not
     * yet GCJ-02 and need encrypting", which is exactly what WGS-84 from this app is. Sending
     * dev=0 would make Amap take our WGS-84 as if it were already GCJ-02 and plot it wrong.
     */
    @Test
    public void amapIsToldTheCoordinatesStillNeedEncrypting() {
        final String link = MapNavigationLinks.forPackage(
                MapNavigationLinks.PACKAGE_AMAP, LAT, LON, NAME);

        assertTrue("expected dev=1 for WGS-84 input: " + link, link.contains("dev=1"));
        assertFalse("dev=0 would mean the coordinates were already GCJ-02: " + link,
                link.contains("dev=0"));
    }

    /** Amap says it does not guarantee service unless the caller names itself. */
    @Test
    public void amapNamesTheCallingApplication() {
        final String link = MapNavigationLinks.forPackage(
                MapNavigationLinks.PACKAGE_AMAP, LAT, LON, NAME);

        assertTrue("sourceApplication missing: " + link,
                link.contains("sourceApplication=com.javaoraspx.opentagviewer"));
    }

    @Test
    public void amapCarriesThePointName() {
        final String link = MapNavigationLinks.forPackage(
                MapNavigationLinks.PACKAGE_AMAP, LAT, LON, NAME);

        assertTrue("poiname missing: " + link, link.contains("poiname="));
    }

    /** Amap 8.2.6 ignores it on Android, so passing it would only look like it worked. */
    @Test
    public void amapDoesNotPassTheIgnoredStyleParameter() {
        final String link = MapNavigationLinks.forPackage(
                MapNavigationLinks.PACKAGE_AMAP, LAT, LON, NAME);

        assertFalse("style is ignored from Amap 8.2.6: " + link, link.contains("style="));
    }

    // ---------------------------------------------------------------- 百度

    @Test
    public void baiduCarriesTheDestinationInItsOwnFormat() {
        final String link = MapNavigationLinks.forPackage(
                MapNavigationLinks.PACKAGE_BAIDU, LAT, LON, NAME);

        // destination is URL-encoded, so "latlng:" arrives as "latlng%3A" and the separator
        // between coordinates as %2C.
        assertTrue("destination missing: " + link, link.contains("destination="));
        assertTrue("latitude missing: " + link, link.contains("39.9046900"));
        assertTrue("longitude missing: " + link, link.contains("116.4071700"));
    }

    /**
     * Baidu defaults to bd09ll, which would put a WGS-84 point several hundred metres off.
     * coord_type has to say so explicitly.
     */
    @Test
    public void baiduIsToldTheCoordinatesAreWgs84() {
        final String link = MapNavigationLinks.forPackage(
                MapNavigationLinks.PACKAGE_BAIDU, LAT, LON, NAME);

        assertTrue("coord_type=wgs84 missing: " + link, link.contains("coord_type=wgs84"));
    }

    @Test
    public void baiduAsksForDrivingDirections() {
        final String link = MapNavigationLinks.forPackage(
                MapNavigationLinks.PACKAGE_BAIDU, LAT, LON, NAME);

        assertTrue("mode missing: " + link, link.contains("mode=driving"));
    }

    @Test
    public void baiduNamesItsSourceInTheRequiredShape() {
        final String link = MapNavigationLinks.forPackage(
                MapNavigationLinks.PACKAGE_BAIDU, LAT, LON, NAME);

        assertTrue("src missing or wrong shape: " + link, link.contains("src=andr."));
    }

    // ---------------------------------------------------------------- 腾讯

    @Test
    public void tencentCarriesTheDestination() {
        final String link = MapNavigationLinks.forPackage(
                MapNavigationLinks.PACKAGE_TENCENT, LAT, LON, NAME);

        assertTrue("latitude missing: " + link, link.contains("39.9046900"));
        assertTrue("longitude missing: " + link, link.contains("116.4071700"));
    }

    /** Nothing here may claim to know where the user is: the phone's own app does. */
    @Test
    public void tencentStartsFromTheUsersOwnLocation() {
        final String link = MapNavigationLinks.forPackage(
                MapNavigationLinks.PACKAGE_TENCENT, LAT, LON, NAME);

        assertTrue("fromcoord missing: " + link, link.contains("fromcoord=CurrentLocation"));
    }

    @Test
    public void tencentAsksForADrivingRoute() {
        final String link = MapNavigationLinks.forPackage(
                MapNavigationLinks.PACKAGE_TENCENT, LAT, LON, NAME);

        assertTrue("type missing: " + link, link.contains("type=drive"));
    }

    // ---------------------------------------------------------------- geo:

    @Test
    public void genericIsAStandardGeoUri() {
        final String link = MapNavigationLinks.generic(LAT, LON, NAME);

        assertTrue("not a geo: uri: " + link, link.startsWith("geo:39.9046900,116.4071700"));
    }

    /**
     * RFC 5870 allows a label after the coordinates, which is what used to be missing: the old
     * link carried the position only, so the map app had nothing to show as a destination name.
     */
    @Test
    public void genericCarriesTheNameAsALabel() {
        final String link = MapNavigationLinks.generic(LAT, LON, NAME);

        assertTrue("label missing: " + link, link.contains("?q="));
        assertTrue("name missing: " + link, link.contains("%E8%87%AA%E8%A1%8C%E8%BD%A6"));
    }

    /** geo: is defined as WGS-84, so no conversion may happen on this path. */
    @Test
    public void genericKeepsTheCoordinatesUntouched() {
        final String link = MapNavigationLinks.generic(LAT, LON, null);

        assertEquals("geo:39.9046900,116.4071700", link);
    }

    // ---------------------------------------------------------------- 名字为空时

    @Test
    public void noNameOmitsTheNameParametersRatherThanSendingBlank() {
        assertEquals("geo:39.9046900,116.4071700", MapNavigationLinks.generic(LAT, LON, null));
        assertFalse("blank poiname would show an empty title",
                MapNavigationLinks.forPackage(MapNavigationLinks.PACKAGE_AMAP, LAT, LON, null)
                        .contains("poiname="));
        assertFalse("blank to would show an empty destination",
                MapNavigationLinks.forPackage(MapNavigationLinks.PACKAGE_TENCENT, LAT, LON, null)
                        .contains("&to="));
    }

    @Test
    public void whitespaceOnlyNamesCountAsNoName() {
        assertFalse(MapNavigationLinks.forPackage(MapNavigationLinks.PACKAGE_AMAP, LAT, LON, "   ")
                .contains("poiname="));
    }

    /**
     * Baidu's destination is a compound value ({@code latlng:..|name:..}) that has to be
     * encoded as a whole. Encoding the name first and then the whole thing again turns 自 into
     * %25E8%2587%25AA, which Baidu decodes as a literal percent sign - the destination name on
     * its navigation page comes out as mojibake.
     *
     * <p>Caught by printing the links rather than by a test, so it is worth a test now.
     */
    @Test
    public void baiduEncodesTheDestinationExactlyOnce() {
        final String link = MapNavigationLinks.forPackage(
                MapNavigationLinks.PACKAGE_BAIDU, LAT, LON, NAME);

        assertFalse("double-encoded: %25 means a % was itself encoded: " + link,
                link.contains("%25"));
        assertTrue("name should appear encoded once: " + link,
                link.contains("%E8%87%AA%E8%A1%8C%E8%BD%A6"));
    }

    /** The compound value's own separators have to survive as encoded characters. */
    @Test
    public void baiduKeepsItsOwnSeparatorsEncoded() {
        final String link = MapNavigationLinks.forPackage(
                MapNavigationLinks.PACKAGE_BAIDU, LAT, LON, NAME);

        assertTrue("':' of latlng: must be encoded: " + link, link.contains("latlng%3A"));
        assertTrue("'|' before name must be encoded: " + link, link.contains("%7C"));
    }
    @Test
    public void anOddNameDoesNotBreakTheLink() {
        final String link = MapNavigationLinks.forPackage(
                MapNavigationLinks.PACKAGE_BAIDU, LAT, LON, "我的 车 & 钥匙");

        assertTrue("destination lost: " + link, link.contains("39.9046900"));
        assertTrue("destination lost: " + link, link.contains("116.4071700"));
    }

    // ---------------------------------------------------------------- 精度

    @Test
    public void coordinatesKeepEnoughPrecisionToBeUseful() {
        // 7 位小数约 1 厘米。少一位就是 10 厘米到 1 米,在导航里还看不出来,但没必要丢。
        final String link = MapNavigationLinks.generic(39.90468961234, 116.40717349999, null);

        assertEquals("geo:39.9046896,116.4071735", link);
    }

    @Test
    public void southernAndWesternHemispheresSurvive() {
        final String link = MapNavigationLinks.generic(-33.8688197, -151.2092955, null);

        assertEquals("geo:-33.8688197,-151.2092955", link);
    }

    // ---------------------------------------------------------------- 出行方式

    /**
     * 高德没有"步行导航"这个入口，{@code navi} 只做驾车。所以除驾车外的三种必须换到
     * {@code route}，否则用户选了步行、进去仍然是一条汽车路线——比没有选择更糟，因为他会
     * 以为自己选的没生效。
     */
    @Test
    public void amapSendsEverythingButDrivingToRoutePlanning() {
        final String walking = MapNavigationLinks.forPackage(
                MapNavigationLinks.PACKAGE_AMAP, LAT, LON, NAME,
                MapNavigationLinks.TravelMode.WALKING);

        assertTrue("walking must not go through navi: " + walking,
                walking.startsWith("androidamap://route"));
        assertTrue("walking is t=2: " + walking, walking.contains("&t=2"));
        assertTrue("destination missing: " + walking, walking.contains("dlat=39.9046900"));
        assertTrue("destination missing: " + walking, walking.contains("dlon=116.4071700"));
        // route 的目的地名参数叫 dname，navi 才叫 poiname。
        assertTrue("dname missing: " + walking, walking.contains("&dname="));
        assertFalse("poiname belongs to navi: " + walking, walking.contains("poiname="));
    }

    /** 坐标系和驾车那条路一样：route 也必须拿到 dev=1，否则偏几百米。 */
    @Test
    public void amapRouteAlsoDeclaresWgs84() {
        final String link = MapNavigationLinks.forPackage(
                MapNavigationLinks.PACKAGE_AMAP, LAT, LON, NAME,
                MapNavigationLinks.TravelMode.WALKING);

        assertTrue("dev=1 missing: " + link, link.contains("dev=1"));
        assertFalse("dev=0 means already GCJ-02: " + link, link.contains("dev=0"));
    }

    @Test
    public void amapNumbersTheOtherModesAsDocumented() {
        final String transit = MapNavigationLinks.forPackage(
                MapNavigationLinks.PACKAGE_AMAP, LAT, LON, NAME,
                MapNavigationLinks.TravelMode.TRANSIT);
        final String cycling = MapNavigationLinks.forPackage(
                MapNavigationLinks.PACKAGE_AMAP, LAT, LON, NAME,
                MapNavigationLinks.TravelMode.CYCLING);

        assertTrue("transit is t=1: " + transit, transit.contains("&t=1"));
        assertTrue("cycling is t=3: " + cycling, cycling.contains("&t=3"));
    }

    /** 驾车仍然直接进导航态：这条路上没有 t 参数，也不该突然多出来。 */
    @Test
    public void amapKeepsDrivingOnTheNavigationScheme() {
        final String link = MapNavigationLinks.forPackage(
                MapNavigationLinks.PACKAGE_AMAP, LAT, LON, NAME,
                MapNavigationLinks.TravelMode.DRIVING);

        assertTrue("driving should stay on navi: " + link, link.startsWith("androidamap://navi"));
        assertFalse("navi has no route type: " + link, link.contains("&t="));
    }

    @Test
    public void baiduRenamesTheModeForEachTravelMode() {
        final String walking = MapNavigationLinks.forPackage(
                MapNavigationLinks.PACKAGE_BAIDU, LAT, LON, NAME,
                MapNavigationLinks.TravelMode.WALKING);
        final String cycling = MapNavigationLinks.forPackage(
                MapNavigationLinks.PACKAGE_BAIDU, LAT, LON, NAME,
                MapNavigationLinks.TravelMode.CYCLING);
        final String transit = MapNavigationLinks.forPackage(
                MapNavigationLinks.PACKAGE_BAIDU, LAT, LON, NAME,
                MapNavigationLinks.TravelMode.TRANSIT);

        assertTrue("walking: " + walking, walking.contains("mode=walking"));
        assertTrue("cycling is riding in Baidu's words: " + cycling, cycling.contains("mode=riding"));
        assertTrue("transit: " + transit, transit.contains("mode=transit"));
    }

    /** 腾讯只认 drive / bus / walk。骑行按步行给：路线是对的，只是不一定允许自行车。 */
    @Test
    public void tencentTranslatesTheModesItDocuments() {
        final String transit = MapNavigationLinks.forPackage(
                MapNavigationLinks.PACKAGE_TENCENT, LAT, LON, NAME,
                MapNavigationLinks.TravelMode.TRANSIT);
        final String cycling = MapNavigationLinks.forPackage(
                MapNavigationLinks.PACKAGE_TENCENT, LAT, LON, NAME,
                MapNavigationLinks.TravelMode.CYCLING);
        final String walking = MapNavigationLinks.forPackage(
                MapNavigationLinks.PACKAGE_TENCENT, LAT, LON, NAME,
                MapNavigationLinks.TravelMode.WALKING);

        assertTrue("transit is bus: " + transit, transit.contains("type=bus"));
        assertTrue("cycling borrows walk: " + cycling, cycling.contains("type=walk"));
        assertTrue("walking: " + walking, walking.contains("type=walk"));
    }

    /** 老调用点（还有测试）不带方式，语义保持"驾车"，不要悄悄变成步行。 */
    @Test
    public void omittingTheModeKeepsDriving() {
        assertEquals(
                MapNavigationLinks.forPackage(MapNavigationLinks.PACKAGE_BAIDU, LAT, LON, NAME,
                        MapNavigationLinks.TravelMode.DRIVING),
                MapNavigationLinks.forPackage(MapNavigationLinks.PACKAGE_BAIDU, LAT, LON, NAME));
        // null 与省略同义：调用方拿到的是一个可能为空的用户选择。
        assertEquals(
                MapNavigationLinks.forPackage(MapNavigationLinks.PACKAGE_AMAP, LAT, LON, NAME,
                        MapNavigationLinks.TravelMode.DRIVING),
                MapNavigationLinks.forPackage(MapNavigationLinks.PACKAGE_AMAP, LAT, LON, NAME,
                        null));
    }
}
