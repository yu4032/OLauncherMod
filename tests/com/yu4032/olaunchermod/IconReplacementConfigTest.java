package com.yu4032.olaunchermod;

import static org.junit.Assert.*;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.List;

public class IconReplacementConfigTest {
    @Rule
    public final TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void parseAndSerializePreserveValidEntries() {
        String raw = "# comment\n"
                + "com.tencent.mm=pkg:com.android.chrome\n"
                + "com.example.foo = icons/com.example.foo.png\n"
                + "bad pkg=pkg:com.valid\n"
                + "com.self=pkg:com.self\n"
                + "com.traversal=../../etc/passwd\n";
        IconReplacementConfig cfg = IconReplacementConfig.parse(raw);
        assertEquals(2, cfg.entries().size());
        assertEquals("pkg:com.android.chrome", cfg.targetFor("com.tencent.mm"));
        assertEquals("icons/com.example.foo.png", cfg.targetFor("com.example.foo"));
        assertNull(cfg.targetFor("com.self"));
        assertNull(cfg.targetFor("com.traversal"));
        assertEquals(
                "com.tencent.mm=pkg:com.android.chrome\n"
                        + "com.example.foo=icons/com.example.foo.png\n",
                cfg.serialize());
    }

    @Test
    public void resolveImageFilePrefersDeBeforeExternal() throws IOException {
        File deDir = temp.newFolder("de");
        File extDir = temp.newFolder("ext");
        File extIcons = new File(extDir, IconReplacementConfig.RELATIVE_ICONS_DIR);
        assertTrue(extIcons.mkdirs());
        File extPng = new File(extIcons, "com.example.app.png");
        assertTrue(extPng.createNewFile());

        // Legacy absolute external path resolves even before DE exists.
        File resolvedExt = IconReplacementConfig.resolveImageFile(
                "/storage/emulated/0/Android/data/com.android.launcher/files/iconfix/icons/com.example.app.png",
                List.of(deDir, extDir));
        assertEquals(extPng, resolvedExt);

        // Once mirrored into DE, DE takes precedence at boot.
        File deIcons = new File(deDir, IconReplacementConfig.RELATIVE_ICONS_DIR);
        assertTrue(deIcons.mkdirs());
        File dePng = new File(deIcons, "com.example.app.png");
        assertTrue(dePng.createNewFile());

        File resolvedDe = IconReplacementConfig.resolveImageFile(
                "/storage/emulated/0/Android/data/com.android.launcher/files/iconfix/icons/com.example.app.png",
                List.of(deDir, extDir));
        assertEquals(dePng, resolvedDe);
    }
}
