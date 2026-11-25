package com.yu4032.olaunchermod;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

public class ModuleConfigStoreTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private static void write(File file, String content) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            assertTrue(parent.mkdirs());
        }
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void prefersDeviceEncryptedPrimaryOverSecondaryAndExternal() throws IOException {
        File de = temp.newFolder("de");
        File ce = temp.newFolder("ce");
        File ext = temp.newFolder("ext");
        write(new File(de, ModuleConfigStore.GRID_FILE), "8 4 1\n");
        write(new File(ce, ModuleConfigStore.GRID_FILE), "7 4 1\n");
        write(new File(ext, ModuleConfigStore.GRID_FILE), "6 4 0\n");

        ModuleConfigStore store = new ModuleConfigStore(de, Arrays.asList(de, ce, ext));
        ModuleConfigStore.ReadResult result = store.readFirstLine(ModuleConfigStore.GRID_FILE);

        assertEquals("8 4 1", result.line());
        assertEquals(new File(de, ModuleConfigStore.GRID_FILE), result.sourceFile());
        assertTrue(result.storageReady());
        assertNull(result.error());
    }

    @Test
    public void fallsBackToLegacyExternalAndMirrorsToPrimaryForNextBoot() throws IOException {
        File de = temp.newFolder("de");
        File ce = temp.newFolder("ce");
        File ext = temp.newFolder("ext");
        write(new File(ext, ModuleConfigStore.GRID_FILE), "8 4 1\n");

        ModuleConfigStore store = new ModuleConfigStore(de, Arrays.asList(de, ce, ext));
        ModuleConfigStore.ReadResult first = store.readFirstLine(ModuleConfigStore.GRID_FILE);

        assertEquals("8 4 1", first.line());
        assertEquals(new File(ext, ModuleConfigStore.GRID_FILE), first.sourceFile());
        assertTrue(first.storageReady());

        File mirrored = new File(de, ModuleConfigStore.GRID_FILE);
        assertTrue("legacy config should be mirrored into DE storage", mirrored.isFile());
        assertEquals("8 4 1",
                new String(Files.readAllBytes(mirrored.toPath()), StandardCharsets.UTF_8).trim());

        // Simulate reboot where external storage is not mounted yet.
        File unmountedExt = new File(temp.getRoot(), "unmounted/Android/data/com.android.launcher/files");
        ModuleConfigStore bootStore = new ModuleConfigStore(de, Arrays.asList(de, ce, unmountedExt));
        ModuleConfigStore.ReadResult onBoot = bootStore.readFirstLine(ModuleConfigStore.GRID_FILE);

        assertEquals("8 4 1", onBoot.line());
        assertEquals(mirrored, onBoot.sourceFile());
        assertTrue(onBoot.storageReady());
    }

    @Test
    public void reportsStorageNotReadyWhenExternalUnmountedAndPrimaryMissing() throws IOException {
        File de = temp.newFolder("de");
        File ce = temp.newFolder("ce");
        File unmountedExt = new File(temp.getRoot(), "unmounted/Android/data/com.android.launcher/files");

        ModuleConfigStore store = new ModuleConfigStore(de, Arrays.asList(de, ce, unmountedExt));
        ModuleConfigStore.ReadResult beforeMount = store.readFirstLine(ModuleConfigStore.GRID_FILE);

        assertNull(beforeMount.line());
        assertFalse("must not latch stock grid while external storage is unmounted",
                beforeMount.storageReady());

        // Once external storage mounts with a legacy config, a retry loads and mirrors it.
        write(new File(unmountedExt, ModuleConfigStore.GRID_FILE), "8 4 1\n");
        ModuleConfigStore.ReadResult afterMount = store.readFirstLine(ModuleConfigStore.GRID_FILE);

        assertEquals("8 4 1", afterMount.line());
        assertTrue(afterMount.storageReady());
        assertTrue(new File(de, ModuleConfigStore.GRID_FILE).isFile());
    }

    @Test
    public void reportsStorageReadyWhenAllTiersAccessibleAndUnconfigured() throws IOException {
        File de = temp.newFolder("de");
        File ce = temp.newFolder("ce");
        File ext = temp.newFolder("ext");

        ModuleConfigStore store = new ModuleConfigStore(de, Arrays.asList(de, ce, ext));
        ModuleConfigStore.ReadResult result = store.readFirstLine(ModuleConfigStore.GRID_FILE);

        assertNull(result.line());
        assertTrue(result.storageReady());
        assertNull(result.error());
    }

    @Test
    public void buildsExpectedTierOrderFromApplicationInfo() {
        ModuleConfigStore custom = ModuleConfigStore.forApplicationInfo(
                "/data/user_de/10/com.android.launcher",
                "/data/user/10/com.android.launcher");
        List<File> dirs = custom.searchDirs();
        assertEquals(3, dirs.size());
        assertEquals(new File("/data/user_de/10/com.android.launcher/files"), dirs.get(0));
        assertEquals(new File("/data/user/10/com.android.launcher/files"), dirs.get(1));
        assertEquals(new File(ModuleConfigStore.LEGACY_EXTERNAL_DIR), dirs.get(2));

        ModuleConfigStore defaults = ModuleConfigStore.defaults();
        assertEquals(new File(ModuleConfigStore.DEFAULT_DE_DIR), defaults.searchDirs().get(0));
        assertEquals(new File(ModuleConfigStore.DEFAULT_CE_DIR), defaults.searchDirs().get(1));
    }
}
