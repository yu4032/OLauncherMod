package local.y700.launcher;

import android.util.Log;

import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.reflect.Method;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * 桌面网格（精简版）。
 *
 * 只做两件事：
 *   1. InvariantDeviceProfile.setColumnsAndRows —— 让 DeviceProfile 的网格跟随配置；
 *   2. CellLayout.setGridSize —— 让 Workspace 的实际网格跟随配置。
 *
 * 配置：/storage/emulated/0/Android/data/com.android.launcher/files/y700_grid，一行“列 行”。
 * 不做迁移、不改边距、不动指示器，避免重启后布局被 Launcher 重建。
 */
public final class LauncherModule extends XposedModule {

    private static final String TAG = "Y700Launcher";
    private static final String FILE =
            "/storage/emulated/0/Android/data/com.android.launcher/files/y700_grid";

    private int cols = 0;
    private int rows = 0;
    private long readAt = 0L;

    private synchronized void refresh() {
        long now = System.currentTimeMillis();
        if (now - readAt < 2000L) return;
        readAt = now;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(FILE));
            String line = r.readLine();
            if (line != null) {
                String[] p = line.trim().split("\\s+");
                if (p.length >= 2) {
                    int c = Integer.parseInt(p[0]);
                    int rw = Integer.parseInt(p[1]);
                    if (c >= 3 && c <= 10 && rw >= 3 && rw <= 10) {
                        cols = c;
                        rows = rw;
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (r != null) try { r.close(); } catch (Throwable ignored) {}
        }
    }

    private void reflow(android.content.Context ctx) {
        java.io.File dbFile = ctx.getDatabasePath("launcher.db");
        if (!dbFile.exists()) return;
        android.content.SharedPreferences sp = ctx.getSharedPreferences("y700_reflow", 0);
        String tag = cols + "x" + rows;
        if (tag.equals(sp.getString("applied", ""))) return;
        SQLiteDatabase db = null;
        Cursor c = null;
        try {
            db = SQLiteDatabase.openDatabase(dbFile.getPath(), null, SQLiteDatabase.OPEN_READWRITE);
            c = db.rawQuery("select _id, screen, cellX, cellY, spanX, spanY from singledesktopitems "
                    + "where container=-100 order by screen, cellY, cellX, _id", null);
            java.util.List<int[]> items = new java.util.ArrayList<>();
            java.util.List<Integer> ids = new java.util.ArrayList<>();
            while (c.moveToNext()) {
                ids.add(c.getInt(0));
                items.add(new int[]{c.getInt(1), c.getInt(2), c.getInt(3),
                        Math.max(1, c.getInt(4)), Math.max(1, c.getInt(5))});
            }
            java.util.Map<String, Integer> occ = new java.util.HashMap<>();
            int moved = 0;
            for (int i = 0; i < items.size(); i++) {
                int[] it = items.get(i);
                int screen = it[0], sx = it[3], sy = it[4];
                int nx = 0, ny = 0;
                boolean placed = false;
                while (ny + sy <= rows) {
                    if (nx + sx > cols) { nx = 0; ny++; continue; }
                    boolean free = true;
                    for (int dx = 0; dx < sx && free; dx++)
                        for (int dy = 0; dy < sy; dy++)
                            if (occ.containsKey(screen + ":" + (nx + dx) + ":" + (ny + dy))) { free = false; break; }
                    if (free) { placed = true; break; }
                    nx++;
                }
                if (!placed) { nx = it[1]; ny = it[2]; }
                for (int dx = 0; dx < sx; dx++)
                    for (int dy = 0; dy < sy; dy++)
                        occ.put(screen + ":" + (nx + dx) + ":" + (ny + dy), i);
                if (nx != it[1] || ny != it[2]) {
                    db.execSQL("update singledesktopitems set cellX=?, cellY=?, vCellX=?, vCellY=? where _id=?",
                            new Object[]{nx, ny, nx, ny, ids.get(i)});
                    moved++;
                }
            }
            sp.edit().putString("applied", tag).apply();
            log(Log.INFO, TAG, "reflow " + items.size() + " items, " + moved + " moved");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "reflow failed: " + t);
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) {}
            if (db != null) try { db.close(); } catch (Throwable ignored) {}
        }
    }

    private Object field(Object o, String name) throws Exception {
        java.lang.reflect.Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (!"com.android.launcher".equals(param.getPackageName())) return;
        ClassLoader cl = param.getDefaultClassLoader();

        try {
            Class<?> idp = Class.forName("com.android.launcher3.InvariantDeviceProfile", false, cl);
            Method m = idp.getDeclaredMethod("setColumnsAndRows", int.class, int.class);
            hook(m).intercept(chain -> {
                refresh();
                if (cols > 0 && rows > 0) {
                    return chain.proceed(new Object[]{cols, rows});
                }
                return chain.proceed();
            });
            log(Log.INFO, TAG, "grid hook installed");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "grid hook failed: " + t);
        }

        try {
            Class<?> clCls = Class.forName("com.android.launcher3.OplusCellLayout", false, cl);
            Method sg = clCls.getDeclaredMethod("setGridSize", int.class, int.class);
            hook(sg).intercept(chain -> {
                refresh();
                Object o = chain.getThisObject();
                Object[] args = chain.getArgs().toArray();
                int ox = (Integer) args[0], oy = (Integer) args[1];
                Object r;
                if (cols > 0 && rows > 0) {
                    args[0] = cols;
                    args[1] = rows;
                    r = chain.proceed(args);
                } else {
                    r = chain.proceed();
                }
                try {
                    log(Log.INFO, TAG, "setGridSize " + ox + "x" + oy + " -> "
                            + args[0] + "x" + args[1]
                            + " mCountX=" + field(o, "mCountX")
                            + " mCountY=" + field(o, "mCountY")
                            + " cls=" + o.getClass().getName());
                } catch (Throwable ignored) {}
                return r;
            });
            log(Log.INFO, TAG, "cellLayout hook installed");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "cellLayout hook failed: " + t);
        }

        try {
            Class<?> launcher = Class.forName("com.android.launcher3.Launcher", false, cl);
            Method onCreate = launcher.getDeclaredMethod("onCreate", android.os.Bundle.class);
            hook(onCreate).intercept(chain -> {
                try {
                    reflow((Context) chain.getThisObject());
                } catch (Throwable t) {
                    log(Log.ERROR, TAG, "reflow dispatch failed: " + t);
                }
                return chain.proceed();
            });
            log(Log.INFO, TAG, "reflow hook installed");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "reflow hook failed: " + t);
        }
    }
}
