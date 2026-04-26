package com.workflow;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.mongodb.config.EnableMongoAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.io.BufferedReader;
import java.io.FileReader;
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
        Path envPath = resolveEnvPath();
        if (envPath == null) {
            return;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(envPath.toFile()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq < 1) continue;
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                if (System.getenv(key) == null) {
                    System.setProperty(key, value);
                }
            }
        } catch (Exception ignored) {}
    }

    private static Path resolveEnvPath() {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        List<Path> candidates = List.of(
                cwd.resolve(".env"),
                cwd.resolve("backend").resolve(".env"),
                cwd.getParent() != null ? cwd.getParent().resolve(".env") : cwd.resolve(".env"),
                cwd.getParent() != null ? cwd.getParent().resolve("backend").resolve(".env") : cwd.resolve("backend").resolve(".env")
        );

        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
