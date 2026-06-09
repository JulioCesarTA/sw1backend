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
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
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
    private final String legacyKeyPrefix;   // solo para leer archivos viejos
    private final boolean s3Available;
    private final Path localUploadDir;

    public FileStorageService(@Value("${app.aws.access-key-id:}") String accessKeyId,
                              @Value("${app.aws.secret-access-key:}") String secretAccessKey,
                              @Value("${app.aws.region:us-east-2}") String region,
                              @Value("${app.aws.bucket-name:}") String bucketName,
                              @Value("${app.aws.key-prefix:workflow-files}") String keyPrefix,
                              @Value("${app.upload.dir:uploads}") String uploadDir) {
        this.bucketName       = bucketName;
        this.legacyKeyPrefix  = (keyPrefix == null || keyPrefix.isBlank()) ? "tramites" : keyPrefix;

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
                client   = S3Client.builder().region(awsRegion).credentialsProvider(provider).build();
                presigner = S3Presigner.builder().region(awsRegion).credentialsProvider(provider).build();
                s3Ok = true;
            } catch (Exception e) {
                s3Ok = false;
            }
        }

        this.s3Available = s3Ok;
        this.s3Client    = client;
        this.s3Presigner = presigner;

        Path dir = Paths.get(uploadDir).toAbsolutePath();
        try { Files.createDirectories(dir); } catch (IOException ignored) {}
        this.localUploadDir = dir;
    }

    // ------------------------------------------------------------------ //
    // Estructura nueva: {workflowName}/{tramiteFolder}/{uuid}.ext
    // ------------------------------------------------------------------ //

    /**
     * Sube un archivo al bucket con la estructura:
     *   {workflowName}/{tramiteFolder}/{uuid}.ext
     *
     * workflowName  : nombre del workflow (se sanitiza para S3)
     * tramiteFolder : UUID generado por el frontend antes del primer upload
     *                 (sirve como carpeta permanente del trámite)
     */
    public Map<String, Object> store(MultipartFile file, String workflowName, String tramiteFolder) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Archivo vacío");
        }

        String originalName = StringUtils.cleanPath(
                file.getOriginalFilename() == null ? "archivo" : file.getOriginalFilename());
        String extension = "";
        int lastDot = originalName.lastIndexOf('.');
        if (lastDot >= 0) extension = originalName.substring(lastDot);

        String storedName     = UUID.randomUUID() + extension;
        String wfFolder       = sanitizeName(workflowName);
        String tramiteSegment = sanitizeName(tramiteFolder);

        if (s3Available) {
            storeS3New(file, storedName, wfFolder, tramiteSegment);
        } else {
            storeLocal(file, storedName, wfFolder + "/" + tramiteSegment);
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("fileName",      originalName);
        meta.put("storedName",    storedName);
        meta.put("workflowName",  wfFolder);
        meta.put("tramiteFolder", tramiteSegment);
        meta.put("contentType",   file.getContentType());
        meta.put("size",          file.getSize());

        String downloadPath = null;
        if (s3Available) {
            try {
                downloadPath = presignNew(storedName, wfFolder, tramiteSegment, originalName);
            } catch (Exception ignored) {}
        }
        meta.put("downloadPath", downloadPath);
        return meta;
    }

    /** Backward compat — usado por código que aún pasa workflowId */
    public Map<String, Object> store(MultipartFile file, String workflowId) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Archivo vacío");
        }

        String originalName = StringUtils.cleanPath(
                file.getOriginalFilename() == null ? "archivo" : file.getOriginalFilename());
        String extension = "";
        int lastDot = originalName.lastIndexOf('.');
        if (lastDot >= 0) extension = originalName.substring(lastDot);

        String storedName = UUID.randomUUID() + extension;
        String folder     = sanitizeName(workflowId);

        if (s3Available) {
            storeS3Legacy(file, storedName, folder);
        } else {
            storeLocal(file, storedName, folder);
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("fileName",    originalName);
        meta.put("storedName",  storedName);
        meta.put("workflowId",  folder != null ? workflowId : null);
        meta.put("contentType", file.getContentType());
        meta.put("size",        file.getSize());

        String downloadPath = null;
        if (s3Available) {
            try { downloadPath = presignLegacy(storedName, folder, originalName); }
            catch (Exception ignored) {}
        }
        meta.put("downloadPath", downloadPath);
        return meta;
    }

    // ------------------------------------------------------------------ //
    // Renombrar carpeta de trámite: {wf}/{oldFolder}/ → {wf}/{newFolder}/
    // ------------------------------------------------------------------ //
    public void moveTramiteFolder(String workflowName, String oldFolder, String newFolder) {
        if (!s3Available || workflowName == null || oldFolder == null || newFolder == null) return;
        String wf        = sanitizeName(workflowName);
        String oldPrefix = wf + "/" + sanitizeName(oldFolder) + "/";
        String newPrefix = wf + "/" + sanitizeName(newFolder) + "/";
        try {
            var listResp = s3Client.listObjectsV2(
                    ListObjectsV2Request.builder().bucket(bucketName).prefix(oldPrefix).build());
            for (var obj : listResp.contents()) {
                String oldKey = obj.key();
                String newKey = newPrefix + oldKey.substring(oldPrefix.length());
                s3Client.copyObject(CopyObjectRequest.builder()
                        .sourceBucket(bucketName).sourceKey(oldKey)
                        .destinationBucket(bucketName).destinationKey(newKey)
                        .build());
                s3Client.deleteObject(DeleteObjectRequest.builder()
                        .bucket(bucketName).key(oldKey).build());
            }
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------------ //
    // Archivos editados colaborativamente: {wf}/{tramiteFolder}/uuid.edited.ext
    // ------------------------------------------------------------------ //

    /**
     * Sube la versión editada de un archivo (no sobreescribe el original).
     * Key: {workflowName}/{tramiteFolder}/{uuid}.edited.{ext}
     */
    public void storeEditedBytes(byte[] data, String storedName, String workflowName, String tramiteFolder) {
        String edited = toEditedName(storedName);
        String key    = sanitizeName(workflowName) + "/" + sanitizeName(tramiteFolder) + "/" + edited;
        if (s3Available) {
            s3Client.putObject(
                    PutObjectRequest.builder().bucket(bucketName).key(key)
                            .contentType(contentTypeFor(storedName)).build(),
                    RequestBody.fromBytes(data));
        } else {
            try {
                Path dir = localUploadDir.resolve(sanitizeName(workflowName)).resolve(sanitizeName(tramiteFolder));
                java.nio.file.Files.createDirectories(dir);
                java.nio.file.Files.write(dir.resolve(edited), data, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException ignored) {}
        }
    }

    /**
     * Lee la mejor versión disponible: primero la editada, luego el original.
     */
    public byte[] readBestVersionBytes(String storedName, String workflowName, String tramiteFolder) {
        if (s3Available) {
            String editedKey = sanitizeName(workflowName) + "/" + sanitizeName(tramiteFolder) + "/" + toEditedName(storedName);
            try { return s3GetBytes(editedKey); } catch (Exception ignored) {}
        } else {
            Path editedFile = localUploadDir
                    .resolve(sanitizeName(workflowName))
                    .resolve(sanitizeName(tramiteFolder))
                    .resolve(toEditedName(storedName));
            if (java.nio.file.Files.exists(editedFile)) {
                try { return java.nio.file.Files.readAllBytes(editedFile); } catch (IOException ignored) {}
            }
        }
        return readFileBytes(storedName, workflowName, tramiteFolder);
    }

    private String toEditedName(String storedName) {
        int dot = storedName.lastIndexOf('.');
        if (dot < 0) return storedName + ".edited";
        return storedName.substring(0, dot) + ".edited" + storedName.substring(dot);
    }

    private String contentTypeFor(String storedName) {
        String lower = storedName.toLowerCase();
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        return "application/octet-stream";
    }

    /** Genera (o regenera) una URL presignada para un archivo en la nueva estructura */
    public String presignUrl(String storedName, String workflowName, String tramiteFolder, String fileName) {
        if (!s3Available) return null;
        try {
            return presignNew(storedName, sanitizeName(workflowName), sanitizeName(tramiteFolder), fileName);
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ //
    // Crear carpeta de workflow en S3 (objeto vacío como placeholder)
    // ------------------------------------------------------------------ //
    public void createWorkflowFolder(String workflowName) {
        if (!s3Available || workflowName == null || workflowName.isBlank()) return;
        String key = sanitizeName(workflowName) + "/";
        try {
            PutObjectRequest req = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .contentType("application/x-directory")
                    .build();
            s3Client.putObject(req, RequestBody.fromBytes(new byte[0]));
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------------ //
    // Lectura de archivos
    // ------------------------------------------------------------------ //
    public byte[] readFileBytes(String storedName, String workflowName, String tramiteFolder) {
        if (s3Available) {
            // Estructura nueva: {workflowName}/{tramiteFolder}/{storedName}
            String keyNew = sanitizeName(workflowName) + "/" + sanitizeName(tramiteFolder) + "/" + storedName;
            try { return s3GetBytes(keyNew); } catch (Exception ignored) {}
            // Fallback legacy: {legacyKeyPrefix}/{workflowName}/{storedName}
            String keyLegacy = legacyKeyPrefix + "/" + sanitizeName(workflowName) + "/" + storedName;
            try { return s3GetBytes(keyLegacy); } catch (Exception ignored) {}
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Archivo no encontrado en S3");
        }
        return readLocalFile(storedName, workflowName);
    }

    /** Backward compat — used when only workflowId is known */
    public byte[] readFileBytes(String storedName, String workflowId) {
        if (s3Available) {
            // Intenta legacy con workflowId como carpeta
            if (workflowId != null && !workflowId.isBlank()) {
                String key = legacyKeyPrefix + "/" + sanitizeName(workflowId) + "/" + storedName;
                try { return s3GetBytes(key); } catch (Exception ignored) {}
            }
            // Fallback: raíz del prefix
            String keyRoot = legacyKeyPrefix + "/" + storedName;
            try { return s3GetBytes(keyRoot); } catch (Exception ignored) {}
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Archivo no encontrado en S3");
        }
        return readLocalFile(storedName, workflowId);
    }

    private byte[] s3GetBytes(String key) {
        return s3Client.getObjectAsBytes(
                software.amazon.awssdk.services.s3.model.GetObjectRequest.builder()
                        .bucket(bucketName).key(key).build()
        ).asByteArray();
    }

    // ------------------------------------------------------------------ //
    // Internos
    // ------------------------------------------------------------------ //
    private void storeS3New(MultipartFile file, String storedName, String wfFolder, String tramiteSegment) {
        try {
            String key = wfFolder + "/" + tramiteSegment + "/" + storedName;
            PutObjectRequest req = PutObjectRequest.builder()
                    .bucket(bucketName).key(key).contentType(file.getContentType()).build();
            s3Client.putObject(req, RequestBody.fromBytes(file.getBytes()));
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "No se pudo leer el archivo");
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "No se pudo subir el archivo a S3");
        }
    }

    private void storeS3Legacy(MultipartFile file, String storedName, String folder) {
        try {
            String key = folder != null
                    ? legacyKeyPrefix + "/" + folder + "/" + storedName
                    : legacyKeyPrefix + "/" + storedName;
            PutObjectRequest req = PutObjectRequest.builder()
                    .bucket(bucketName).key(key).contentType(file.getContentType()).build();
            s3Client.putObject(req, RequestBody.fromBytes(file.getBytes()));
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "No se pudo leer el archivo");
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "No se pudo subir el archivo a S3");
        }
    }

    private String presignNew(String storedName, String wfFolder, String tramiteSegment, String filename) {
        String key = wfFolder + "/" + tramiteSegment + "/" + storedName;
        return presign(key, filename);
    }

    private String presignLegacy(String storedName, String folder, String filename) {
        String key = folder != null
                ? legacyKeyPrefix + "/" + folder + "/" + storedName
                : legacyKeyPrefix + "/" + storedName;
        return presign(key, filename);
    }

    private String presign(String objectKey, String filename) {
        GetObjectRequest getReq = GetObjectRequest.builder()
                .bucket(bucketName).key(objectKey)
                .responseContentDisposition(contentDisposition(filename))
                .build();
        GetObjectPresignRequest presignReq = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofHours(1))
                .getObjectRequest(getReq)
                .build();
        return s3Presigner.presignGetObject(presignReq).url().toString();
    }

    private void storeLocal(MultipartFile file, String relativePath, String storedName) {
        try {
            Path dir = localUploadDir.resolve(relativePath);
            Files.createDirectories(dir);
            Path dest = dir.resolve(storedName);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "No se pudo guardar el archivo localmente");
        }
    }

    private byte[] readLocalFile(String storedName, String folder) {
        String san = sanitizeName(folder);
        Path file = san != null
                ? localUploadDir.resolve(san).resolve(storedName)
                : localUploadDir.resolve(storedName);
        if (!Files.exists(file)) file = localUploadDir.resolve(storedName);
        if (!Files.exists(file)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Archivo no encontrado");
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "No se pudo leer el archivo");
        }
    }

    private byte[] downloadViaUrl(String urlStr) throws IOException {
        java.net.HttpURLConnection conn =
                (java.net.HttpURLConnection) new java.net.URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(30_000);
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

    private String sanitizeName(String name) {
        if (name == null || name.isBlank()) return null;
        return name.trim().replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private String contentDisposition(String filename) {
        String safe = (filename == null || filename.isBlank()) ? "archivo" : filename.replace("\"", "");
        return "attachment; filename=\"" + safe + "\"";
    }
}
