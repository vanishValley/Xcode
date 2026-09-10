package com.xu.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

final class EvalFiles {
    static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private EvalFiles() {}
    static Path resolve(Path root, String relative) {
        Path file = root.resolve(relative).normalize();
        if (Path.of(relative).isAbsolute() || !file.startsWith(root) || file.equals(root))
            throw new IllegalArgumentException("Task path outside workspace: " + relative);
        return file;
    }
    static void writeFiles(Path root, Map<String, String> files) throws IOException {
        Files.createDirectories(root);
        for (var entry : files.entrySet()) {
            Path file = resolve(root, entry.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue());
        }
    }
    static void json(Path path, Object value) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        JSON.writeValue(path.toFile(), value);
    }
    static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
