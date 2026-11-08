package org.drools.completion;

import java.nio.file.*;
import java.util.Set;
import java.util.zip.ZipFile;

/** Uses only source archives beside dependencies on the resolved classpath. */
final class DependencySourceArchive {
    private DependencySourceArchive() {}

    static Path locate(String fqcn, Set<Path> classpath) {
        if (fqcn == null || classpath == null) return null;
        String relative = fqcn.split("[$]", 2)[0].replace('.', '/') + ".java";
        String binary = fqcn.replace('.', '/') + ".class";
        for (Path jar : classpath) {
            String name = jar.getFileName().toString();
            if (!name.endsWith(".jar") || name.endsWith("-sources.jar") || !Files.isRegularFile(jar)) continue;
            Path sources = jar.resolveSibling(name.substring(0, name.length() - 4) + "-sources.jar");
            if (!Files.isRegularFile(sources)) continue;
            try (ZipFile classes = new ZipFile(jar.toFile()); ZipFile zip = new ZipFile(sources.toFile())) {
                if (classes.getEntry(binary) == null) continue;
                var entry = zip.getEntry(relative);
                if (entry == null || entry.isDirectory()) continue;
                String digest = Integer.toHexString((sources.toRealPath().toString()
                        + Files.getLastModifiedTime(sources) + Files.size(sources)).hashCode());
                Path cache = Path.of(System.getProperty("java.io.tmpdir"), "drools-lsp-dependency-sources", digest);
                Path target = cache.resolve(relative).normalize();
                if (!target.startsWith(cache)) return null;
                if (!Files.isRegularFile(target)) {
                    Files.createDirectories(target.getParent());
                    Path temporary = Files.createTempFile(target.getParent(), "source-", ".tmp");
                    try {
                        try (var input = zip.getInputStream(entry)) {
                            Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
                        }
                        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                    } finally { Files.deleteIfExists(temporary); }
                }
                return target;
            } catch (Exception unavailable) {
                // A broken archive must not prevent other classpath entries from resolving.
            }
        }
        return null;
    }
}
