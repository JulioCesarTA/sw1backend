package com.workflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.mongodb.config.EnableMongoAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@SpringBootApplication
@EnableMongoAuditing
@EnableScheduling
public class WorkflowApplication {

    public static void main(String[] args) {
        loadDotEnv();
        SpringApplication.run(WorkflowApplication.class, args);
    }

    private static void loadDotEnv() {
        var envPath = resolveEnvPath();
        if (envPath == null) {
            return;
        }

        try {
            Files.lines(envPath)
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .map(line -> line.split("=", 2))
                    .filter(parts -> parts.length == 2 && !parts[0].trim().isEmpty())
                    .forEach(parts -> setIfMissing(parts[0].trim(), parts[1].trim()));
        } catch (IOException ignored) {
        }
    }

    private static Path resolveEnvPath() {
        var cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (var candidate : List.of(
                cwd.resolve(".env"),
                cwd.resolve("backend").resolve(".env"),
                cwd.getParent() != null ? cwd.getParent().resolve(".env") : cwd.resolve(".env"),
                cwd.getParent() != null ? cwd.getParent().resolve("backend").resolve(".env") : cwd.resolve("backend").resolve(".env")
        )) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static void setIfMissing(String key, String value) {
        if (System.getenv(key) == null) {
            System.setProperty(key, value);
        }
    }
}
