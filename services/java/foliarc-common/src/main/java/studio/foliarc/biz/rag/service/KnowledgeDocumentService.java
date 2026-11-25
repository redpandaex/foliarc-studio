package studio.foliarc.biz.rag.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import studio.foliarc.biz.common.BizException;
import studio.foliarc.biz.common.TransactionCallbacks;
import studio.foliarc.biz.novel.entity.BookEntity;
import studio.foliarc.biz.novel.entity.ChapterEntity;
import studio.foliarc.biz.novel.entity.ChapterSectionEntity;
import studio.foliarc.biz.novel.mapper.ChapterMapper;
import studio.foliarc.biz.novel.mapper.ChapterSectionMapper;
import studio.foliarc.biz.novel.service.BookService;
import studio.foliarc.biz.novel.service.StructureService;
import studio.foliarc.biz.outbox.RabbitMqTopologyConfig;
import studio.foliarc.biz.outbox.service.OutboxService;
import studio.foliarc.biz.rag.dto.RagDtos;
import studio.foliarc.biz.rag.entity.BookKnowledgeBindingEntity;
import studio.foliarc.biz.rag.entity.DocumentChunkEntity;
import studio.foliarc.biz.rag.entity.KnowledgeDocumentEntity;
import studio.foliarc.biz.rag.entity.KnowledgeGrantEntity;
import studio.foliarc.biz.rag.mapper.BookKnowledgeBindingMapper;
import studio.foliarc.biz.rag.mapper.DocumentChunkMapper;
import studio.foliarc.biz.rag.mapper.KnowledgeDocumentMapper;
import studio.foliarc.biz.rag.mapper.KnowledgeGrantMapper;
import studio.foliarc.biz.storage.MinioStorageService;
import studio.foliarc.biz.vip.dto.VipDtos;
import studio.foliarc.biz.vip.service.VipEntitlementService;

