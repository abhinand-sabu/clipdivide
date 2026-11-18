package app.clipdivide;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.IOException;
import java.util.Random;

public class SplitPlannerTest {
    @Test
    public void countSplitSupportsThreeHourTimestamps() throws Exception {
        SplitEngine.Source source = source(10_800_000_000L, 2_000_000L);
        SplitEngine.Plan plan = SplitEngine.plan(source, 7);
        assertEquals(7, plan.count());
        assertEquals(0, plan.boundaries[0]);
        assertEquals(source.durationUs, plan.boundaries[7]);
        assertValid(source, plan);
    }

    @Test
    public void durationSplitRetainsFinalRemainder() throws Exception {
        SplitEngine.Source source = source(125_000_000L, 1_000_000L);
        SplitEngine.Plan plan = SplitEngine.planDuration(source, 60);
        assertArrayEquals(new long[] {0, 60_000_000L, 120_000_000L, 125_000_000L}, plan.boundaries);
    }

    @Test
    public void approximateSizeUsesDecimalMegabytes() throws Exception {
        SplitEngine.Source source = source(300_000_000L, 1_000_000L);
        source.bytes = 508_000_000L;
        assertEquals(6, SplitEngine.planSize(source, 100).count());
    }

    @Test
    public void customTimesSplitTheWholeSourceAndSnapToKeyframes() throws Exception {
        SplitEngine.Source source = source(120_000_000L, 2_000_000L);
        SplitEngine.Plan plan = SplitEngine.planCuts(source, new long[] {30_300_000L, 80_700_000L});
        assertArrayEquals(new long[] {0, 30_000_000L, 80_000_000L, 120_000_000L}, plan.boundaries);
        assertValid(source, plan);
    }

    @Test
    public void malformedInputsAndUnusableCountsAreRejected() throws Exception {
        SplitEngine.Source source = source(10_000_000L, 2_000_000L);
        assertThrows(IOException.class, () -> SplitEngine.plan(source, 1));
        assertThrows(IOException.class, () -> SplitEngine.plan(source, 1000));
        assertThrows(IOException.class, () -> SplitEngine.plan(source, 6));
        assertThrows(IOException.class, () -> SplitEngine.planDuration(source, Double.NaN));
        assertThrows(IOException.class, () -> SplitEngine.planDuration(source, 0));
        assertThrows(IOException.class, () -> SplitEngine.planSize(source, 100));
        assertThrows(
                IOException.class,
                () -> SplitEngine.planCuts(source, new long[] {8_000_000, 3_000_000}));
        assertThrows(
                IOException.class, () -> SplitEngine.planCuts(source, new long[] {10_000_000}));
    }

    @Test
    public void cachedExportPlanRejectsStaleOrInvalidBoundaries() throws Exception {
        SplitEngine.Source source = source(10_000_000L, 2_000_000L);
        assertThrows(
                IOException.class,
                () -> SplitEngine.restorePlan(source, new long[] {0, 3_000_000, 10_000_000}));
        assertThrows(
                IOException.class,
                () -> SplitEngine.restorePlan(source, new long[] {0, 2_000_000, 9_000_000}));
        assertValid(source, SplitEngine.restorePlan(source, new long[] {0, 4_000_000, 10_000_000}));
    }

    @Test
    public void balancingNeverWorsensGreedyMaximumError() throws Exception {
        Random random = new Random(42);
        int improved = 0;
        for (int trial = 0; trial < 500; trial++) {
            SplitEngine.Source source = new SplitEngine.Source();
            source.durationUs = 60_000_000;
            source.keys.add(0L);
            long key = 0;
            while ((key += 500_000 + random.nextInt(3_500_000)) < source.durationUs)
                source.keys.add(key);
            int count = 2 + random.nextInt(8);
            SplitEngine.Plan balanced = SplitEngine.plan(source, count);
            long[] greedy = greedy(source, count);
            double before = worst(greedy, source.durationUs);
            double after = worst(balanced.boundaries, source.durationUs);
            assertTrue("Maximum duration error increased", after <= before + 1);
            assertValid(source, balanced);
            if (after < before - 1) improved++;
        }
        assertTrue("Expected improvement for uneven keyframe layouts", improved > 0);
    }

    private static SplitEngine.Source source(long duration, long interval) {
        SplitEngine.Source source = new SplitEngine.Source();
        source.durationUs = duration;
        for (long key = 0; key < duration; key += interval) source.keys.add(key);
        return source;
    }

    private static void assertValid(SplitEngine.Source source, SplitEngine.Plan plan) {
        for (int i = 1; i < plan.boundaries.length; i++) {
            assertTrue(plan.boundaries[i] > plan.boundaries[i - 1]);
            if (i < plan.count()) assertTrue(source.keys.contains(plan.boundaries[i]));
        }
    }

    private static double worst(long[] boundaries, long duration) {
        double ideal = (double) duration / (boundaries.length - 1);
        double maximum = 0;
        for (int i = 1; i < boundaries.length; i++)
            maximum = Math.max(maximum, Math.abs(boundaries[i] - boundaries[i - 1] - ideal));
        return maximum;
    }

    private static long[] greedy(SplitEngine.Source source, int count) {
        long[] boundaries = new long[count + 1];
        boundaries[count] = source.durationUs;
        int previous = 0;
        for (int i = 1; i < count; i++) {
            long target = source.durationUs * i / count;
            int maximum = source.keys.size() - (count - i);
            int best = previous + 1;
            while (best < maximum
                    && Math.abs(source.keys.get(best + 1) - target)
                            < Math.abs(source.keys.get(best) - target)) best++;
            boundaries[i] = source.keys.get(best);
            previous = best;
        }
        return boundaries;
    }
}
