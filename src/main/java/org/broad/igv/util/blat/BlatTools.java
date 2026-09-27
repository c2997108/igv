package org.broad.igv.util.blat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Locates sequence-alignment BLAT on PATH or in the installation's native bundle. */
public class BlatTools {
    public static String platform(String os, String architecture) throws IOException {
        os = os.toLowerCase(Locale.ROOT);
        architecture = architecture.toLowerCase(Locale.ROOT);
        boolean arm = List.of("aarch64", "arm64").contains(architecture);
        boolean x64 = List.of("amd64", "x86_64", "x64").contains(architecture);
        if (os.startsWith("windows") && (arm || x64)) return "windows-x64";
        if ((os.contains("mac") || os.contains("darwin")) && (arm || x64)) return arm ? "macos-arm64" : "macos-x64";
        if (os.contains("linux") && (arm || x64)) return arm ? "linux-arm64" : "linux-x64";
        throw new IOException("No bundled BLAT for " + os + " / " + architecture + ". Specify a BLAT executable in Preferences.");
    }

    public static Path resolve(String preference) throws IOException {
        List<Path> roots = new ArrayList<>();
        String override = System.getProperty("igv.blat.bundle.dir");
        if (override != null && !override.isBlank()) roots.add(Path.of(override));
        try {
            Path jar = Path.of(BlatTools.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (Files.isRegularFile(jar)) {
                Path parent = jar.getParent();
                roots.add((parent.getFileName().toString().equals("lib") ? parent.getParent() : parent).resolve("blat"));
            }
        } catch (Exception ignored) { }
        Path working = Path.of(System.getProperty("user.dir"));
        roots.add(working.resolve("blat"));
        roots.add(working.resolve("build/bundled-blat"));
        roots.add(working.resolve("build/IGV-dist/blat"));
        return resolve(preference, System.getenv(), roots, System.getProperty("os.name"), System.getProperty("os.arch"));
    }

    static Path resolve(String preference, Map<String, String> environment, List<Path> roots,
                        String os, String architecture) throws IOException {
        boolean windows = os.toLowerCase(Locale.ROOT).startsWith("windows");
        String name = windows ? "blat.exe" : "blat";
        if (preference != null && !preference.isBlank() && !preference.equalsIgnoreCase("auto")) {
            Path path = Path.of(preference.strip()).toAbsolutePath().normalize();
            if (Files.isDirectory(path)) path = path.resolve(name);
            if (usable(path, windows)) return path;
            throw new IOException("BLAT executable was not found: " + path);
        }
        String path = environment.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase("PATH"))
                .map(Map.Entry::getValue).findFirst().orElse("");
        for (String entry : path.split(windows ? ";" : ":")) {
            if (entry.isBlank()) continue;
            String directory = entry.strip();
            if (directory.startsWith("\"") && directory.endsWith("\"")) directory = directory.substring(1, directory.length() - 1);
            Path candidate = Path.of(directory).resolve(name).toAbsolutePath().normalize();
            if (usable(candidate, windows)) return candidate;
        }
        String platform = platform(os, architecture);
        for (Path root : roots) {
            Path candidate = root.resolve(platform).resolve("bin").resolve(name).toAbsolutePath().normalize();
            if (usable(candidate, windows)) return candidate;
        }
        throw new IOException("BLAT was not found on PATH or in the bundled tools. Specify its executable in Preferences.");
    }

    private static boolean usable(Path path, boolean windows) {
        return Files.isRegularFile(path) && (windows || Files.isExecutable(path));
    }
}
