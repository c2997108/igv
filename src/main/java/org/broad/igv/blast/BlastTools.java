package org.broad.igv.blast;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Finds native BLAST+ tools in an explicit directory, on PATH, or in the IGV distribution. */
public class BlastTools {
    public static String platform(String os, String architecture) throws IOException {
        os = os.toLowerCase(Locale.ROOT);
        architecture = architecture.toLowerCase(Locale.ROOT);
        boolean arm = architecture.equals("aarch64") || architecture.equals("arm64");
        boolean x64 = architecture.equals("amd64") || architecture.equals("x86_64") || architecture.equals("x64");
        if (os.startsWith("windows") && (x64 || arm)) return "windows-x64";
        if ((os.contains("mac") || os.contains("darwin")) && (x64 || arm)) return "macos-universal";
        if (os.contains("linux") && (x64 || arm)) return arm ? "linux-arm64" : "linux-x64";
        throw new IOException("No bundled BLAST+ tools for " + os + " / " + architecture + ". Set a BLAST+ executable path in Preferences.");
    }

    public static Path resolve(String tool, String executablePreference) throws IOException {
        String os = System.getProperty("os.name");
        String arch = System.getProperty("os.arch");
        List<Path> roots = bundleRoots();
        return resolve(tool, executablePreference, System.getenv(), roots, os, arch);
    }

    static Path resolve(String tool, String preference, Map<String, String> environment,
                        List<Path> bundleRoots, String os, String architecture) throws IOException {
        boolean windows = os.toLowerCase(Locale.ROOT).startsWith("windows");
        String name = tool + (windows ? ".exe" : "");
        boolean explicit = preference != null && !preference.isBlank() &&
                !List.of("auto", "blastn", "blastn.exe").contains(preference.strip().toLowerCase(Locale.ROOT));
        if (explicit) {
            Path configured = Path.of(preference.strip()).toAbsolutePath().normalize();
            Path candidate = Files.isDirectory(configured) ? configured.resolve(name) :
                    tool.equals("blastn") ? configured : configured.resolveSibling(name);
            if (usable(candidate, windows)) return candidate;
            // An explicit blastn path is an override, so a typo must not silently select another version.
            if (tool.equals("blastn")) throw new IOException("Cannot start BLAST+ blastn: executable not found at " + candidate);
        }
        String path = environment.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase("PATH"))
                .map(Map.Entry::getValue).findFirst().orElse("");
        for (String directory : path.split(windows ? ";" : ":")) {
            if (directory.isBlank()) continue;
            String value = directory.strip();
            if (value.startsWith("\"") && value.endsWith("\"")) value = value.substring(1, value.length() - 1);
            Path candidate = Path.of(value).resolve(name).toAbsolutePath().normalize();
            if (usable(candidate, windows)) return candidate;
        }
        String platform = platform(os, architecture);
        for (Path root : bundleRoots) {
            Path candidate = root.resolve(platform).resolve("bin").resolve(name).toAbsolutePath().normalize();
            if (usable(candidate, windows)) return candidate;
        }
        throw new IOException("BLAST+ " + tool + " was not found on PATH or in the bundled tools. Use an IGV distribution with BLAST+ or set its executable path in Preferences.");
    }

    private static boolean usable(Path candidate, boolean windows) {
        return Files.isRegularFile(candidate) && (windows || Files.isExecutable(candidate));
    }

    private static List<Path> bundleRoots() {
        List<Path> roots = new ArrayList<>();
        String override = System.getProperty("igv.blast.bundle.dir");
        if (override != null && !override.isBlank()) roots.add(Path.of(override));
        try {
            Path location = Path.of(BlastTools.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (Files.isRegularFile(location)) {
                Path parent = location.getParent();
                // Normal distribution and macOS .app both keep igv.jar in <installation>/lib.
                Path installation = parent.getFileName().toString().equals("lib") ? parent.getParent() : parent;
                roots.add(installation.resolve("blast"));
            }
        } catch (Exception ignored) {
            // Exploded development builds use the working-directory locations below.
        }
        Path working = Path.of(System.getProperty("user.dir"));
        roots.add(working.resolve("blast"));
        roots.add(working.resolve("build/bundled-blast"));
        roots.add(working.resolve("build/IGV-dist/blast"));
        return roots;
    }
}
