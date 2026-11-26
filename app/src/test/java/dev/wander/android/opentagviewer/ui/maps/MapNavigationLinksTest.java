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
}
