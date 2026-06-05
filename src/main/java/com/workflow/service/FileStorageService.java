package com.workflow.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class FileStorageService {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final String bucketName;
    private final String keyPrefix;
    private final boolean s3Available;
    private final Path localUploadDir;

    public FileStorageService(@Value("${app.aws.access-key-id:}") String accessKeyId,
                              @Value("${app.aws.secret-access-key:}") String secretAccessKey,
                              @Value("${app.aws.region:us-east-2}") String region,
                              @Value("${app.aws.bucket-name:}") String bucketName,
                              @Value("${app.aws.key-prefix:workflow-files}") String keyPrefix,
                              @Value("${app.upload.dir:uploads}") String uploadDir) {
        this.bucketName = bucketName;
        this.keyPrefix = (keyPrefix == null || keyPrefix.isBlank()) ? "workflow-files" : keyPrefix;

        boolean s3Ok = false;
        S3Client client = null;
        S3Presigner presigner = null;

        if (accessKeyId != null && !accessKeyId.isBlank()
                && secretAccessKey != null && !secretAccessKey.isBlank()
                && bucketName != null && !bucketName.isBlank()) {
            try {
                AwsBasicCredentials credentials = AwsBasicCredentials.create(accessKeyId, secretAccessKey);
                StaticCredentialsProvider provider = StaticCredentialsProvider.create(credentials);
                Region awsRegion = Region.of(region);
                client = S3Client.builder().region(awsRegion).credentialsProvider(provider).build();
                presigner = S3Presigner.builder().region(awsRegion).credentialsProvider(provider).build();
                s3Ok = true;
            } catch (Exception e) {
                s3Ok = false;
            }
        }

        this.s3Available = s3Ok;
        this.s3Client = client;
        this.s3Presigner = presigner;

        Path dir = Paths.get(uploadDir).toAbsolutePath();
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {}
        this.localUploadDir = dir;
    }

    public boolean isS3Available() {
        return s3Available;
    }

    /**
     * Sube un archivo. Si se provee workflowId, el archivo queda en
     * {keyPrefix}/{workflowId}/{uuid}.ext — una carpeta por workflow.
     * Sin workflowId usa el nivel raíz (compatibilidad con archivos viejos).
     */
    public Map<String, Object> store(MultipartFile file, String workflowId) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Archivo vacio");
        }

        String originalName = StringUtils.cleanPath(
                file.getOriginalFilename() == null ? "archivo" : file.getOriginalFilename());
        String extension = "";
        int lastDot = originalName.lastIndexOf('.');
        if (lastDot >= 0) extension = originalName.substring(lastDot);

        String storedName = UUID.randomUUID() + extension;
        String folder = sanitizeFolder(workflowId);

        if (s3Available) {
            storeS3(file, storedName, folder);
        } else {
            storeLocal(file, storedName, folder);
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("fileName", originalName);
        meta.put("storedName", storedName);
        meta.put("workflowId", folder != null ? workflowId : null);
        meta.put("contentType", file.getContentType());
        meta.put("size", file.getSize());
        meta.put("downloadPath", "/files/" + storedName + "/download");
        return meta;
    }

    /** Retro-compatible: sin workflowId (archivos viejos o subida sin contexto). */
    public Map<String, Object> store(MultipartFile file) {
        return store(file, null);
    }

    /**
     * Genera URL pre-firmada. Busca primero en la carpeta del workflow;
     * si no existe (archivo viejo), cae al nivel raíz.
     */
    public String createPresignedDownloadUrl(String storedName, String workflowId, String filename) {
        if (!s3Available) return null;
        String folder = sanitizeFolder(workflowId);
        String objectKey = folder != null
                ? keyPrefix + "/" + folder + "/" + storedName
                : keyPrefix + "/" + storedName;

        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(objectKey)
                .responseContentDisposition(contentDisposition(filename))
                .build();
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(15))
                .getObjectRequest(getObjectRequest)
                .build();
        return s3Presigner.presignGetObject(presignRequest).url().toString();
    }

    /** Retro-compatible: sin workflowId. */
    public String createPresignedDownloadUrl(String storedName, String filename) {
        return createPresignedDownloadUrl(storedName, null, filename);
    }

    public byte[] readLocalFile(String storedName, String workflowId) {
        String folder = sanitizeFolder(workflowId);
        Path file = folder != null
                ? localUploadDir.resolve(folder).resolve(storedName)
                : localUploadDir.resolve(storedName);

        // Fallback a raíz si no se encuentra en la carpeta del workflow
        if (!Files.exists(file)) {
            file = localUploadDir.resolve(storedName);
        }
        if (!Files.exists(file)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Archivo no encontrado");
        }
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "No se pudo leer el archivo");
        }
    }

    public byte[] readLocalFile(String storedName) {
        return readLocalFile(storedName, null);
    }

    public String detectContentType(String storedName) {
        try {
            Path file = localUploadDir.resolve(storedName);
            String ct = Files.probeContentType(file);
            return ct != null ? ct : "application/octet-stream";
        } catch (IOException e) {
            return "application/octet-stream";
        }
    }

    /**
     * Lee los bytes de un archivo desde S3 o local (para conversión inline).
     */
    /**
     * Descarga los bytes de un archivo via URL pre-firmada (funciona sin s3:GetObject directo).
     * Fallback a almacenamiento local si S3 no está disponible.
     */
    public byte[] readFileBytes(String storedName, String workflowId) {
        if (s3Available) {
            // Intentar con carpeta de workflow primero, luego raíz como fallback
            String signedUrl = createPresignedDownloadUrl(storedName, workflowId, storedName);
            try {
                return downloadViaUrl(signedUrl);
            } catch (Exception e) {
                // Fallback sin workflowId (archivos viejos)
                if (workflowId != null) {
                    try {
                        String rootUrl = createPresignedDownloadUrl(storedName, null, storedName);
                        return downloadViaUrl(rootUrl);
                    } catch (Exception ex) {
                        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Archivo no encontrado en S3");
                    }
                }
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Archivo no encontrado en S3");
            }
        }
        return readLocalFile(storedName, workflowId);
    }

    private byte[] downloadViaUrl(String urlStr) throws IOException {
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(30_000);
        int status = conn.getResponseCode();
        if (status == 302 || status == 301) {
            String location = conn.getHeaderField("Location");
            conn.disconnect();
            conn = (java.net.HttpURLConnection) new java.net.URL(location).openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(30_000);
        }
        if (conn.getResponseCode() >= 400) {
            conn.disconnect();
            throw new IOException("S3 responded with HTTP " + conn.getResponseCode());
        }
        try (InputStream in = conn.getInputStream()) {
            return in.readAllBytes();
        } finally {
            conn.disconnect();
        }
    }

    private void storeS3(MultipartFile file, String storedName, String folder) {
        try {
            String objectKey = folder != null
                    ? keyPrefix + "/" + folder + "/" + storedName
                    : keyPrefix + "/" + storedName;
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucketName).key(objectKey).contentType(file.getContentType()).build();
            s3Client.putObject(request, RequestBody.fromBytes(file.getBytes()));
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "No se pudo leer el archivo");
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "No se pudo subir el archivo a S3");
        }
    }

    private void storeLocal(MultipartFile file, String storedName, String folder) {
        try {
            Path dir = folder != null ? localUploadDir.resolve(folder) : localUploadDir;
            Files.createDirectories(dir);
            Path dest = dir.resolve(storedName);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "No se pudo guardar el archivo localmente");
        }
    }

    private String sanitizeFolder(String workflowId) {
        if (workflowId == null || workflowId.isBlank()) return null;
        // Solo caracteres alfanuméricos y guiones para evitar path traversal
        return workflowId.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private String contentDisposition(String filename) {
        String safeFileName = (filename == null || filename.isBlank()) ? "archivo" : filename.replace("\"", "");
        return "attachment; filename=\"" + safeFileName + "\"";
    }
}
