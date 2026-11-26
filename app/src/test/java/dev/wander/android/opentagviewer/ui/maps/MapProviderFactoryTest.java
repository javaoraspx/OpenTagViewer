package dev.wander.android.opentagviewer.ui.maps;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * AMap 是唯一的地图实现。
 *
 * <p>这个测试以前测的是"Google 有可用 key 就用 Google，否则退到 OpenStreetMap"的那套
 * 选择逻辑。那套逻辑已经不在了：包里只带高德，所以 {@code providerFor} 没有了，
 * 剩下的问题是「老设置里的 'google' / 'osm' 会不会把应用打回一张画不出来的空白地图」。
 * 答案必须是不会。
 */
public class MapProviderFactoryTest {

    @Test
    public void amapStaysAMap() {
        assertEquals(MapProviderFactory.PROVIDER_AMAP,
                MapProviderFactory.effectiveProvider(null, MapProviderFactory.PROVIDER_AMAP));
    }

    /**
     * 老版本把 'google' / 'osm' 写进了 DataStore，升级后这些值还在。
     *
     * <p>如果这里把它们原样透传，{@code create()} 就会去建一个已经不存在的 provider——
     * 升级过的用户看到的是空地图，而不是他升级前用的那张。
     */
    @Test
    public void aStaleProviderSettingIsAnsweredWithAMap() {
        for (final String stale : new String[]{"google", "osm", "bing", ""}) {
            assertEquals("stale setting: " + stale, MapProviderFactory.PROVIDER_AMAP,
                    MapProviderFactory.effectiveProvider(null, stale));
        }
    }

    /** 从没设置过（全新安装）同样是高德，不是「等用户去设置里选一个」。 */
    @Test
    public void anUnsetProviderIsAMap() {
        assertEquals(MapProviderFactory.PROVIDER_AMAP,
                MapProviderFactory.effectiveProvider(null, null));
    }
}
