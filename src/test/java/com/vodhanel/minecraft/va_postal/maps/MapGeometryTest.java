package com.vodhanel.minecraft.va_postal.maps;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapGeometryTest {
    @Test
    void theWidestSpanFillsTheMapInsideTheMargin() {
        MapGeometry.Projection p = MapGeometry.fit(List.of(new double[]{0, 0}, new double[]{100, 50}), 10);
        assertArrayEquals(new int[]{10, 37}, p.pixel(0, 0)); // x spans the map; z is centred
        assertArrayEquals(new int[]{117, 90}, p.pixel(100, 50));
    }

    @Test
    void negativeCoordinatesWork() {
        MapGeometry.Projection p = MapGeometry.fit(List.of(new double[]{-200, -200}, new double[]{-100, -100}), 0);
        assertArrayEquals(new int[]{0, 0}, p.pixel(-200, -200));
        assertArrayEquals(new int[]{127, 127}, p.pixel(-100, -100));
    }

    @Test
    void oneOrNoPointsSitInTheMiddle() {
        assertArrayEquals(new int[]{64, 64}, MapGeometry.fit(List.of(new double[]{500, -20}), 10).pixel(500, -20));
        assertArrayEquals(new int[]{64, 64}, MapGeometry.fit(List.of(), 10).pixel(0, 0));
    }

    @Test
    void pointsOutsideAreKeptOnTheMap() {
        MapGeometry.Projection p = MapGeometry.fit(List.of(new double[]{0, 0}, new double[]{10, 10}), 0);
        assertArrayEquals(new int[]{127, 0}, p.pixel(1000, -1000));
    }

    @Test
    void cursorsAreTwoUnitsPerPixelFromTheCentre() {
        MapGeometry.Projection p = MapGeometry.fit(List.of(new double[]{0, 0}, new double[]{127, 127}), 0);
        assertArrayEquals(new byte[]{-128, -128}, p.cursor(0, 0));
        assertArrayEquals(new byte[]{0, 0}, p.cursor(64, 64));
        assertArrayEquals(new byte[]{126, 126}, p.cursor(127, 127));
    }

    @Test
    void aLineRunsEndToEndWithoutGaps() {
        List<int[]> l = MapGeometry.line(new int[]{0, 0}, new int[]{5, 2});
        assertArrayEquals(new int[]{0, 0}, l.get(0));
        assertArrayEquals(new int[]{5, 2}, l.get(l.size() - 1));
        assertEquals(6, l.size());
        for (int i = 1; i < l.size(); i++) {
            assertTrue(Math.abs(l.get(i)[0] - l.get(i - 1)[0]) <= 1 && Math.abs(l.get(i)[1] - l.get(i - 1)[1]) <= 1);
        }
        assertEquals(1, MapGeometry.line(new int[]{3, 3}, new int[]{3, 3}).size());
    }
}
