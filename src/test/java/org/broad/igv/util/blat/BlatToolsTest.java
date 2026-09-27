package org.broad.igv.util.blat;

import org.junit.Test;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public class BlatToolsTest {
    @Test public void selectsNativeUnixAndEmulatedWindowsTargets() throws IOException {
        assertEquals("windows-x64", BlatTools.platform("Windows 11", "aarch64"));
        assertEquals("windows-x64", BlatTools.platform("Windows 10", "amd64"));
        assertEquals("linux-x64", BlatTools.platform("Linux", "x86_64"));
        assertEquals("linux-arm64", BlatTools.platform("Linux", "arm64"));
        assertEquals("macos-x64", BlatTools.platform("Mac OS X", "x86_64"));
        assertEquals("macos-arm64", BlatTools.platform("Darwin", "aarch64"));
        assertThrows(IOException.class, () -> BlatTools.platform("Windows", "x86"));
    }

    @Test public void prefersExplicitThenPathThenBundleAndRejectsInvalidOverride() throws IOException {
        Path root = Files.createTempDirectory("blat-tools");
        Path path = Files.createDirectories(root.resolve("path with spaces"));
        Path bundle = Files.createDirectories(root.resolve("windows-x64/bin"));
        Path bundled = Files.createFile(bundle.resolve("blat.exe"));
        Path installed = Files.createFile(path.resolve("blat.exe"));
        Path explicit = Files.createFile(root.resolve("custom.exe"));
        try {
            Map<String, String> environment = Map.of("Path", "\"" + path + "\"");
            assertEquals(explicit, BlatTools.resolve(explicit.toString(), environment, List.of(root), "Windows", "amd64"));
            assertEquals(installed, BlatTools.resolve(path.toString(), environment, List.of(root), "Windows", "amd64"));
            assertEquals(installed, BlatTools.resolve("auto", environment, List.of(root), "Windows", "aarch64"));
            assertEquals(bundled, BlatTools.resolve("auto", Map.of(), List.of(root), "Windows", "amd64"));
            assertThrows(IOException.class, () -> BlatTools.resolve(root.resolve("missing.exe").toString(), environment, List.of(root), "Windows", "amd64"));
        } finally {
            for (Path file : List.of(explicit, installed, bundled, path, bundle, bundle.getParent(), root)) Files.delete(file);
        }
    }
}
