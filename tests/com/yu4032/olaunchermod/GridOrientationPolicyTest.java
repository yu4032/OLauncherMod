package com.yu4032.olaunchermod;

import org.junit.Test;
import static org.junit.Assert.*;

public class GridOrientationPolicyTest {
    @Test public void landscapeUsesEightColumnsFourRows() {
        assertArrayEquals(new int[]{8,4},
                GridOrientationPolicy.resolve(8,4,2560,1600,1,4,8));
    }

    @Test public void portraitSwapsColumnsAndRows() {
        assertArrayEquals(new int[]{4,8},
                GridOrientationPolicy.resolve(8,4,1600,2560,2,8,4));
    }

    @Test public void secondLandscapeRotationRemainsLandscape() {
        assertArrayEquals(new int[]{8,4},
                GridOrientationPolicy.resolve(8,4,2560,1600,2,4,8));
    }

    @Test public void secondPortraitRotationRemainsPortrait() {
        assertArrayEquals(new int[]{4,8},
                GridOrientationPolicy.resolve(8,4,1600,2560,1,8,4));
    }

    @Test public void fallbackToConfigurationWhenDisplayNotReady() {
        assertArrayEquals(new int[]{4,8},
                GridOrientationPolicy.resolve(8,4,0,0,1,8,4));
    }

    @Test public void fallbackToNativeAxesOnlyWhenNoOrientationEvidence() {
        assertArrayEquals(new int[]{4,8},
                GridOrientationPolicy.resolve(8,4,0,0,0,4,6));
        assertArrayEquals(new int[]{8,4},
                GridOrientationPolicy.resolve(8,4,0,0,0,6,4));
    }

    @Test public void squareDisplayPrefersExplicitConfiguration() {
        assertArrayEquals(new int[]{4,8},
                GridOrientationPolicy.resolve(8,4,1800,1800,1,8,4));
    }

    @Test public void noCustomGridPreservesNativeBounds() {
        assertArrayEquals(new int[]{6,4},
                GridOrientationPolicy.resolve(0,0,1600,2560,1,6,4));
    }
}
