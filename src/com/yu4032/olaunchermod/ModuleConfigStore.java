package com.yu4032.olaunchermod;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves module configuration across boot-safe internal storage and legacy
 * external storage.
 *
 * <p>On Android FBE / FUSE devices, {@code /storage/emulated/0} may not be
 * mounted yet when {@code com.android.launcher} starts at boot. Reading from
 * device-encrypted (DE) internal storage first ensures custom grid and layout
 * protections are active from the first boot frame.
 */
final class ModuleConfigStore {
    static final String GRID_FILE = "launcher_grid";
    static final String MARGINS_FILE = "launcher_workspace_margins";
    static final String DOCK_FILE = "launcher_dock_offset";
    static final String INDICATOR_FILE = IndicatorActivityConfig.FILE_NAME;

    static final String DEFAULT_DE_DIR = "/data/user_de/0/com.android.launcher/files";
    static final String DEFAULT_CE_DIR = "/data/user/0/com.android.launcher/files";
    static final String LEGACY_EXTERNAL_DIR =
            "/storage/emulated/0/Android/data/com.android.launcher/files";

    record ReadResult(String line, File sourceFile, boolean storageReady, IOException error) {}

    private final File primaryDir;
    private final List<File> searchDirs;

    ModuleConfigStore(File primaryDir, List<File> searchDirs) {
        this.primaryDir = primaryDir;
        Set<File> ordered = new LinkedHashSet<>();
        if (primaryDir != null) ordered.add(primaryDir);
        if (searchDirs != null) {
            for (File dir : searchDirs) {
                if (dir != null) ordered.add(dir);
            }
        }
        this.searchDirs = Collections.unmodifiableList(new ArrayList<>(ordered));
    }

    static ModuleConfigStore defaults() {
        return forApplicationInfo(null, null);
    }

    static ModuleConfigStore forApplicationInfo(
            String deviceProtectedDataDir, String dataDir) {
        File deDir = (deviceProtectedDataDir != null && !deviceProtectedDataDir.isEmpty())
                ? new File(deviceProtectedDataDir, "files")
                : new File(DEFAULT_DE_DIR);
        File ceDir = (dataDir != null && !dataDir.isEmpty())
                ? new File(dataDir, "files")
                : new File(DEFAULT_CE_DIR);
        File extDir = new File(LEGACY_EXTERNAL_DIR);
        List<File> dirs = new ArrayList<>(3);
        dirs.add(deDir);
        dirs.add(ceDir);
        dirs.add(extDir);
        return new ModuleConfigStore(deDir, dirs);
    }

    List<File> searchDirs() {
        return searchDirs;
    }

    File primaryDir() {
        return primaryDir;
    }

    /**
     * Reads the first line of {@code fileName} from the highest-priority
     * available storage tier. If found only in a secondary/legacy tier, mirrors
     * it to {@code primaryDir} on a best-effort basis so subsequent reboots read
     * it before external storage mounts.
     */
    ReadResult readFirstLine(String fileName) {
        boolean allTiersAccessible = true;
        IOException lastError = null;
        for (File dir : searchDirs) {
            File candidate = new File(dir, fileName);
            try (BufferedReader reader = new BufferedReader(new FileReader(candidate))) {
                String line = reader.readLine();
                if (line != null && primaryDir != null && !dir.equals(primaryDir)) {
                    mirrorToPrimary(fileName, line);
                }
                return new ReadResult(line, candidate, true, null);
            } catch (FileNotFoundException missing) {
                if (!isDirectoryAccessible(dir)) {
                    allTiersAccessible = false;
                }
            } catch (IOException error) {
                allTiersAccessible = false;
                lastError = error;
            }
        }
        return new ReadResult(null, null, allTiersAccessible, lastError);
    }

    /**
     * A missing file only means "not configured" when the directory (or its
     * immediate app root) is actually mounted and readable. During early boot
     * or Direct Boot, unmounted FUSE / CE storage fails these checks so callers
     * know to retry later instead of latching stock 6x4 forever.
     */
    static boolean isDirectoryAccessible(File dir) {
        if (dir == null) return false;
        if (dir.isDirectory() && dir.canRead()) return true;
        File parent = dir.getParentFile();
        return parent != null && parent.isDirectory() && parent.canRead();
    }

    private void mirrorToPrimary(String fileName, String line) {
        if (primaryDir == null) return;
        File temp = null;
        try {
            if (!primaryDir.isDirectory() && !primaryDir.mkdirs()) return;
            File target = new File(primaryDir, fileName);
            if (target.isFile()) return;
            temp = File.createTempFile("." + fileName + ".", ".tmp", primaryDir);
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (!temp.renameTo(target)) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
        } catch (IOException | SecurityException ignored) {
            if (temp != null) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
        }
    }
}
