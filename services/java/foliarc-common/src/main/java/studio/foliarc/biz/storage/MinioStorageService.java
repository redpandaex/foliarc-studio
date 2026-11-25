package studio.foliarc.biz.storage;

import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import studio.foliarc.biz.common.BizException;

@Service
public class MinioStorageService {

    private static final Logger log = LoggerFactory.getLogger(MinioStorageService.class);

    private final MinioClient client;
    private final String defaultBucket;
    private final AtomicBoolean bucketEnsured = new AtomicBoolean(false);

    public MinioStorageService(
            @Value("${foliarc.minio.endpoint:http://localhost:9000}") String endpoint,
            @Value("${foliarc.minio.access-key:minioadmin}") String accessKey,
            @Value("${foliarc.minio.secret-key:minioadmin}") String secretKey,
            @Value("${foliarc.minio.bucket-docs:foliarc-docs}") String defaultBucket
    ) {
        this.client = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
        this.defaultBucket = defaultBucket;
    }

    public String getDefaultBucket() {
        return defaultBucket;
    }

    public void ensureBucketExists() {
        if (bucketEnsured.get()) {
            return;
        }
        try {
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(defaultBucket).build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder().bucket(defaultBucket).build());
                log.info("Created MinIO bucket: {}", defaultBucket);
            }
            bucketEnsured.set(true);
        } catch (Exception ex) {
            throw BizException.serviceUnavailable("MINIO_UNAVAILABLE", "对象存储 MinIO 不可用，请确认 --profile rag 已启动: " + ex.getMessage());
        }
    }

    public void upload(String objectKey, InputStream stream, long size, String contentType) {
        ensureBucketExists();
        try {
            client.putObject(
                    PutObjectArgs.builder()
                            .bucket(defaultBucket)
                            .object(objectKey)
                            .stream(stream, size, -1)
                            .contentType(contentType != null && !contentType.isBlank() ? contentType : "application/octet-stream")
                            .build()
            );
        } catch (Exception ex) {
            throw BizException.serviceUnavailable("MINIO_UPLOAD_FAILED", "文件上传至 MinIO 失败: " + ex.getMessage());
        }
    }

    public byte[] download(String bucket, String objectKey) {
        String targetBucket = (bucket == null || bucket.isBlank()) ? defaultBucket : bucket;
        try (InputStream in = client.getObject(
                GetObjectArgs.builder()
                        .bucket(targetBucket)
                        .object(objectKey)
                        .build()
        )) {
            return in.readAllBytes();
        } catch (Exception ex) {
            throw BizException.serviceUnavailable("MINIO_DOWNLOAD_FAILED", "从 MinIO 读取文件失败: " + ex.getMessage());
        }
    }

    public void deleteQuietly(String bucket, String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return;
        }
        String targetBucket = (bucket == null || bucket.isBlank()) ? defaultBucket : bucket;
        try {
            client.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(targetBucket)
                            .object(objectKey)
                            .build()
            );
        } catch (Exception ex) {
            log.warn("Best-effort MinIO delete failed for {}/{}: {}", targetBucket, objectKey, ex.getMessage());
        }
    }
}
