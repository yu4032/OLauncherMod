package com.yu4032.olaunchermod;

import java.util.Arrays;

/**
 * Workspace padding adjustments in dp. The six canonical values match
 * LiquidDock's per-orientation {horizontal, top, bottom} layout controls.
 * Landscape/portrait are independent and do NOT rotate margin settings.
 *
 * The old 4-value left/top/right/bottom format is accepted unchanged until
 * the first explicit save from the new UI. Unequal legacy left/right values
 * are reported for user-approved migration, not silently overwritten.
 */
final class WorkspaceMarginConfig {
    static final int LIMIT_DP = 600;
    static final int HORIZONTAL = 0, TOP = 1, BOTTOM = 2;
    static final WorkspaceMarginConfig DEFAULT =
            new WorkspaceMarginConfig(new int[3], new int[3], null);

    private final int[] landscape;
    private final int[] portrait;
    private final int[] legacyEdges; // {left,top,right,bottom} or null

    private WorkspaceMarginConfig(int[] landscape, int[] portrait, int[] legacyEdges) {
        this.landscape = landscape.clone();
        this.portrait = portrait.clone();
        this.legacyEdges = legacyEdges == null ? null : legacyEdges.clone();
    }

    static WorkspaceMarginConfig parse(String text) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("missing margin settings");
        }
        String[] values = text.trim().split("\\s+");
        if (values.length != 4 && values.length != 6) {
            throw new IllegalArgumentException("expected 4 (legacy) or 6 values");
        }
        int[] numbers = new int[values.length];
        for (int i = 0; i < numbers.length; i++) {
            numbers[i] = Integer.parseInt(values[i]);
            if (numbers[i] < -LIMIT_DP || numbers[i] > LIMIT_DP) {
                throw new IllegalArgumentException("margin outside -600..600 dp");
            }
        }
        if (numbers.length == 6) {
            return new WorkspaceMarginConfig(
                    Arrays.copyOfRange(numbers, 0, 3),
                    Arrays.copyOfRange(numbers, 3, 6), null);
        }
        int horizontal = Math.round((numbers[0] + numbers[2]) / 2f);
        int[] current = {horizontal, numbers[1], numbers[3]};
        return new WorkspaceMarginConfig(current, current, numbers);
    }

    String serialize() {
        if (legacyEdges != null) return join(legacyEdges);
        return join(landscape) + " " + join(portrait);
    }

    private static String join(int[] values) {
        StringBuilder result = new StringBuilder();
        for (int value : values) {
            if (result.length() > 0) result.append(' ');
            result.append(value);
        }
        return result.toString();
    }

    int get(boolean portraitMode, int axis) {
        if (axis < HORIZONTAL || axis > BOTTOM) throw new IllegalArgumentException("axis");
        return (portraitMode ? portrait : landscape)[axis];
    }

    boolean isLegacy() { return legacyEdges != null; }

    boolean isLegacyAsymmetric() {
        return legacyEdges != null && legacyEdges[0] != legacyEdges[2];
    }

    WorkspaceMarginConfig with(boolean portraitMode, int axis, int value) {
        if (axis < HORIZONTAL || axis > BOTTOM || value < -LIMIT_DP || value > LIMIT_DP) {
            throw new IllegalArgumentException("invalid margin setting");
        }
        int[] nextL = landscape.clone();
        int[] nextP = portrait.clone();
        (portraitMode ? nextP : nextL)[axis] = value;
        return new WorkspaceMarginConfig(nextL, nextP, null);
    }

    WorkspaceMarginConfig resetOrientation(boolean portraitMode) {
        return new WorkspaceMarginConfig(
                portraitMode ? landscape : new int[3],
                portraitMode ? new int[3] : portrait, null);
    }

    boolean isDefault() {
        if (legacyEdges != null) {
            for (int n : legacyEdges) if (n != 0) return false;
            return true;
        }
        for (int n : landscape) if (n != 0) return false;
        for (int n : portrait) if (n != 0) return false;
        return true;
    }

    int[] adjust(int[] nativePx, float density, int widthPx, int heightPx,
            boolean portraitMode) {
        if (nativePx == null || nativePx.length != 4) {
            throw new IllegalArgumentException("expected four native paddings");
        }
        int[] result = nativePx.clone();
        if (isDefault()) return result;
        if (!(density > 0)) density = 1f;
        final int[] dp;
        if (legacyEdges != null) {
            dp = legacyEdges;
        } else {
            int[] axes = portraitMode ? portrait : landscape;
            dp = new int[]{axes[HORIZONTAL], axes[TOP],
                    axes[HORIZONTAL], axes[BOTTOM]};
        }
        for (int i = 0; i < result.length; i++) {
            long candidate = (long) nativePx[i] + Math.round(dp[i] * density);
            result[i] = (int) Math.max(0L, Math.min(candidate, Integer.MAX_VALUE));
        }
        limitAxis(result, 0, 2, widthPx);
        limitAxis(result, 1, 3, heightPx);
        return result;
    }

    private static void limitAxis(int[] result, int first, int second, int viewport) {
        if (viewport <= 0) return;
        int maxTotal = (int) (viewport * 0.72f);
        long sum = (long) result[first] + result[second];
        if (sum <= maxTotal) return;
        if (maxTotal <= 0) {
            result[first] = result[second] = 0;
            return;
        }
        result[first] = (int) (result[first] * maxTotal / sum);
        result[second] = maxTotal - result[first];
    }
}
