package com.yu4032.olaunchermod;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import io.github.libxposed.api.XposedModule;

/**
 * Replaces app icons inside ColorOS Launcher using LSPosed API 102 hooks.
 * Migrated from standalone Y700-Toolkit into OLauncherMod so all Launcher
 * customizations stay in one module.
 */
final class IconReplacementHooks {
    private static final String TAG = "OLauncherMod";
    private static final String LEGACY_ADB_CONF = "/data/adb/iconfix.conf";

    private volatile ModuleConfigStore configStore = ModuleConfigStore.defaults();
    private volatile IconReplacementConfig config = IconReplacementConfig.EMPTY;
    private volatile boolean configLoaded;
    private final Map<String, Drawable> cache = new HashMap<>();

    IconReplacementHooks(ModuleConfigStore store) {
        if (store != null) this.configStore = store;
    }

    static void install(XposedModule module, ClassLoader loader, ModuleConfigStore store) {
        IconReplacementHooks instance = new IconReplacementHooks(store);
        instance.refresh();
        instance.hookLauncherIcons(module, loader);
    }

    private void refresh() {
        if (configLoaded) return;
        synchronized (this) {
            if (configLoaded) return;
            boolean allTiersReady = true;
            String loadedRaw = null;
            File loadedFile = null;

            for (File dir : configStore.searchDirs()) {
                File confFile = new File(dir, IconReplacementConfig.RELATIVE_CONF);
                try (BufferedReader reader = new BufferedReader(new FileReader(confFile))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line).append('\n');
                    }
                    loadedRaw = sb.toString();
                    loadedFile = confFile;
                    break;
                } catch (FileNotFoundException missing) {
                    if (!ModuleConfigStore.isDirectoryAccessible(dir)) {
                        allTiersReady = false;
                    }
                } catch (IOException error) {
                    allTiersReady = false;
                    Log.w(TAG, "iconfix config read failed: " + error);
                }
            }

