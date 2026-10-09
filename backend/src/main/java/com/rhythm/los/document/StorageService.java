package com.rhythm.los.document;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** File storage behind an interface-like service. Phase 2 swaps this for S3-compatible object storage. */
@Service
public class StorageService {
    private final Path root;

    public StorageService(@Value("${rhythm.storage.path}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    public String store(Long appId, byte[] bytes, String extension) {
        try {
            Path dir = root.resolve(String.valueOf(appId));
            Files.createDirectories(dir);
            Path file = dir.resolve(UUID.randomUUID() + (extension == null ? "" : extension));
            Files.write(file, bytes);
            return root.relativize(file).toString();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store document", e);
        }
    }

    public byte[] read(String relative) {
        try {
            Path p = root.resolve(relative).normalize();
            if (!p.startsWith(root)) throw new IllegalArgumentException("Bad path");
            return Files.readAllBytes(p);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read document", e);
        }
    }
}
