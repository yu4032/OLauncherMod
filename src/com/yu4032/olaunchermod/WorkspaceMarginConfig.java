package com.yu4032.olaunchermod;

/**
 * Additional Workspace padding, in dp. Never changes icon cell coordinates,
 * profile grid dimensions, or the Launcher database.
 *
 * File format: left top right bottom (signed integers, -160..160).
 * Zero is an exact no-op that preserves ColorOS's native padding.
 */
final class WorkspaceMarginConfig {
    static final int LIMIT_DP = 160;
    static final WorkspaceMarginConfig DEFAULT =
            new WorkspaceMarginConfig(0, 0, 0, 0);

    final int leftDp;
    final int topDp;
    final int rightDp;
    final int bottomDp;

    private WorkspaceMarginConfig(int left, int top, int right, int bottom) {
        leftDp = left;
        topDp = top;
        rightDp = right;
        bottomDp = bottom;
    }

    static WorkspaceMarginConfig parse(String text) {
        if (text == null) throw new IllegalArgumentException("missing margin settings");
        String[] values = text.trim().split("\\s+");
        if (values.length != 4) {
            throw new IllegalArgumentException("expected left top right bottom");
        }
        int[] dp = new int[4];
        for (int i = 0; i < dp.length; i++) {
            dp[i] = Integer.parseInt(values[i]);
            if (dp[i] < -LIMIT_DP || dp[i] > LIMIT_DP) {
                throw new IllegalArgumentException("margin outside -160..160 dp");
            }
        }
        return new WorkspaceMarginConfig(dp[0], dp[1], dp[2], dp[3]);
    }

    String serialize() {
        return leftDp + " " + topDp + " " + rightDp + " " + bottomDp;
    }

    boolean isDefault() {
        return leftDp == 0 && topDp == 0 && rightDp == 0 && bottomDp == 0;
    }

    /**
     * Apply signed deltas to native Workspace padding. Keep at least 28% of
     * the viewport available on either axis even for extreme user values.
     * Does not mutate the native padding array.
     */
    int[] adjust(int[] nativePx, float density, int widthPx, int heightPx) {
        if (nativePx == null || nativePx.length != 4) {
            throw new IllegalArgumentException("expected four native paddings");
        }
        int[] result = nativePx.clone();
        if (isDefault()) return result;
        if (!(density > 0)) density = 1f;
        final int[] dp = {leftDp, topDp, rightDp, bottomDp};
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