            if (loadedRaw == null) {
                File legacy = new File(LEGACY_ADB_CONF);
                if (legacy.isFile() && legacy.canRead()) {
                    try (BufferedReader reader = new BufferedReader(new FileReader(legacy))) {
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            sb.append(line).append('\n');
                        }
                        loadedRaw = sb.toString();
                        loadedFile = legacy;
                    } catch (IOException ignored) {
                    }
                }
            }

            if (loadedRaw != null) {
                config = IconReplacementConfig.parse(loadedRaw);
                if (configStore.primaryDir() != null && loadedFile != null
                        && !loadedFile.getAbsolutePath().startsWith(
                                configStore.primaryDir().getAbsolutePath())) {
                    mirrorToPrimary(loadedRaw);
                }
                configLoaded = true;
                Log.i(TAG, "loaded " + config.entries().size()
                        + " icon replacements from " + loadedFile);
            } else if (allTiersReady) {
                configLoaded = true;
            }
        }
    }

    private void mirrorToPrimary(String raw) {
        File primary = configStore.primaryDir();
        if (primary == null) return;
        try {
            File target = new File(primary, IconReplacementConfig.RELATIVE_CONF);
            if (target.isFile()) return;
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return;
            File temp = File.createTempFile(".iconfix.", ".tmp", parent);
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(raw.getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (!temp.renameTo(target)) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
        } catch (IOException | SecurityException ignored) {
        }
    }

    private Context resolveSystemContext(ClassLoader loader) {
        try {
            Class<?> app = Class.forName(
                    "com.android.common.LauncherApplication", false, loader);
            Object q = HookSymbols.field(app, "q", Context.class, true, false).get(null);
            if (q instanceof Context) return (Context) q;
        } catch (Throwable ignored) {
        }
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object ctx = at.getMethod("currentApplication").invoke(null);
            if (ctx instanceof Context) return (Context) ctx;
            Object thread = at.getMethod("currentActivityThread").invoke(null);
            if (thread != null) {
                Object sysCtx = at.getMethod("getSystemContext").invoke(thread);
                if (sysCtx instanceof Context) return (Context) sysCtx;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private Drawable freshIcon(String pkg, ClassLoader loader) {
        refresh();
        String target = config.targetFor(pkg);
        if (target == null) return null;

        Drawable base;
        synchronized (cache) {
            base = cache.get(target);
        }
        if (base == null) {
            base = loadTargetDrawable(pkg, target, loader);
            if (base != null) {
                synchronized (cache) {
                    cache.put(target, base);
                }
            }
        }
        if (base == null) return null;
        try {
            Drawable.ConstantState cs = base.getConstantState();
            return cs != null ? cs.newDrawable() : base;
        } catch (Throwable ignored) {
            return base;
        }
    }

    private Drawable loadTargetDrawable(String pkg, String target, ClassLoader loader) {
        if (target.startsWith("pkg:")) {
            String srcPkg = target.substring(4).trim();
            if (srcPkg.isEmpty() || srcPkg.equals(pkg)) return null;
            Context ctx = resolveSystemContext(loader);
            if (ctx == null) return null;
            try {
                return ctx.getPackageManager().getApplicationIcon(srcPkg);
            } catch (Throwable error) {
                Log.w(TAG, "borrow icon failed (" + srcPkg + "): " + error);
                return null;
            }
        }

        File file = IconReplacementConfig.resolveImageFile(target, configStore.searchDirs());
        if (file == null) return null;
        try {
            Bitmap bm = BitmapFactory.decodeFile(file.getAbsolutePath());
            if (bm == null) return null;
            BitmapDrawable bd = new BitmapDrawable(Resources.getSystem(), bm);
            return hasTransparentCorners(bm)
                    ? bd
                    : new AdaptiveIconDrawable(bd, new ColorDrawable(0x00000000));
        } catch (Throwable error) {
            Log.w(TAG, "decode icon failed (" + file + "): " + error);
            return null;
        }
    }

    private static boolean hasTransparentCorners(Bitmap bm) {
        try {
            int w = bm.getWidth();
            int h = bm.getHeight();
            int m = Math.max(1, Math.min(w, h) / 40);
            int[] px = new int[]{
                    bm.getPixel(m, m),
                    bm.getPixel(w - 1 - m, m),
                    bm.getPixel(m, h - 1 - m),
                    bm.getPixel(w - 1 - m, h - 1 - m)
            };
            for (int p : px) {
                if ((p >>> 24) > 32) return false;
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void hookLauncherIcons(XposedModule module, ClassLoader loader) {
        try {
            Method loadIcon = HookSymbols.method(PackageItemInfo.class, "loadIcon",
                    Drawable.class, false, false, PackageManager.class);
            module.hook(loadIcon).intercept(chain -> {
                Object result = chain.proceed();
                Object self = chain.getThisObject();
                if (!(self instanceof PackageItemInfo info)) return result;
                Drawable replacement = freshIcon(info.packageName, loader);
                return replacement != null ? replacement : result;
            });
        } catch (Throwable error) {
            module.log(Log.WARN, TAG, "icon replacement PackageItemInfo#loadIcon failed: " + error);
        }

        try {
            Method getIcon = HookSymbols.method(LauncherActivityInfo.class, "getIcon",
                    Drawable.class, false, false, int.class);
            module.hook(getIcon).intercept(chain -> {
                Object result = chain.proceed();
                Object self = chain.getThisObject();
                if (!(self instanceof LauncherActivityInfo info)) return result;
                ApplicationInfo ai = info.getApplicationInfo();
                if (ai == null) return result;
                Drawable replacement = freshIcon(ai.packageName, loader);
                return replacement != null ? replacement : result;
            });
            Method getBadged = HookSymbols.method(LauncherActivityInfo.class, "getBadgedIcon",
                    Drawable.class, false, false, int.class);
            module.hook(getBadged).intercept(chain -> {
                Object result = chain.proceed();
                Object self = chain.getThisObject();
                if (!(self instanceof LauncherActivityInfo info)) return result;
                ApplicationInfo ai = info.getApplicationInfo();
                if (ai == null) return result;
                Drawable replacement = freshIcon(ai.packageName, loader);
                return replacement != null ? replacement : result;
            });
        } catch (Throwable error) {
            module.log(Log.WARN, TAG, "icon replacement LauncherActivityInfo failed: " + error);
        }

        try {
            Class<?> apm = Class.forName("android.app.ApplicationPackageManager", false, loader);
            Method getDrawable = HookSymbols.method(apm, "getDrawable",
                    Drawable.class, false, false,
                    String.class, int.class, ApplicationInfo.class);
            module.hook(getDrawable).intercept(chain -> {
                Object arg0 = chain.getArgs().get(0);
                if (arg0 instanceof String pkg) {
                    Drawable replacement = freshIcon(pkg, loader);
                    if (replacement != null) return replacement;
                }
                return chain.proceed();
            });
        } catch (Throwable error) {
            module.log(Log.WARN, TAG, "icon replacement ApplicationPackageManager#getDrawable failed: " + error);
        }

        module.log(Log.INFO, TAG, "icon replacement hooks installed (loaded="
                + config.entries().size() + ")");
    }
}
