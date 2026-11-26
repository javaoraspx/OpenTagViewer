package dev.wander.android.opentagviewer.ui.maps;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import dev.wander.android.opentagviewer.ble.FindMyAdvertisement.BatteryLevel;

/**
 * The value comparison the sheet's redraw short-circuit rests on.
 *
 * <p>A plain JVM test: it builds {@link TagListSheetAdapter.Entry} objects and compares them. The
 * adapter itself never gets a view here - what is being checked is whether two draws of the same
 * data produce entries that compare equal, which is what lets {@code submit} skip the rebind.
 */
public class TagListSheetAdapterTest {

    private static TagListSheetAdapter.Entry entry(final String age) {
        return new TagListSheetAdapter.Entry(
                "beacon-1", "自行车", "🚲", "海淀区中关村大街 1 号", age, BatteryLevel.FULL);
    }

    @Test
    public void identicalFieldsAreEqual() {
        assertEquals(entry("3 分钟前"), entry("3 分钟前"));
    }

    /** The whole point: a re-draw with unchanged content must not look like a change. */
    @Test
    public void twoDrawsOfTheSameDataAreEqual() {
        assertEquals(entry("3 分钟前"), entry("3 分钟前"));
        assertEquals(entry("3 分钟前").hashCode(), entry("3 分钟前").hashCode());
    }

    @Test
    public void aChangedFieldBreaksEquality() {
        assertNotEquals(entry("3 分钟前"), entry("4 分钟前"));
    }

    /**
     * The same address and name, but the time moved on far enough to be redrawn. It happens on
     * its own every minute, so it is worth pinning down as "a real change".
     */
    @Test
    public void aNewerRelativeTimeIsADifferentRow() {
        assertNotEquals(entry("59 秒前"), entry("1 分钟前"));
    }

    @Test
    public void aMissingBatteryIsNotTheSameAsAFullOne() {
        final TagListSheetAdapter.Entry withoutBattery = new TagListSheetAdapter.Entry(
                "beacon-1", "自行车", "🚲", "海淀区中关村大街 1 号", "3 分钟前", null);

        assertNotEquals(withoutBattery, entry("3 分钟前"));
        assertEquals(withoutBattery, new TagListSheetAdapter.Entry(
                "beacon-1", "自行车", "🚲", "海淀区中关村大街 1 号", "3 分钟前", null));
    }

    @Test
    public void aDifferentTagIsADifferentRow() {
        assertNotEquals(entry("3 分钟前"), new TagListSheetAdapter.Entry(
                "beacon-2", "自行车", "🚲", "海淀区中关村大街 1 号", "3 分钟前", BatteryLevel.FULL));
    }

    /**
     * How {@code submit} actually decides. Lists compare element by element, so this is the shape
     * the short-circuit sees, and it must hold for the same reason.
     */
    @Test
    public void listsOfEqualEntriesCompareEqual() {
        final List<TagListSheetAdapter.Entry> first =
                Arrays.asList(entry("3 分钟前"), entry("5 分钟前"));
        final List<TagListSheetAdapter.Entry> second =
                Arrays.asList(entry("3 分钟前"), entry("5 分钟前"));

        assertTrue(first.equals(second));
        assertEquals(first, second);
    }

    @Test
    public void aReorderedListIsADifferentList() {
        final List<TagListSheetAdapter.Entry> first =
                Arrays.asList(entry("3 分钟前"), entry("5 分钟前"));
        final List<TagListSheetAdapter.Entry> second =
                Arrays.asList(entry("5 分钟前"), entry("3 分钟前"));

        assertNotEquals(first, second);
    }

    @Test
    public void anEmptyListMatchesAnEmptyList() {
        assertEquals(Collections.<TagListSheetAdapter.Entry>emptyList(),
                Collections.<TagListSheetAdapter.Entry>emptyList());
    }
}
