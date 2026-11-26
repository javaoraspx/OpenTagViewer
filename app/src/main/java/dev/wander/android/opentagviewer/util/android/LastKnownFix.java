package dev.wander.android.opentagviewer.util.android;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.util.concurrent.Executor;

/**
 * "Where is this phone right now, for moving the map there?" - answered from the platform.
 *
 * <p><b>Replaces {@code FusedLocationProviderClient}, which was 3 of the 4 remaining Play services
 * location call sites.</b> Both of them did the same thing with the answer: move the camera if a
 * fix came back, fall back otherwise. That is a callback, so this is one too - but the whole of
 * Play services comes off the dependency list for it.
 *
 * <p>Not on {@link PhoneLocation}: that interface is the blocking seam the sighting write path
 * uses, and its whole design is "the cached fix, read as cheaply as possible". This is the
 * interactive path, where a result arriving a moment later is fine and blocking the main thread
 * is not.
 */
public final class LastKnownFix {

    private static final String TAG = "PhoneLocation";

    /** What to do with the answer. {@code null} means "no fix", not "failed". */
    public interface Callback {
        void onFix(Location location);
    }

    private LastKnownFix() {
    }

    /**
     * Asks for the freshest cached fix and calls {@code callback} on {@code executor}.
     *
     * <p><b>Always calls back, exactly once.</b> A caller that only handles success ends up with a
     * map stuck on its default view whenever location is off, the permission was declined, or no
     * provider has ever run - all of which are ordinary. The read itself is a memory lookup, so
     * the work on {@code executor} is just the callback.
     */
    public static void request(final Context context, final Executor executor,
                               final Callback callback) {
        final Context app = context.getApplicationContext();

        executor.execute(() -> {
            Location fix = null;
            try {
                fix = read(app);
            } catch (final Exception e) {
                Log.d(TAG, "Could not read a cached fix", e);
            }
            callback.onFix(fix);
        });
    }

    @SuppressLint("MissingPermission")
    private static Location read(final Context app) {
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_COARSE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED) {
            return null;
        }

        final LocationManager manager =
                (LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) {
            return null;
        }

        // Same order of preference as FusedPhoneLocation: accurate first, the passive
        // leftovers last. Freshest wins, because all three are cached answers.
        final String[] providers = {
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER,
        };

        Location best = null;
        for (final String provider : providers) {
            final Location candidate;
            try {
                if (!manager.isProviderEnabled(provider)) {
                    continue;
                }
                candidate = manager.getLastKnownLocation(provider);
            } catch (final IllegalArgumentException | SecurityException e) {
                continue;
            }
            if (candidate != null && (best == null || candidate.getTime() > best.getTime())) {
                best = candidate;
            }
        }
        return best;
    }
}
