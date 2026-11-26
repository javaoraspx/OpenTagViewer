package dev.wander.android.opentagviewer.ui.maps;

import android.util.Log;

import androidx.annotation.VisibleForTesting;

import java.util.function.Supplier;

/**
 * 地图提供商工厂。
 * <br>
 * <b>AMap is the only provider.</b> This class used to choose between Google Maps, AMap and
 * OpenStreetMap, and to fall back to OpenStreetMap whenever the build had no usable Google
 * key. That choice cost a great deal for a Chinese-market build:
 * <ul>
 *   <li>the Google Maps and Places SDKs - megabytes of dex for a map that cannot load without
 *       Play Services and a key restricted to a signing fingerprint;</li>
 *   <li>a whole second and third implementation of every map operation, each with its own
 *       bugs, and screens that branched on which one was active;</li>
 *   <li>a default of "Google", so a fresh install drew a grey rectangle until the user found
 *       the setting.</li>
 * </ul>
 * AMap is the map this app is for: it works without Play Services, and the app already asks the
 * user for their own key. So the factory no longer chooses - it answers AMap.
 */
public class MapProviderFactory {
    private static final String TAG = MapProviderFactory.class.getSimpleName();

    /** Kept because stored settings still contain the old values; only AMap is honoured. */
    public static final String PROVIDER_AMAP = "amap";

    /**
     * A provider to hand out instead of a real one, or null in production.
     *
     * <p>The instrumented tests run on a device without Play Services and without a map key, so
     * a screen that builds a real map cannot start there at all. Providers are behind
     * {@link IMapProvider}, so a fake is another implementation rather than a change to the
     * screens.
     */
    private static Supplier<IMapProvider> replacement = null;

    @VisibleForTesting
    public static void replaceWith(final Supplier<IMapProvider> factory) {
        replacement = factory;
    }

    /** Put the real one back. Call from a teardown, or the next test inherits the fake. */
    @VisibleForTesting
    public static void reset() {
        replacement = null;
    }

    /**
     * The provider to actually use.
     * <br>
     * Always AMap now. Kept as a method with the old signature so that settings screens and
     * stored preferences that still say "google" or "osm" keep loading - they land here and are
     * answered with AMap instead of a map that cannot draw.
     */
    public static String effectiveProvider(final android.content.Context context, final String providerType) {
        if (providerType != null && !PROVIDER_AMAP.equals(providerType)) {
            Log.i(TAG, "Provider '" + providerType + "' is no longer bundled; using AMap");
        }
        return PROVIDER_AMAP;
    }

    public static IMapProvider create(String providerType) {
        if (replacement != null) {
            Log.d(TAG, "Creating a substituted map provider");
            return replacement.get();
        }
        return new AMapProvider();
    }
}
