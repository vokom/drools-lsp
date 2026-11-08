package org.drools.completion;

import java.nio.file.*;
import java.util.zip.ZipFile;

/** Materializes source files from the server JDK as ordinary file URIs for LSP clients. */
final class JdkSourceArchive {
    private JdkSourceArchive() {}
    static Path locate(String fqcn) {
        if (!(fqcn.startsWith("java.") || fqcn.startsWith("javax.") || fqcn.startsWith("jdk."))) return null;
        String relative = fqcn.split("[$]", 2)[0].replace('.', '/') + ".java";
        Path home = Path.of(System.getProperty("java.home"));
        for (Path archive : new Path[]{home.resolve("lib/src.zip"), home.resolve("../lib/src.zip").normalize()}) {
            if (!Files.isRegularFile(archive)) continue;
            try (ZipFile zip = new ZipFile(archive.toFile())) {
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    if (!(entry.getName().equals(relative) || entry.getName().endsWith("/" + relative))) continue;
                    String digest = Integer.toHexString((archive.toRealPath().toString()+Files.getLastModifiedTime(archive)+Files.size(archive)).hashCode());
                    Path cache = Path.of(System.getProperty("java.io.tmpdir"), "drools-lsp-jdk-sources", digest);
                    Path target = cache.resolve(entry.getName()).normalize();
                    if (!target.startsWith(cache)) return null;
                    if (!Files.isRegularFile(target)) {
                        Files.createDirectories(target.getParent());
                        Path temporary = Files.createTempFile(target.getParent(), "source-", ".tmp");
                        try {
                            try (var input = zip.getInputStream(entry)) { Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING); }
                            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                        } finally { Files.deleteIfExists(temporary); }
                    }
                    return target;
                }
            } catch (Exception unavailable) { return null; }
        }
        return null;
    }
}