@Service
public class KnowledgeDocumentService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeDocumentService.class);
    private static final Set<String> ALLOWED_EXTS = Set.of(".pdf", ".docx", ".md", ".txt");
    private static final Set<String> VALID_DOC_TYPES = Set.of("WORLDBOOK", "CHAPTER", "STYLE_REF", "OUTLINE");
    public static final String PARTITION_USER = "__USER__";
    public static final String PARTITION_GLOBAL = "__GLOBAL__";

    private static final long BYTES_PER_MB = 1024L * 1024;
    private static final long USER_MAX_FILE_BYTES = 10 * BYTES_PER_MB;
    private static final int USER_MAX_DOCS = 50;
    private static final long USER_MAX_TOTAL_BYTES = 100 * BYTES_PER_MB;

    private final BookService bookService;
    private final ChapterMapper chapterMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final DocumentChunkMapper chunkMapper;
    private final KnowledgeGrantMapper grantMapper;
    private final BookKnowledgeBindingMapper bindingMapper;
    private final MinioStorageService minioStorage;
    private final OutboxService outboxService;
    private final RestClient agentRestClient;
    private VipEntitlementService vipEntitlementService;
    private ChapterSectionMapper chapterSectionMapper;

    public KnowledgeDocumentService(
            BookService bookService,
            ChapterMapper chapterMapper,
            KnowledgeDocumentMapper documentMapper,
            DocumentChunkMapper chunkMapper,
            KnowledgeGrantMapper grantMapper,
            BookKnowledgeBindingMapper bindingMapper,
            MinioStorageService minioStorage,
            OutboxService outboxService,
            RestClient agentRestClient
    ) {
        this.bookService = bookService;
        this.chapterMapper = chapterMapper;
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.grantMapper = grantMapper;
        this.bindingMapper = bindingMapper;
        this.minioStorage = minioStorage;
        this.outboxService = outboxService;
        this.agentRestClient = agentRestClient;
    }

    @Autowired(required = false)
    public void setVipEntitlementService(VipEntitlementService vipEntitlementService) {
        this.vipEntitlementService = vipEntitlementService;
    }

    @Autowired(required = false)
    public void setChapterSectionMapper(ChapterSectionMapper chapterSectionMapper) {
        this.chapterSectionMapper = chapterSectionMapper;
    }

    public List<RagDtos.KnowledgeDocumentResponse> listDocuments(UUID ownerId, UUID bookId) {
        bookService.requireOwnedBook(ownerId, bookId);
        var query = new LambdaQueryWrapper<KnowledgeDocumentEntity>()
                .eq(KnowledgeDocumentEntity::getBookId, bookId)
                .isNull(KnowledgeDocumentEntity::getDeletedAt)
                .orderByDesc(KnowledgeDocumentEntity::getUpdatedAt);
        return documentMapper.selectList(query).stream().map(KnowledgeDocumentService::toResponse).toList();
    }

    public RagDtos.KnowledgeDocumentResponse getDocument(UUID ownerId, UUID bookId, UUID docId) {
        return toResponse(requireOwnedDocument(ownerId, bookId, docId));
    }

    @Transactional
    public RagDtos.KnowledgeDocumentResponse uploadDocument(
            UUID ownerId,
            UUID bookId,
            MultipartFile file,
            String docTypeRaw,
            Integer chapterNo,
            String titleOverride
    ) {
        bookService.requireOwnedBook(ownerId, bookId);
        byte[] bytes = readAndValidateFile(file, USER_MAX_FILE_BYTES);
        String sha256 = sha256Hex(bytes);

        // SHA-256 deduplication within this book
        KnowledgeDocumentEntity duplicate = documentMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeDocumentEntity>()
                        .eq(KnowledgeDocumentEntity::getOwnerId, ownerId)
                        .eq(KnowledgeDocumentEntity::getBookId, bookId)
                        .eq(KnowledgeDocumentEntity::getScope, "BOOK")
                        .eq(KnowledgeDocumentEntity::getContentSha256, sha256)
                        .isNull(KnowledgeDocumentEntity::getDeletedAt)
                        .ne(KnowledgeDocumentEntity::getStatus, "FAILED")
                        .last("LIMIT 1")
        );
        if (duplicate != null) {
            return toResponse(duplicate);
        }

        String originalName = file.getOriginalFilename() != null ? file.getOriginalFilename().trim() : "document.txt";
        String docType = normalizeDocType(docTypeRaw, "WORLDBOOK");
        int effectiveChapter = chapterNo != null && chapterNo >= 0 ? chapterNo : 0;
        UUID docId = UUID.randomUUID();
        String safeFileName = originalName.replaceAll("[^a-zA-Z0-9._\\-\\u4e00-\\u9fa5]", "_");
        String objectKey = "books/" + bookId + "/docs/" + docId + "/v1/" + safeFileName;

        minioStorage.upload(objectKey, new ByteArrayInputStream(bytes), bytes.length, file.getContentType());

        String title = (titleOverride != null && !titleOverride.isBlank())
                ? titleOverride.trim()
                : stripExtension(originalName);

        KnowledgeDocumentEntity entity = new KnowledgeDocumentEntity();
        entity.setId(docId);
        entity.setBookId(bookId);
        entity.setOwnerId(ownerId);
        entity.setUploadedBy(ownerId);
        entity.setScope("BOOK");
        entity.setGenreTags("");
        entity.setContentSha256(sha256);
        entity.setIsReadonly(false);
        entity.setEnabled(true);
        entity.setSourceType("UPLOAD");
        entity.setChapterNo(effectiveChapter);
        entity.setDocType(docType);
        entity.setTitle(title);
        entity.setFileName(originalName);
        entity.setContentType(file.getContentType() != null ? file.getContentType() : "application/octet-stream");
        entity.setFileSize((long) bytes.length);
        entity.setMinioBucket(minioStorage.getDefaultBucket());
        entity.setMinioObjectKey(objectKey);
        entity.setDocVersion(1);
        entity.setStatus("QUEUED");
        entity.setProgress(0);
        entity.setChunkCount(0);
        documentMapper.insert(entity);

        enqueueIngestEvent(entity);
        return toResponse(entity);
    }

    // --- Phase 5.5: Three-Scope Library (USER Personal / ASSIGNED Grants / GLOBAL Public) ---

    public List<RagDtos.LibraryDocumentResponse> listAccessibleLibrary(UUID userId, UUID bookId) {
        BookEntity book = bookId != null ? bookService.requireOwnedBook(userId, bookId) : null;

        Map<UUID, Boolean> explicitBindings = new HashMap<>();
        if (bookId != null) {
            List<BookKnowledgeBindingEntity> bindings = bindingMapper.selectList(
                    new LambdaQueryWrapper<BookKnowledgeBindingEntity>()
                            .eq(BookKnowledgeBindingEntity::getBookId, bookId)
            );
            if (bindings != null) {
                for (BookKnowledgeBindingEntity b : bindings) {
                    explicitBindings.put(b.getDocumentId(), Boolean.TRUE.equals(b.getEnabled()));
                }
            }
        }

        // Preserve deterministic ordering & avoid duplicate entries if a doc is both granted and global
        Map<UUID, RagDtos.LibraryDocumentResponse> result = new LinkedHashMap<>();

        // 1. Author's own USER-scope personal documents
        List<KnowledgeDocumentEntity> userDocs = documentMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocumentEntity>()
                        .eq(KnowledgeDocumentEntity::getOwnerId, userId)
                        .eq(KnowledgeDocumentEntity::getScope, "USER")
                        .isNull(KnowledgeDocumentEntity::getDeletedAt)
                        .orderByDesc(KnowledgeDocumentEntity::getUpdatedAt)
        );
        if (userDocs != null) {
            for (KnowledgeDocumentEntity d : userDocs) {
                boolean bound = explicitBindings.getOrDefault(d.getId(), true);
                result.put(d.getId(), toLibraryResponse(d, "USER", bound));
            }
        }

        // 2. Platform documents explicitly granted to this author (ASSIGNED, zero-copy)
        List<KnowledgeGrantEntity> grants = grantMapper.selectList(
                new LambdaQueryWrapper<KnowledgeGrantEntity>()
                        .eq(KnowledgeGrantEntity::getGranteeUserId, userId)
        );
        if (grants != null && !grants.isEmpty()) {
            List<UUID> grantedIds = grants.stream().map(KnowledgeGrantEntity::getDocumentId).distinct().toList();
            List<KnowledgeDocumentEntity> assignedDocs = documentMapper.selectList(
                    new LambdaQueryWrapper<KnowledgeDocumentEntity>()
                            .in(KnowledgeDocumentEntity::getId, grantedIds)
                            .isNull(KnowledgeDocumentEntity::getDeletedAt)
                            .orderByDesc(KnowledgeDocumentEntity::getUpdatedAt)
            );
            if (assignedDocs != null) {
                for (KnowledgeDocumentEntity d : assignedDocs) {
                    if (result.containsKey(d.getId())) continue;
                    boolean bound = explicitBindings.getOrDefault(d.getId(), true);
                    result.put(d.getId(), toLibraryResponse(d, "ASSIGNED", bound));
                }
            }
        }

        // 3. Enabled GLOBAL public platform documents
        List<KnowledgeDocumentEntity> globalDocs = documentMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocumentEntity>()
                        .eq(KnowledgeDocumentEntity::getScope, "GLOBAL")
                        .eq(KnowledgeDocumentEntity::getEnabled, true)
                        .isNull(KnowledgeDocumentEntity::getDeletedAt)
                        .orderByDesc(KnowledgeDocumentEntity::getUpdatedAt)
        );
        if (globalDocs != null) {
            for (KnowledgeDocumentEntity d : globalDocs) {
                if (result.containsKey(d.getId())) continue;
                boolean defaultBound = book == null || matchesBookGenre(d.getGenreCodes(), d.getGenreTags(),
                        book.getGenreCode(), book.getSubGenreTags(), book.getGenre());
                boolean bound = explicitBindings.getOrDefault(d.getId(), defaultBound);
                result.put(d.getId(), toLibraryResponse(d, "GLOBAL", bound));
            }
        }

        return new ArrayList<>(result.values());
    }

    @Transactional
    public RagDtos.LibraryDocumentResponse uploadPersonalDocument(
            UUID ownerId,
            MultipartFile file,
            String docTypeRaw,
            String titleOverride,
            String genreTags
    ) {
        return uploadPersonalDocument(ownerId, file, docTypeRaw, titleOverride, genreTags, null);
    }

    @Transactional
    public RagDtos.LibraryDocumentResponse uploadPersonalDocument(
            UUID ownerId,
            MultipartFile file,
            String docTypeRaw,
            String titleOverride,
            String genreTags,
            String genreCodes
    ) {
        String normalizedGenreCodes = normalizeGenreCodes(genreCodes);
        byte[] bytes = readAndValidateFile(file, USER_MAX_FILE_BYTES);
        String sha256 = sha256Hex(bytes);

        // SHA-256 deduplication in author's personal library
        KnowledgeDocumentEntity duplicate = documentMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeDocumentEntity>()
                        .eq(KnowledgeDocumentEntity::getOwnerId, ownerId)
                        .eq(KnowledgeDocumentEntity::getScope, "USER")
                        .eq(KnowledgeDocumentEntity::getContentSha256, sha256)
                        .isNull(KnowledgeDocumentEntity::getDeletedAt)
                        .ne(KnowledgeDocumentEntity::getStatus, "FAILED")
                        .last("LIMIT 1")
        );
        if (duplicate != null) {
            return toLibraryResponse(duplicate, "USER", true);
        }

        // Enforce per-user personal library quota (doc count + total bytes)
        List<KnowledgeDocumentEntity> existingUserDocs = documentMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocumentEntity>()
                        .eq(KnowledgeDocumentEntity::getOwnerId, ownerId)
                        .eq(KnowledgeDocumentEntity::getScope, "USER")
                        .isNull(KnowledgeDocumentEntity::getDeletedAt)
        );
        int count = existingUserDocs == null ? 0 : existingUserDocs.size();
        long totalBytes = existingUserDocs == null ? 0L
                : existingUserDocs.stream().mapToLong(d -> d.getFileSize() == null ? 0L : d.getFileSize()).sum();
        long requestedTotalBytes = totalBytes + bytes.length;
        if (vipEntitlementService != null) {
            VipDtos.ResolvedUserEntitlements resolved = vipEntitlementService.resolveForUser(ownerId);
            int maxDocs = resolved.entitlements().userLibraryMaxDocs();
            long maxTotalBytes = resolved.entitlements().userLibraryMaxTotalBytes();
            if (count >= maxDocs) {
                throw vipEntitlementService.quotaExceeded(
                        resolved, "LIBRARY_DOCS", maxDocs, count + 1L,
                        "当前方案（" + resolved.displayName() + "）个人资料库最多上传 " + maxDocs + " 份文档，升级会员可解锁更大容量"
                );
            }
            if (requestedTotalBytes > maxTotalBytes) {
                long maxMb = maxTotalBytes / BYTES_PER_MB;
                long reqMb = Math.ceilDiv(requestedTotalBytes, BYTES_PER_MB);
                throw vipEntitlementService.quotaExceeded(
                        resolved, "LIBRARY_STORAGE_MB", maxMb, reqMb,
                        "当前方案（" + resolved.displayName() + "）个人资料库总容量上限为 " + maxMb + " MB，升级会员可扩容"
                );
            }
        } else if (count >= USER_MAX_DOCS) {
            throw BizException.badRequest("LIBRARY_QUOTA_EXCEEDED", "个人资料库最多可上传 " + USER_MAX_DOCS + " 份文档，请先清理不需要的资料");
        } else if (requestedTotalBytes > USER_MAX_TOTAL_BYTES) {
            throw BizException.badRequest("LIBRARY_STORAGE_EXCEEDED", "个人资料库总容量已达 100MB 上限");
        }

        String originalName = file.getOriginalFilename() != null ? file.getOriginalFilename().trim() : "document.txt";
        String docType = normalizeDocType(docTypeRaw, "WORLDBOOK");
        UUID docId = UUID.randomUUID();
        String safeFileName = originalName.replaceAll("[^a-zA-Z0-9._\\-\\u4e00-\\u9fa5]", "_");
        String objectKey = "users/" + ownerId + "/library/" + docId + "/v1/" + safeFileName;

        minioStorage.upload(objectKey, new ByteArrayInputStream(bytes), bytes.length, file.getContentType());

        String title = (titleOverride != null && !titleOverride.isBlank())
                ? titleOverride.trim()
                : stripExtension(originalName);

        KnowledgeDocumentEntity entity = new KnowledgeDocumentEntity();
        entity.setId(docId);
        entity.setBookId(null);
        entity.setOwnerId(ownerId);
        entity.setUploadedBy(ownerId);
        entity.setScope("USER");
        entity.setGenreTags(normalizeGenreTags(genreTags));
        entity.setGenreCodes(normalizedGenreCodes);
        entity.setContentSha256(sha256);
        entity.setIsReadonly(false);
        entity.setEnabled(true);
        entity.setSourceType("UPLOAD");
        entity.setChapterNo(0);
        entity.setDocType(docType);
        entity.setTitle(title);
        entity.setFileName(originalName);
        entity.setContentType(file.getContentType() != null ? file.getContentType() : "application/octet-stream");
        entity.setFileSize((long) bytes.length);
        entity.setMinioBucket(minioStorage.getDefaultBucket());
        entity.setMinioObjectKey(objectKey);
        entity.setDocVersion(1);
        entity.setStatus("QUEUED");
        entity.setProgress(0);
        entity.setChunkCount(0);
        documentMapper.insert(entity);

        enqueueIngestEvent(entity);
        return toLibraryResponse(entity, "USER", true);
    }

    @Transactional
    public void deletePersonalDocument(UUID ownerId, UUID docId) {
        KnowledgeDocumentEntity doc = documentMapper.selectById(docId);
        if (doc == null || doc.getDeletedAt() != null) {
            throw BizException.notFound("DOCUMENT_NOT_FOUND", "资料文档不存在");
        }
        if (Boolean.TRUE.equals(doc.getIsReadonly())
                || !"USER".equals(doc.getScope())
                || !ownerId.equals(doc.getOwnerId())) {
            throw BizException.forbidden("DOCUMENT_READONLY", "平台公共资料或定向分配资料不可删除，可在作品中关闭挂载");
        }
        softDeleteAndEnqueue(doc);
    }

    @Transactional
    public RagDtos.LibraryDocumentResponse setBookBinding(UUID ownerId, UUID bookId, UUID docId, boolean enabled) {
        bookService.requireOwnedBook(ownerId, bookId);
        List<RagDtos.LibraryDocumentResponse> accessible = listAccessibleLibrary(ownerId, bookId);
        RagDtos.LibraryDocumentResponse target = accessible.stream()
                .filter(d -> docId.equals(d.id()))
                .findFirst()
                .orElseThrow(() -> BizException.forbidden("DOCUMENT_NOT_ACCESSIBLE", "无权挂载该参考资料"));

        BookKnowledgeBindingEntity existing = bindingMapper.selectOne(
                new LambdaQueryWrapper<BookKnowledgeBindingEntity>()
                        .eq(BookKnowledgeBindingEntity::getBookId, bookId)
                        .eq(BookKnowledgeBindingEntity::getDocumentId, docId)
        );
        if (existing == null) {
            BookKnowledgeBindingEntity entity = new BookKnowledgeBindingEntity();
            entity.setId(UUID.randomUUID());
            entity.setBookId(bookId);
            entity.setDocumentId(docId);
            entity.setEnabled(enabled);
            bindingMapper.insert(entity);
        } else {
            existing.setEnabled(enabled);
            bindingMapper.updateById(existing);
        }

        return new RagDtos.LibraryDocumentResponse(
                target.id(), target.bookId(), target.scope(), target.accessType(),
                target.docType(), target.title(), target.fileName(), target.fileSize(),
                target.genreTags(), target.readonly(), target.enabled(), enabled,
                target.status(), target.progress(), target.chunkCount(), target.errorMessage(),
                target.createdAt(), target.updatedAt(), target.genreCodes()
        );
    }

    // --- Admin Platform Public / Assigned RAG Management ---

    @Transactional
    public RagDtos.LibraryDocumentResponse uploadPlatformDocument(
            UUID adminId,
            MultipartFile file,
            String docTypeRaw,
            String titleOverride,
            String genreTags,
            boolean publicToAll
    ) {
        return uploadPlatformDocument(adminId, file, docTypeRaw, titleOverride, genreTags, null, publicToAll);
    }

    @Transactional
    public RagDtos.LibraryDocumentResponse uploadPlatformDocument(
            UUID adminId,
            MultipartFile file,
            String docTypeRaw,
            String titleOverride,
            String genreTags,
            String genreCodes,
            boolean publicToAll
    ) {
        String normalizedGenreCodes = normalizeGenreCodes(genreCodes);
        byte[] bytes = readAndValidateFile(file, 25L * 1024 * 1024);
        String sha256 = sha256Hex(bytes);

        String originalName = file.getOriginalFilename() != null ? file.getOriginalFilename().trim() : "platform-doc.txt";
        String docType = normalizeDocType(docTypeRaw, "WORLDBOOK");
        UUID docId = UUID.randomUUID();
        String safeFileName = originalName.replaceAll("[^a-zA-Z0-9._\\-\\u4e00-\\u9fa5]", "_");
        String objectKey = "platform/global/" + docId + "/v1/" + safeFileName;

        minioStorage.upload(objectKey, new ByteArrayInputStream(bytes), bytes.length, file.getContentType());
        String bucket = minioStorage.getDefaultBucket();
        TransactionCallbacks.afterRollback(() -> minioStorage.deleteQuietly(bucket, objectKey));

        String title = (titleOverride != null && !titleOverride.isBlank())
                ? titleOverride.trim()
                : stripExtension(originalName);

        KnowledgeDocumentEntity entity = new KnowledgeDocumentEntity();
        entity.setId(docId);
        entity.setBookId(null);
        entity.setOwnerId(adminId);
        entity.setUploadedBy(adminId);
        entity.setScope("GLOBAL");
        entity.setGenreTags(normalizeGenreTags(genreTags));
        entity.setGenreCodes(normalizedGenreCodes);
        entity.setContentSha256(sha256);
        entity.setIsReadonly(true);
        entity.setEnabled(publicToAll);
        entity.setSourceType("UPLOAD");
        entity.setChapterNo(0);
        entity.setDocType(docType);
        entity.setTitle(title);
        entity.setFileName(originalName);
        entity.setContentType(file.getContentType() != null ? file.getContentType() : "application/octet-stream");
        entity.setFileSize((long) bytes.length);
        entity.setMinioBucket(bucket);
        entity.setMinioObjectKey(objectKey);
        entity.setDocVersion(1);
        entity.setStatus("QUEUED");
        entity.setProgress(0);
        entity.setChunkCount(0);
        documentMapper.insert(entity);

        enqueueIngestEvent(entity);
        return toLibraryResponse(entity, "GLOBAL", publicToAll);
    }

    @Transactional
    public GrantChange grantDocumentToUser(UUID adminId, UUID docId, UUID granteeUserId, String permission) {
        KnowledgeDocumentEntity doc = requireActiveDocumentForUpdate(docId);
        if (!"GLOBAL".equals(doc.getScope())) {
            throw BizException.badRequest("INVALID_GRANT_SCOPE", "仅支持对平台资料库文档执行定向授权");
        }
        KnowledgeGrantEntity existing = grantMapper.selectOne(grantQuery(docId, granteeUserId));
        if (existing != null) {
            return new GrantChange(existing, existing);
        }
        KnowledgeGrantEntity grant = new KnowledgeGrantEntity();
        grant.setId(UUID.randomUUID());
        grant.setDocumentId(docId);
        grant.setGranteeUserId(granteeUserId);
        grant.setGrantedBy(adminId);
        grant.setPermission(permission == null || permission.isBlank() ? "READ" : permission.trim().toUpperCase(Locale.ROOT));
        grantMapper.insert(grant);
        return new GrantChange(null, grant);
    }

    @Transactional
    public KnowledgeGrantEntity revokeDocumentGrant(UUID docId, UUID granteeUserId) {
        requirePlatformDocumentForUpdate(docId);
        var query = grantQuery(docId, granteeUserId);
        KnowledgeGrantEntity before = grantMapper.selectOne(query);
        grantMapper.delete(query);
        return before;
    }

    public record GrantChange(KnowledgeGrantEntity before, KnowledgeGrantEntity after) {}

    private static LambdaQueryWrapper<KnowledgeGrantEntity> grantQuery(UUID docId, UUID granteeUserId) {
        return Wrappers.<KnowledgeGrantEntity>lambdaQuery()
                .eq(KnowledgeGrantEntity::getDocumentId, docId)
                .eq(KnowledgeGrantEntity::getGranteeUserId, granteeUserId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public KnowledgeDocumentEntity requirePlatformDocumentForUpdate(UUID docId) {
        KnowledgeDocumentEntity doc = requireActiveDocumentForUpdate(docId);
        if (!"GLOBAL".equals(doc.getScope())) {
            throw BizException.notFound("DOCUMENT_NOT_FOUND", "平台资料文档不存在");
        }
        return doc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void deletePlatformDocument(KnowledgeDocumentEntity doc) {
        if (!"GLOBAL".equals(doc.getScope()) || doc.getDeletedAt() != null) {
            throw BizException.notFound("DOCUMENT_NOT_FOUND", "平台资料文档不存在");
        }
        softDeleteAndEnqueue(doc);
    }

    public List<RagDtos.LibraryDocumentResponse> listPlatformDocuments() {
        List<KnowledgeDocumentEntity> docs = documentMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocumentEntity>()
                        .eq(KnowledgeDocumentEntity::getScope, "GLOBAL")
                        .isNull(KnowledgeDocumentEntity::getDeletedAt)
                        .orderByDesc(KnowledgeDocumentEntity::getUpdatedAt)
        );
        return (docs == null ? List.<KnowledgeDocumentEntity>of() : docs).stream()
                .map(d -> toLibraryResponse(d, "GLOBAL", Boolean.TRUE.equals(d.getEnabled())))
                .toList();
    }

    /**
     * Authoritatively resolves which READY non-BOOK documents (USER, ASSIGNED, GLOBAL)
     * are enabled for a given (ownerId, bookId) pair for multi-scope Milvus pre-filtering.
     */
    public RagDtos.InternalAccessibleDocsResponse resolveAccessibleDocScopes(UUID ownerId, UUID bookId) {
        List<RagDtos.LibraryDocumentResponse> accessible = listAccessibleLibrary(ownerId, bookId);
        List<String> userIds = new ArrayList<>();
        List<String> assignedIds = new ArrayList<>();
        List<String> globalIds = new ArrayList<>();

        for (RagDtos.LibraryDocumentResponse d : accessible) {
            if (!d.boundToBook() || !"READY".equals(d.status())) {
                continue;
            }
            String idStr = d.id().toString();
            switch (d.accessType()) {
                case "USER" -> userIds.add(idStr);
                case "ASSIGNED" -> assignedIds.add(idStr);
                case "GLOBAL" -> globalIds.add(idStr);
                default -> {}
            }
        }
        return new RagDtos.InternalAccessibleDocsResponse(userIds, assignedIds, globalIds);
    }

    @Transactional
    public RagDtos.KnowledgeDocumentResponse reprocessDocument(UUID ownerId, UUID bookId, UUID docId) {
        bookService.requireOwnedBook(ownerId, bookId);
        KnowledgeDocumentEntity doc = requireActiveDocumentForUpdate(docId);
        if (!bookId.equals(doc.getBookId())) {
            throw BizException.notFound("DOCUMENT_NOT_FOUND", "知识库文档不存在");
        }
        return requeueDocument(doc);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public KnowledgeDocumentEntity requireActiveDocumentForUpdate(UUID docId) {
        KnowledgeDocumentEntity doc = loadDocumentForUpdate(docId);
        if (doc.getDeletedAt() != null) {
            throw BizException.notFound("DOCUMENT_NOT_FOUND", "知识库文档不存在");
        }
        return doc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public RagDtos.KnowledgeDocumentResponse requeueDocument(KnowledgeDocumentEntity doc) {
        int nextVersion = (doc.getDocVersion() == null ? 1 : doc.getDocVersion()) + 1;
        doc.setDocVersion(nextVersion);
        doc.setStatus("QUEUED");
        doc.setProgress(0);
        doc.setErrorMessage(null);
        doc.setUpdatedAt(OffsetDateTime.now());
        documentMapper.update(null, Wrappers.<KnowledgeDocumentEntity>lambdaUpdate()
                .eq(KnowledgeDocumentEntity::getId, doc.getId())
                .set(KnowledgeDocumentEntity::getDocVersion, nextVersion)
                .set(KnowledgeDocumentEntity::getStatus, doc.getStatus())
                .set(KnowledgeDocumentEntity::getProgress, doc.getProgress())
                .set(KnowledgeDocumentEntity::getErrorMessage, null)
                .set(KnowledgeDocumentEntity::getUpdatedAt, doc.getUpdatedAt()));

        enqueueIngestEvent(doc);
        return toResponse(doc);
    }

    @Transactional
    public void deleteDocument(UUID ownerId, UUID bookId, UUID docId) {
        KnowledgeDocumentEntity doc = requireOwnedDocument(ownerId, bookId, docId);
        softDeleteAndEnqueue(doc);
    }

    @Transactional
    public void deleteBySourceChapter(UUID ownerId, UUID bookId, UUID chapterId) {
        KnowledgeDocumentEntity doc = documentMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeDocumentEntity>()
                        .eq(KnowledgeDocumentEntity::getBookId, bookId)
                        .eq(KnowledgeDocumentEntity::getSourceChapterId, chapterId)
                        .isNull(KnowledgeDocumentEntity::getDeletedAt)
        );
        if (doc != null && ownerId.equals(doc.getOwnerId())) {
            softDeleteAndEnqueue(doc);
        }
    }

    @Transactional
    public RagDtos.SyncChapterResponse syncChapter(
            UUID ownerId,
            UUID bookId,
            UUID chapterId,
            boolean extractMemory
    ) {
        bookService.requireOwnedBook(ownerId, bookId);
        if (chapterSectionMapper != null) {
            chapterMapper.lockForStructure(bookId, chapterId);
        }
        ChapterEntity chapter = chapterMapper.selectById(chapterId);
        if (chapter == null || !bookId.equals(chapter.getBookId())) {
            throw BizException.notFound("CHAPTER_NOT_FOUND", "章节不存在");
        }
        String text = chapterContent(chapter);
        if (text.isBlank()) {
            throw BizException.badRequest("EMPTY_CHAPTER_CONTENT", "章节正文为空，无法同步索引或抽取记忆");
        }

        KnowledgeDocumentEntity existing = documentMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeDocumentEntity>()
                        .eq(KnowledgeDocumentEntity::getBookId, bookId)
                        .eq(KnowledgeDocumentEntity::getSourceChapterId, chapterId)
                        .isNull(KnowledgeDocumentEntity::getDeletedAt)
        );

        KnowledgeDocumentEntity doc;
        if (existing == null) {
            doc = new KnowledgeDocumentEntity();
            doc.setId(UUID.randomUUID());
            doc.setBookId(bookId);
            doc.setOwnerId(ownerId);
            doc.setUploadedBy(ownerId);
            doc.setScope("BOOK");
            doc.setGenreTags("");
            doc.setContentSha256(sha256Hex(text.getBytes(StandardCharsets.UTF_8)));
            doc.setIsReadonly(false);
            doc.setEnabled(true);
            doc.setSourceType("CHAPTER");
            doc.setSourceChapterId(chapterId);
            doc.setChapterNo(chapter.getChapterNo());
            doc.setDocType("CHAPTER");
            doc.setTitle("第" + chapter.getChapterNo() + "章 " + chapter.getTitle());
            doc.setFileName("chapter-" + chapter.getChapterNo() + ".md");
            doc.setContentType("text/markdown");
            doc.setFileSize((long) text.getBytes(StandardCharsets.UTF_8).length);
            doc.setMinioBucket("");
            doc.setMinioObjectKey("");
            doc.setDocVersion(1);
            doc.setStatus("QUEUED");
            doc.setProgress(0);
            doc.setChunkCount(0);
            documentMapper.insert(doc);
        } else {
            existing.setChapterNo(chapter.getChapterNo());
            existing.setTitle("第" + chapter.getChapterNo() + "章 " + chapter.getTitle());
            existing.setFileSize((long) text.getBytes(StandardCharsets.UTF_8).length);
            existing.setContentSha256(sha256Hex(text.getBytes(StandardCharsets.UTF_8)));
            existing.setDocVersion((existing.getDocVersion() == null ? 1 : existing.getDocVersion()) + 1);
            existing.setStatus("QUEUED");
            existing.setProgress(0);
            existing.setErrorMessage(null);
            documentMapper.updateById(existing);
            doc = existing;
        }

        enqueueIngestEvent(doc, findSingleSectionForIngest(chapter));

        if (extractMemory) {
            enqueueMemoryExtractEvent(ownerId, bookId, chapter, doc.getDocVersion());
        }

        return new RagDtos.SyncChapterResponse(toResponse(doc), extractMemory);
    }

    /**
     * Silently triggers background RAG indexing + structured memory extraction when a chapter
     * has meaningful new/changed content, keeping RAG fully automatic for writers.
     */
    public void syncChapterInBackgroundIfNeeded(UUID ownerId, UUID bookId, ChapterEntity chapter) {
        if (chapter == null) {
            return;
        }
        try {
            String content = chapterContent(chapter);
            KnowledgeDocumentEntity existing = documentMapper.selectOne(
                    new LambdaQueryWrapper<KnowledgeDocumentEntity>()
                            .eq(KnowledgeDocumentEntity::getBookId, bookId)
                            .eq(KnowledgeDocumentEntity::getSourceChapterId, chapter.getId())
                            .isNull(KnowledgeDocumentEntity::getDeletedAt)
            );
            if (content.isBlank()) {
                if (existing != null) softDeleteAndEnqueue(existing);
                return;
            }
            if (existing == null && content.trim().length() < 60) return;
            String hash = sha256Hex(content.getBytes(StandardCharsets.UTF_8));
            if (existing != null && !"FAILED".equals(existing.getStatus()) && hash.equals(existing.getContentSha256())) {
                return;
            }
            syncChapter(ownerId, bookId, chapter.getId(), chapterSectionMapper == null);
        } catch (Exception ex) {
            log.warn("Background chapter RAG/memory sync skipped for chapter {}: {}", chapter.getId(), ex.getMessage());
        }
    }

    @Transactional
    public void triggerMemoryExtractOnly(UUID ownerId, UUID bookId, UUID chapterId) {
        bookService.requireOwnedBook(ownerId, bookId);
        ChapterEntity chapter = chapterMapper.selectById(chapterId);
        if (chapter == null || !bookId.equals(chapter.getBookId())) {
            throw BizException.notFound("CHAPTER_NOT_FOUND", "章节不存在");
        }
        if (chapter.getContent() == null || chapter.getContent().isBlank()) {
            throw BizException.badRequest("EMPTY_CHAPTER_CONTENT", "章节正文为空，无法抽取记忆");
        }
        enqueueMemoryExtractEvent(ownerId, bookId, chapter, (int) (System.currentTimeMillis() / 1000));
    }

    // --- Internal Worker Callbacks (Dual-Write Monotonic Version Guards) ---

    @Transactional
    public KnowledgeDocumentEntity updateIngestionStatus(UUID docId, RagDtos.InternalStatusUpdateRequest req) {
        KnowledgeDocumentEntity doc = loadDocumentForUpdate(docId);
        int incomingVersion = req.docVersion() != null ? req.docVersion() : 1;
        int currentVersion = doc.getDocVersion() != null ? doc.getDocVersion() : 1;
        if (doc.getDeletedAt() != null || incomingVersion < currentVersion) {
            log.info("Ignored stale status update for doc {} (incoming v{} < current v{}, deleted={})",
                    docId, incomingVersion, currentVersion, doc.getDeletedAt() != null);
            return doc;
        }
        doc.setStatus(req.status().trim().toUpperCase(Locale.ROOT));
        if (req.progress() != null) {
            doc.setProgress(Math.max(0, Math.min(100, req.progress())));
        }
        if (req.chunkCount() != null) {
            doc.setChunkCount(req.chunkCount());
        }
        doc.setErrorMessage(req.errorMessage());
        documentMapper.updateById(doc);
        return doc;
    }

    @Transactional
    public int saveDocumentChunks(UUID docId, RagDtos.InternalSaveChunksRequest req) {
        KnowledgeDocumentEntity doc = loadDocumentForUpdate(docId);
        int incomingVersion = req.docVersion() != null ? req.docVersion() : 1;
        int currentVersion = doc.getDocVersion() != null ? doc.getDocVersion() : 1;
        if (doc.getDeletedAt() != null || incomingVersion < currentVersion) {
            log.info("Ignored stale chunks save for doc {} (incoming v{} < current v{})",
                    docId, incomingVersion, currentVersion);
            return 0;
        }

        chunkMapper.delete(new LambdaQueryWrapper<DocumentChunkEntity>()
                .eq(DocumentChunkEntity::getDocumentId, docId));

        if (req.chunks() == null || req.chunks().isEmpty()) {
            return 0;
        }

        List<RagDtos.InternalChunkInput> parents = new ArrayList<>();
        List<RagDtos.InternalChunkInput> children = new ArrayList<>();
        for (RagDtos.InternalChunkInput c : req.chunks()) {
            if ("PARENT".equalsIgnoreCase(c.chunkLevel())) {
                parents.add(c);
            } else {
                children.add(c);
            }
        }
        int inserted = 0;
        for (RagDtos.InternalChunkInput c : parents) {
            insertChunkRow(doc, incomingVersion, c);
            inserted++;
        }
        for (RagDtos.InternalChunkInput c : children) {
            insertChunkRow(doc, incomingVersion, c);
            inserted++;
        }
        return inserted;
    }

    /**
     * Multi-scope authoritative Parent Chunk loader (Small-to-Big):
     * verifies tenant & scope authorization across BOOK, USER, ASSIGNED, and GLOBAL scopes.
     */
    public List<RagDtos.InternalParentChunkResponse> loadParentChunks(UUID ownerId, UUID bookId, List<UUID> parentIds) {
        bookService.requireOwnedBook(ownerId, bookId);
        if (parentIds == null || parentIds.isEmpty()) {
            return List.of();
        }
        List<DocumentChunkEntity> chunks = chunkMapper.selectList(
                new LambdaQueryWrapper<DocumentChunkEntity>()
                        .in(DocumentChunkEntity::getId, parentIds)
        );
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }

        RagDtos.InternalAccessibleDocsResponse scopes = resolveAccessibleDocScopes(ownerId, bookId);
        Set<String> userSet = new HashSet<>(scopes.userDocIds());
        Set<String> assignedSet = new HashSet<>(scopes.assignedDocIds());
        Set<String> globalSet = new HashSet<>(scopes.globalDocIds());

        Set<UUID> docIds = chunks.stream().map(DocumentChunkEntity::getDocumentId).collect(java.util.stream.Collectors.toSet());
        Map<UUID, KnowledgeDocumentEntity> docs = new HashMap<>();
        for (KnowledgeDocumentEntity d : documentMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocumentEntity>()
                        .in(KnowledgeDocumentEntity::getId, docIds)
                        .isNull(KnowledgeDocumentEntity::getDeletedAt)
        )) {
            docs.put(d.getId(), d);
        }

        List<RagDtos.InternalParentChunkResponse> result = new ArrayList<>();
        for (DocumentChunkEntity c : chunks) {
            KnowledgeDocumentEntity doc = docs.get(c.getDocumentId());
            if (doc == null || !Objects.equals(doc.getDocVersion(), c.getDocVersion())) {
                continue;
            }
            String docIdStr = doc.getId().toString();
            String effectiveScope;
            if ("BOOK".equals(doc.getScope()) || doc.getScope() == null) {
                if (!bookId.equals(doc.getBookId()) || !ownerId.equals(doc.getOwnerId())) {
                    continue;
                }
                effectiveScope = "BOOK";
            } else if (userSet.contains(docIdStr)) {
                effectiveScope = "USER";
            } else if (assignedSet.contains(docIdStr)) {
                effectiveScope = "ASSIGNED";
            } else if (globalSet.contains(docIdStr)) {
                effectiveScope = "GLOBAL";
            } else {
                // Defense in depth: deny unauthorized or disabled doc chunks
                continue;
            }

            result.add(new RagDtos.InternalParentChunkResponse(
                    c.getId(),
                    c.getDocumentId(),
                    doc.getTitle(),
                    doc.getDocType(),
                    c.getDocVersion(),
                    c.getChapterNo(),
                    c.getSceneTitle(),
                    c.getContent(),
                    effectiveScope
            ));
        }
        return result;
    }

    public RagDtos.InternalChapterPayload loadChapterForWorker(UUID chapterId) {
        ChapterEntity chapter = chapterMapper.selectById(chapterId);
        if (chapter == null) {
            throw BizException.notFound("CHAPTER_NOT_FOUND", "章节不存在");
        }
        var book = bookService.getBookByIdUnchecked(chapter.getBookId());
        return new RagDtos.InternalChapterPayload(
                chapter.getId(),
                chapter.getBookId(),
                book.getOwnerId(),
                chapter.getChapterNo(),
                chapter.getTitle(),
                chapterContent(chapter),
                chapter.getStatus()
        );
    }

    private String chapterContent(ChapterEntity chapter) {
        if (chapterSectionMapper != null && StructureService.MULTI_SECTION.equals(chapter.getStructureMode())) {
            List<ChapterSectionEntity> sections = chapterSectionMapper.selectList(
                    ChapterSectionMapper.inChapter(chapter.getId()).orderByAsc(ChapterSectionEntity::getSectionNo));
            if (!sections.isEmpty()) return StructureService.combineSections(sections);
        }
        return chapter.getContent() == null ? "" : chapter.getContent();
    }

    private ChapterSectionEntity findSingleSectionForIngest(ChapterEntity chapter) {
        if (chapterSectionMapper == null || StructureService.MULTI_SECTION.equals(chapter.getStructureMode())) {
            return null;
        }
        return chapterSectionMapper.selectOne(ChapterSectionMapper.inChapter(chapter.getId())
                .select(ChapterSectionEntity::getId, ChapterSectionEntity::getNarrativeOrder)
                .eq(ChapterSectionEntity::getIsDefault, true).last("LIMIT 1"));
    }

    public KnowledgeDocumentEntity loadDocumentUnchecked(UUID docId) {
        KnowledgeDocumentEntity doc = documentMapper.selectById(docId);
        if (doc == null) {
            throw BizException.notFound("DOCUMENT_NOT_FOUND", "知识库文档不存在");
        }
        return doc;
    }

    private KnowledgeDocumentEntity loadDocumentForUpdate(UUID docId) {
        KnowledgeDocumentEntity doc = documentMapper.selectOne(Wrappers.<KnowledgeDocumentEntity>lambdaQuery()
                .eq(KnowledgeDocumentEntity::getId, docId)
                .last("FOR UPDATE"));
        if (doc == null) {
            throw BizException.notFound("DOCUMENT_NOT_FOUND", "知识库文档不存在");
        }
        return doc;
    }

    public Map<String, Object> searchKnowledge(UUID ownerId, UUID bookId, RagDtos.RagSearchRequest req) {
        bookService.requireOwnedBook(ownerId, bookId);
        RagDtos.InternalAccessibleDocsResponse scopes = resolveAccessibleDocScopes(ownerId, bookId);
        Map<String, Object> body = new HashMap<>();
        body.put("owner_id", ownerId.toString());
        body.put("book_id", bookId.toString());
        body.put("query", req.query().trim());
        body.put("top_k", req.topK() != null ? Math.max(1, Math.min(req.topK(), 20)) : 5);
        if (req.maxChapterNo() != null) {
            body.put("max_chapter_no", req.maxChapterNo());
        }
        if (req.docType() != null && !req.docType().isBlank()) {
            body.put("doc_type", req.docType().trim().toUpperCase(Locale.ROOT));
        }
        body.put("rewrite_query", Boolean.TRUE.equals(req.rewriteQuery()));
        body.put("user_doc_ids", scopes.userDocIds());
        body.put("assigned_doc_ids", scopes.assignedDocIds());
        body.put("global_doc_ids", scopes.globalDocIds());

        try {
            Map<String, Object> resp = agentRestClient.post()
                    .uri("/internal/v1/rag/search")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            return resp != null ? resp : Map.of("hits", List.of());
        } catch (Exception ex) {
            throw BizException.serviceUnavailable("RAG_SEARCH_FAILED", "混合检索服务暂时不可用: " + ex.getMessage());
        }
    }

    // --- Helpers ---

    private byte[] readAndValidateFile(MultipartFile file, long maxBytes) {
        if (file == null || file.isEmpty()) {
            throw BizException.badRequest("EMPTY_FILE", "请上传非空文档文件");
        }
        if (file.getSize() > maxBytes) {
            throw BizException.badRequest("FILE_TOO_LARGE", "单个文件不能超过 " + (maxBytes / (1024 * 1024)) + "MB");
        }
        String originalName = file.getOriginalFilename() != null ? file.getOriginalFilename().trim() : "document.txt";
        String ext = extractExtension(originalName);
        if (!ALLOWED_EXTS.contains(ext)) {
            throw BizException.badRequest("UNSUPPORTED_FILE_TYPE", "仅支持 PDF / DOCX / MD / TXT 格式，当前: " + ext);
        }
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw BizException.badRequest("FILE_READ_ERROR", "读取上传文件流失败: " + ex.getMessage());
        }
    }

    private void insertChunkRow(KnowledgeDocumentEntity doc, int version, RagDtos.InternalChunkInput c) {
        DocumentChunkEntity row = new DocumentChunkEntity();
        row.setId(c.id() != null ? c.id() : UUID.randomUUID());
        row.setDocumentId(doc.getId());
        row.setBookId(doc.getBookId());
        row.setOwnerId(doc.getOwnerId());
        row.setScope(doc.getScope() != null ? doc.getScope() : "BOOK");
        row.setDocVersion(version);
        row.setChunkLevel(c.chunkLevel() != null ? c.chunkLevel().toUpperCase(Locale.ROOT) : "CHILD");
        row.setParentChunkId(c.parentChunkId());
        row.setChunkIndex(c.chunkIndex() != null ? c.chunkIndex() : 0);
        row.setChapterNo(c.chapterNo() != null ? c.chapterNo() : doc.getChapterNo());
        row.setNarrativeOrder(c.narrativeOrder() != null ? c.narrativeOrder() : 0L);
        row.setSectionId(c.sectionId());
        row.setSceneTitle(c.sceneTitle() != null ? c.sceneTitle() : "");
        row.setContent(c.content() != null ? c.content() : "");
        row.setTokenCount(c.tokenCount() != null ? c.tokenCount() : 0);
        row.setMetadataJson(c.metadataJson() != null ? c.metadataJson() : "{}");
        chunkMapper.insert(row);
    }

    private void softDeleteAndEnqueue(KnowledgeDocumentEntity doc) {
        int nextVersion = (doc.getDocVersion() == null ? 1 : doc.getDocVersion()) + 1;
        doc.setDocVersion(nextVersion);
        doc.setStatus("DELETED");
        doc.setDeletedAt(OffsetDateTime.now());
        documentMapper.updateById(doc);

        chunkMapper.delete(new LambdaQueryWrapper<DocumentChunkEntity>()
                .eq(DocumentChunkEntity::getDocumentId, doc.getId()));

        String scope = doc.getScope() != null ? doc.getScope() : "BOOK";
        String partitionKey = resolveMilvusPartitionBookId(doc.getBookId(), scope);

        Map<String, Object> payload = new HashMap<>();
        payload.put("document_id", doc.getId().toString());
        payload.put("book_id", partitionKey);
        payload.put("owner_id", doc.getOwnerId().toString());
        payload.put("scope", scope);
        payload.put("doc_version", nextVersion);
        payload.put("idempotency_key", "DOC_DELETE:" + doc.getId() + ":v" + nextVersion);

        outboxService.enqueue(
                "DOCUMENT",
                doc.getId(),
                "DOC_DELETE",
                RabbitMqTopologyConfig.RK_DELETE,
                "DOC_DELETE:" + doc.getId() + ":v" + nextVersion,
                payload
        );

        TransactionCallbacks.afterCommit(() -> minioStorage.deleteQuietly(doc.getMinioBucket(), doc.getMinioObjectKey()));
    }

    private void enqueueIngestEvent(KnowledgeDocumentEntity doc) {
        enqueueIngestEvent(doc, null);
    }

    private void enqueueIngestEvent(KnowledgeDocumentEntity doc, ChapterSectionEntity section) {
        String idempotencyKey = "DOC_INGEST:" + doc.getId() + ":v" + doc.getDocVersion();
        String scope = doc.getScope() != null ? doc.getScope() : "BOOK";
        String partitionKey = resolveMilvusPartitionBookId(doc.getBookId(), scope);
        boolean isChapter = "CHAPTER".equals(doc.getSourceType());

        Map<String, Object> payload = new HashMap<>();
        payload.put("document_id", doc.getId().toString());
        payload.put("book_id", partitionKey);
        payload.put("owner_id", doc.getOwnerId().toString());
        payload.put("scope", scope);
        payload.put("source_type", doc.getSourceType());
        payload.put("source_chapter_id", doc.getSourceChapterId() != null ? doc.getSourceChapterId().toString() : null);
        payload.put("chapter_no", doc.getChapterNo());
        payload.put("doc_type", doc.getDocType());
        payload.put("title", doc.getTitle());
        payload.put("file_name", doc.getFileName());
        payload.put("minio_bucket", doc.getMinioBucket());
        payload.put("minio_object_key", doc.getMinioObjectKey());
        payload.put("doc_version", doc.getDocVersion());
        payload.put("idempotency_key", idempotencyKey);
        if (section != null) {
            payload.put("section_id", section.getId().toString());
            payload.put("narrative_order", section.getNarrativeOrder());
        }

        // Priority queue isolation: real-time chapter indexing uses dedicated high-priority lane
        // so large uploaded PDFs never block ongoing writing continuity.
        String routingKey = isChapter ? RabbitMqTopologyConfig.RK_CHAPTER_INGEST : RabbitMqTopologyConfig.RK_INGEST;

        outboxService.enqueue(
                "DOCUMENT",
                doc.getId(),
                isChapter ? "CHAPTER_SYNC" : "DOC_INGEST",
                routingKey,
                idempotencyKey,
                payload
        );
    }

    private void enqueueMemoryExtractEvent(UUID ownerId, UUID bookId, ChapterEntity chapter, int triggerSeq) {
        String idempotencyKey = "MEMORY_EXTRACT:" + chapter.getId() + ":v" + triggerSeq;
        Map<String, Object> payload = new HashMap<>();
        payload.put("book_id", bookId.toString());
        payload.put("owner_id", ownerId.toString());
        payload.put("chapter_id", chapter.getId().toString());
        payload.put("chapter_no", chapter.getChapterNo());
        payload.put("title", chapter.getTitle());
        payload.put("idempotency_key", idempotencyKey);

        outboxService.enqueue(
                "CHAPTER",
                chapter.getId(),
                "MEMORY_EXTRACT",
                RabbitMqTopologyConfig.RK_MEMORY,
                idempotencyKey,
                payload
        );
    }

    private KnowledgeDocumentEntity requireOwnedDocument(UUID ownerId, UUID bookId, UUID docId) {
        bookService.requireOwnedBook(ownerId, bookId);
        KnowledgeDocumentEntity doc = documentMapper.selectById(docId);
        if (doc == null || doc.getDeletedAt() != null || !bookId.equals(doc.getBookId())) {
            throw BizException.notFound("DOCUMENT_NOT_FOUND", "知识库文档不存在");
        }
        return doc;
    }

    public static String resolveMilvusPartitionBookId(UUID bookId, String scope) {
        if (bookId != null) {
            return bookId.toString();
        }
        return "GLOBAL".equalsIgnoreCase(scope) ? PARTITION_GLOBAL : PARTITION_USER;
    }

    public static boolean genreMatches(String genreTags, String bookGenre) {
        if (genreTags == null || genreTags.isBlank()) {
            return true;
        }
        if (bookGenre == null || bookGenre.isBlank()) {
            return false;
        }
        String normBook = bookGenre.trim().toLowerCase(Locale.ROOT);
        for (String rawTag : genreTags.split("[,，、;/\\s]+")) {
            String tag = rawTag.trim().toLowerCase(Locale.ROOT);
            if (!tag.isEmpty() && (normBook.contains(tag) || tag.contains(normBook))) {
                return true;
            }
        }
        return false;
    }

    public static boolean matchesBookGenre(
            String docGenreCodes, String docGenreTags, String bookGenreCode, String bookSubGenreTags, String bookLegacyGenre
    ) {
        if (Objects.requireNonNullElse(docGenreCodes, "").isBlank()
                && Objects.requireNonNullElse(docGenreTags, "").isBlank()) {
            return true;
        }
        Set<String> codes = genreTokens(docGenreCodes);
        String bookCode = Objects.requireNonNullElse(bookGenreCode, "").strip().toUpperCase(Locale.ROOT);
        if (codes.contains("ALL") || codes.contains(bookCode)) return true;

        Set<String> tags = genreTokens(docGenreTags);
        return !tags.isEmpty() && (!Collections.disjoint(tags, genreTokens(bookSubGenreTags))
                || !Collections.disjoint(tags, genreTokens(bookLegacyGenre)));
    }

    private static String normalizeGenreCodes(String raw) {
        String codes = String.join(",", genreTokens(raw));
        if (codes.length() > 255) {
            throw BizException.badRequest("INVALID_GENRE_CODES", "资料题材代码总长最多 255 个字符");
        }
        return codes;
    }

    private static Set<String> genreTokens(String raw) {
        if (raw == null || raw.isBlank()) return Set.of();
        Set<String> tokens = new LinkedHashSet<>();
        for (String part : raw.split("[,，、;；/\\r\\n]+")) {
            String token = part.strip().toUpperCase(Locale.ROOT);
            if (!token.isEmpty()) tokens.add(token);
        }
        return tokens;
    }

    private static String normalizeGenreTags(String raw) {
        if (raw == null || raw.isBlank()) return "";
        List<String> tags = new ArrayList<>();
        for (String t : raw.split("[,，、;/\\s]+")) {
            String clean = t.trim();
            if (!clean.isEmpty()) tags.add(clean);
        }
        return String.join(",", tags);
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static String extractExtension(String filename) {
        int idx = filename.lastIndexOf('.');
        if (idx < 0) return "";
        return filename.substring(idx).toLowerCase(Locale.ROOT);
    }

    private static String stripExtension(String filename) {
        int idx = filename.lastIndexOf('.');
        return idx > 0 ? filename.substring(0, idx) : filename;
    }

    private static String normalizeDocType(String raw, String fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        String upper = raw.trim().toUpperCase(Locale.ROOT);
        if (!VALID_DOC_TYPES.contains(upper)) {
            throw BizException.badRequest("INVALID_DOC_TYPE", "不支持的文档类型: " + upper);
        }
        return upper;
    }

    public static RagDtos.KnowledgeDocumentResponse toResponse(KnowledgeDocumentEntity d) {
        return new RagDtos.KnowledgeDocumentResponse(
                d.getId(), d.getBookId(), d.getSourceType(), d.getSourceChapterId(),
                d.getChapterNo(), d.getDocType(), d.getTitle(), d.getFileName(),
                d.getContentType(), d.getFileSize(), d.getDocVersion(),
                d.getStatus(), d.getProgress(), d.getChunkCount(), d.getErrorMessage(),
                d.getCreatedAt(), d.getUpdatedAt()
        );
    }

    public static RagDtos.LibraryDocumentResponse toLibraryResponse(
            KnowledgeDocumentEntity d,
            String accessType,
            boolean boundToBook
    ) {
        return new RagDtos.LibraryDocumentResponse(
                d.getId(),
                d.getBookId(),
                d.getScope() != null ? d.getScope() : "BOOK",
                accessType,
                d.getDocType(),
                d.getTitle(),
                d.getFileName(),
                d.getFileSize(),
                d.getGenreTags() == null ? "" : d.getGenreTags(),
                Boolean.TRUE.equals(d.getIsReadonly()) || !"USER".equals(accessType),
                !Boolean.FALSE.equals(d.getEnabled()),
                boundToBook,
                d.getStatus(),
                d.getProgress(),
                d.getChunkCount(),
                d.getErrorMessage(),
                d.getCreatedAt(),
                d.getUpdatedAt(),
                Objects.requireNonNullElse(d.getGenreCodes(), "")
        );
    }
}
