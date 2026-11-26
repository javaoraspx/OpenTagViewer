package dev.wander.android.opentagviewer.util.android;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

/**
 * The real {@link PhoneLocation}: the cached fix the system already holds.
 *
 * <p><b>Framework {@link LocationManager}, not Play services.</b> This used to go through
 * {@code FusedLocationProviderClient}, which pulled in {@code play-services-location} - for one
 * method, on a path that must keep working on phones without Play services at all.
 * {@code getLastKnownLocation} answers the same question ("what does the system already know?")
 * from the platform, asks for nothing the app was not already granted, and starts no new
 * location request either way.
 *
 * <p>The provider preference below mirrors what the fused provider used to do: the freshest
 * cached fix wins, and no new fix is ever requested.
 */
public class FusedPhoneLocation implements PhoneLocation {
    private static final String TAG = "PhoneLocation";

    /**
     * Providers to try, in order of trust.
     *
     * <p>{@code gps} is the accurate one; {@code network} survives indoors and is what a fused
     * provider usually returns; {@code passive} is whatever another app happened to receive and
     * costs nothing to read - which is exactly the "cached fix" this class is after, so it is the
     * last resort rather than the first choice. All three are reads of an existing answer.
     */
    private static final String[] PROVIDERS = {
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
    };

    /**
     * The accuracy claimed when the system reports none of its own.
     *
     * <p>Hearing a Find My advertisement is itself a distance measurement of sorts: the tag was
     * in Bluetooth range. That is the weakest true statement available, so it is the right
     * fallback - see {@code NearbyTagLabel} on why RSSI cannot narrow it further.
     */
    private static final long BLUETOOTH_RANGE_M = 10L;

    private final Context context;
    private final LocationManager locationManager;

    public FusedPhoneLocation(final Context context) {
        this.context = context.getApplicationContext();
        this.locationManager =
                (LocationManager) this.context.getSystemService(Context.LOCATION_SERVICE);
    }

    @Nullable
    @Override
    @SuppressLint("MissingPermission")
    public Fix lastKnown() {
        if (!this.locationGranted()) {
            // Not an error: the map asks for this permission, and somebody who declined it has
            // said they do not want their position recorded. The sighting still records what it
            // heard, minus the position.
            return null;
        }

        if (this.locationManager == null) {
            return null;
        }

        try {
            final Location location = this.bestCachedFix();
            if (location == null) {
                return null;
            }

            // hasAccuracy() is false on a fix from a provider that does not report one. Zero
            // would then be written as "accurate to the metre", which is a stronger claim than
            // anything here can make - so it becomes the width of Bluetooth range instead, which
            // is what hearing the tag actually established.
            final long accuracy = location.hasAccuracy()
                    ? Math.round(location.getAccuracy())
                    : BLUETOOTH_RANGE_M;

            return new Fix(location.getLatitude(), location.getLongitude(), accuracy);
        } catch (final SecurityException e) {
            // Thrown if the permission was revoked between the check above and the read.
            Log.d(TAG, "Location permission vanished mid-read", e);
            return null;
        } catch (final Exception e) {
            Log.d(TAG, "No cached location available for this sighting", e);
            return null;
        }
    }

    /**
     * The freshest cached fix from any of {@link #PROVIDERS}, or null when none has one.
     *
     * <p>Freshest rather than most accurate: all three are cached answers, so the newer one is
     * the one that describes where the phone is now. The accuracy each claims is preserved on the
     * fix itself, so nothing is overstated by the choice.
     */
    @Nullable
    @SuppressLint("MissingPermission")
    private Location bestCachedFix() {
        Location best = null;

        for (final String provider : PROVIDERS) {
            if (!this.providerEnabled(provider)) {
                continue;
            }
            final Location candidate;
            try {
                candidate = this.locationManager.getLastKnownLocation(provider);
            } catch (final IllegalArgumentException | SecurityException e) {
                // Provider not present on this device, or not permitted: try the next one.
                continue;
            }
            if (candidate != null && (best == null || candidate.getTime() > best.getTime())) {
                best = candidate;
            }
        }

        return best;
    }

    private boolean providerEnabled(final String provider) {
        try {
            return this.locationManager.isProviderEnabled(provider);
        } catch (final Exception e) {
            return false;
        }
    }

    private boolean locationGranted() {
        return ContextCompat.checkSelfPermission(this.context, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this.context, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }
}
