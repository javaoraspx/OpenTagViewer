package dev.wander.android.opentagviewer.ble;

import androidx.annotation.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * The most recent sighting of each tag, and whether it is recent enough to still show.
 *
 * <p><b>The ageing is the point.</b> A sighting is a claim about a moment, so one left on screen
 * becomes a lie as soon as the tag is carried out of range: the badge would still read "nearby"
 * for a tag that is gone. Nothing tells us it left - we simply stop hearing it - so the only
 * honest rendering is to let the claim expire on its own.
 *
 * <p>No Android in here, and the clock is a parameter, so the expiry rule is covered by a JVM
 * test rather than by watching a screen and waiting.
 */
public final class NearbyTagSightings {

    /**
     * How long a sighting is worth showing.
     *
     * <p>A separated accessory advertises every second or two, but even
     * {@code SCAN_MODE_BALANCED} - see {@code NearbyTagWatcher} - still duty-cycles rather than
     * listening continuously, so gaps of a few seconds between sightings are normal for a tag
     * sitting right next to the phone. This is generous enough to ride those out and short
     * enough that a tag carried away stops claiming to be here within about half a minute.
     *
     * <p>Public because it is the one answer to "how long may a sighting be presented as
     * current", wherever that presentation happens - the device info screen's live battery row
     * ages out on the same clock rather than inventing a second one.
     */
    public static final long FRESH_FOR_MS = TimeUnit.SECONDS.toMillis(30);

    private final Map<String, NearbyTagSighting> latestByBeaconId = new ConcurrentHashMap<>();

    /** Written from the scan callback, read on the main thread, hence the concurrent map. */
    public void record(final NearbyTagSighting sighting) {
        this.latestByBeaconId.put(sighting.getBeaconId(), sighting);
    }

    /**
     * The last sighting of this tag, or null if there is none or it is too old to stand behind.
     */
    @Nullable
    public NearbyTagSighting freshFor(final String beaconId, final long nowMs) {
        final NearbyTagSighting sighting = this.latestByBeaconId.get(beaconId);
        if (sighting == null || nowMs - sighting.getSeenAtMs() >= FRESH_FOR_MS) {
            return null;
        }
        return sighting;
    }

    /**
     * Whether any tag has been heard within {@code windowMs} of {@code nowMs}.
     *
     * <p>For the map screen's redraw rate: while something is audible the cards have to be
     * redrawn every second so the pulse dot can light and dim, and once nothing is, one redraw a
     * minute is enough for the "3 minutes ago" lines. This is what lets that screen tell the
     * two apart without knowing which tags exist.
     *
     * <p><b>The window is the caller's, but 30 seconds - {@link #FRESH_FOR_MS} - is the useful
     * one.</b> A shorter window would call the screen idle two seconds after the last
     * advertisement while the cards still say "Nearby" for another twenty-eight, leaving a lit
     * pulse dot on a tag nobody is listening to any more.
     *
     * <p>O(n) over the tags heard this session, which is tens at most, and it runs once a second.
     */
    public boolean hasAnyWithin(final long nowMs, final long windowMs) {
        for (final NearbyTagSighting sighting : this.latestByBeaconId.values()) {
            if (nowMs - sighting.getSeenAtMs() < windowMs) {
                return true;
            }
        }
        return false;
    }

    /** Drops everything, for when scanning stops and nothing may keep claiming to be current. */
    public void clear() {
        this.latestByBeaconId.clear();
    }
}
