package org.broad.igv.blast;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class BlastToolsTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private Path executable(Path directory, String filename) throws IOException {
        Files.createDirectories(directory);
        Path file = Files.writeString(directory.resolve(filename), "fixture");
        file.toFile().setExecutable(true, false);
        return file;
    }

    @Test public void selectsWindowsMacIntelAppleSiliconAndLinuxArchitectures() throws IOException {
        assertEquals("windows-x64", BlastTools.platform("Windows 11", "amd64"));
        assertEquals("windows-x64", BlastTools.platform("Windows 11", "aarch64"));
        assertEquals("macos-universal", BlastTools.platform("Mac OS X", "x86_64"));
        assertEquals("macos-universal", BlastTools.platform("Mac OS X", "aarch64"));
        assertEquals("linux-x64", BlastTools.platform("Linux", "amd64"));
        assertEquals("linux-arm64", BlastTools.platform("Linux", "aarch64"));
        assertThrows(IOException.class, () -> BlastTools.platform("Linux", "riscv64"));
    }

    @Test public void prefersPathOverBundledAndUsesBundleWhenPathMissing() throws IOException {
        Path root = temporary.newFolder("bundle").toPath();
        Path pathDirectory = temporary.newFolder("PATH with spaces").toPath();
        Path pathTool = executable(pathDirectory, "blastn.exe");
        Path bundled = executable(root.resolve("windows-x64/bin"), "blastn.exe");
        assertEquals(pathTool, BlastTools.resolve("blastn", "auto", Map.of("Path", pathDirectory.toString()), List.of(root), "Windows 11", "amd64"));
        assertEquals(bundled, BlastTools.resolve("blastn", "auto", Map.of(), List.of(root), "Windows 11", "amd64"));
        // The previous default 'blastn' retains automatic PATH/bundle resolution.
        assertEquals(bundled, BlastTools.resolve("blastn", "blastn", Map.of(), List.of(root), "Windows 11", "amd64"));
    }

    @Test public void resolvesMakeblastdbBesideExplicitBlastn() throws IOException {
        Path directory = temporary.newFolder("custom version").toPath();
        Path blastn = executable(directory, "blastn.exe");
        Path makeblastdb = executable(directory, "makeblastdb.exe");
        assertEquals(makeblastdb, BlastTools.resolve("makeblastdb", blastn.toString(), Map.of(), List.of(), "Windows 11", "amd64"));
        assertEquals(blastn, BlastTools.resolve("blastn", directory.toString(), Map.of(), List.of(), "Windows 11", "amd64"));
    }

    @Test public void selectsNativeBundlesForMacAndLinux() throws IOException {
        Path root = temporary.newFolder("native").toPath();
        for (String platform : List.of("macos-universal", "linux-x64", "linux-arm64")) {
            executable(root.resolve(platform + "/bin"), "blastn");
            executable(root.resolve(platform + "/bin"), "makeblastdb");
        }
        assertEquals(root.resolve("macos-universal/bin/blastn"), BlastTools.resolve("blastn", "auto", Map.of(), List.of(root), "Mac OS X", "arm64"));
        assertEquals(root.resolve("linux-arm64/bin/makeblastdb"), BlastTools.resolve("makeblastdb", "auto", Map.of(), List.of(root), "Linux", "aarch64"));
        assertEquals(root.resolve("linux-x64/bin/blastn"), BlastTools.resolve("blastn", "auto", Map.of(), List.of(root), "Linux", "x86_64"));
    }

    @Test public void explicitMissingExecutableDoesNotSilentlyFallBack() throws IOException {
        Path root = temporary.newFolder("fallback").toPath();
        executable(root.resolve("windows-x64/bin"), "blastn.exe");
        assertThrows(IOException.class, () -> BlastTools.resolve("blastn", root.resolve("missing.exe").toString(), Map.of(), List.of(root), "Windows 11", "amd64"));
    }
}
