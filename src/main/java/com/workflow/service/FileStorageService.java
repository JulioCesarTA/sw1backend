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

    public FileStorageService(@Value("${app.aws.access-key-id:}") String accessKeyId,
                              @Value("${app.aws.secret-access-key:}") String secretAccessKey,
                              @Value("${app.aws.region:us-east-2}") String region,
                              @Value("${app.aws.bucket-name:}") String bucketName,
                              @Value("${app.aws.key-prefix:workflow-files}") String keyPrefix) {
        if (accessKeyId == null || accessKeyId.isBlank()
                || secretAccessKey == null || secretAccessKey.isBlank()
                || bucketName == null || bucketName.isBlank()) {
            throw new IllegalStateException("Faltan credenciales o bucket de AWS S3");
        }

        AwsBasicCredentials credentials = AwsBasicCredentials.create(accessKeyId, secretAccessKey);
        StaticCredentialsProvider provider = StaticCredentialsProvider.create(credentials);
        Region awsRegion = Region.of(region);

        this.s3Client = S3Client.builder()
                .region(awsRegion)
                .credentialsProvider(provider)
                .build();
        this.s3Presigner = S3Presigner.builder()
                .region(awsRegion)
                .credentialsProvider(provider)
                .build();
        this.bucketName = bucketName;
        this.keyPrefix = keyPrefix == null || keyPrefix.isBlank() ? "workflow-files" : keyPrefix;
    }

    public Map<String, Object> store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Archivo vacio");
        }

        String originalName = StringUtils.cleanPath(file.getOriginalFilename() == null ? "archivo" : file.getOriginalFilename());
        String extension = "";
        int lastDot = originalName.lastIndexOf('.');
        if (lastDot >= 0) {
            extension = originalName.substring(lastDot);
        }

        String storedName = UUID.randomUUID() + extension;
        String objectKey = keyPrefix + "/" + storedName;

        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(objectKey)
                    .contentType(file.getContentType())
                    .build();
            s3Client.putObject(request, RequestBody.fromBytes(file.getBytes()));
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "No se pudo leer el archivo");
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "No se pudo subir el archivo a S3");
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("fileName", originalName);
        meta.put("storedName", storedName);
        meta.put("objectKey", objectKey);
        meta.put("bucket", bucketName);
        meta.put("contentType", file.getContentType());
        meta.put("size", file.getSize());
        meta.put("downloadPath", "/files/" + storedName + "/download");
        return meta;
    }

    public String createPresignedDownloadUrl(String storedName, String filename) {
        String objectKey = keyPrefix + "/" + storedName;
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

    private String contentDisposition(String filename) {
        String safeFileName = (filename == null || filename.isBlank()) ? "archivo" : filename.replace("\"", "");
        return "attachment; filename=\"" + safeFileName + "\"";
    }
}
