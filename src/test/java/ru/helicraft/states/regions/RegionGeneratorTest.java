package ru.helicraft.states.regions;

import org.junit.jupiter.api.Test;
import ru.helicraft.states.regions.RegionGenerator;

import static org.junit.jupiter.api.Assertions.*;

class RegionGeneratorTest {
    @Test
    void testComputeConcurrencyZeroUsesCpuTimes2() {
        int cpus = 4;
        int res = RegionGenerator.computeConcurrency(0, cpus);
        assertEquals(cpus * 2, res);
    }

    @Test
    void testComputeConcurrencyPositive() {
        int res = RegionGenerator.computeConcurrency(5, 8);
        assertEquals(5, res);
    }

    @Test
    void testComputeConcurrencyNegativeClamped() {
        int res = RegionGenerator.computeConcurrency(-1, 2);
        assertEquals(4, res); // 2 * 2
    }
}
