package studio.foliarc.biz.style.service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import studio.foliarc.biz.common.BizException;
import studio.foliarc.biz.novel.service.BookService;
import studio.foliarc.biz.style.dto.StyleAnchorDtos.StyleAnchorResponse;
import studio.foliarc.biz.style.dto.StyleAnchorDtos.UpsertStyleAnchorRequest;
import studio.foliarc.biz.style.entity.StyleAnchorEntity;
import studio.foliarc.biz.style.mapper.StyleAnchorMapper;
import studio.foliarc.biz.vip.service.VipEntitlementService;

@Service
public class StyleAnchorService {

    private static final int MAX_ENABLED_EXCERPT_CHARS = 900;
    private static final int DEFAULT_MAX_STYLE_ANCHORS = 3;

    private final BookService bookService;
    private final StyleAnchorMapper anchorMapper;
    private VipEntitlementService vipEntitlementService;

    public StyleAnchorService(BookService bookService, StyleAnchorMapper anchorMapper) {
        this.bookService = bookService;
        this.anchorMapper = anchorMapper;
    }

    @Autowired(required = false)
    public void setVipEntitlementService(VipEntitlementService vipEntitlementService) {
        this.vipEntitlementService = vipEntitlementService;
    }

    public List<StyleAnchorResponse> listAnchors(UUID ownerId, UUID bookId) {
        bookService.requireOwnedBook(ownerId, bookId);
        return anchorMapper.selectList(orderedAnchors(bookId)).stream()
                .map(StyleAnchorService::toResponse)
                .toList();
    }

    @Transactional
    public StyleAnchorResponse upsertAnchor(UUID ownerId, UUID bookId, UpsertStyleAnchorRequest req) {
        bookService.requireOwnedBook(ownerId, bookId);
        // Check both limits under the same book lock as the write.
        bookService.lockStructure(bookId);
        boolean creating = req.id() == null;
        StyleAnchorEntity anchor = creating ? new StyleAnchorEntity() : requireAnchor(ownerId, bookId, req.id());
        boolean enabled = req.enabled() == null ? creating || Boolean.TRUE.equals(anchor.getEnabled()) : req.enabled();
        String excerpt = Objects.requireNonNullElse(req.excerptText(), "").trim();
        if (excerpt.isBlank()) {
            throw BizException.badRequest("STYLE_ANCHOR_EMPTY", "文风锚点摘录不能为空");
        }
        if (enabled) {
            List<StyleAnchorEntity> enabledAnchors = enabledAnchors(bookId);
            if (creating || !Boolean.TRUE.equals(anchor.getEnabled())) {
                enforceQuota(ownerId, enabledAnchors.size() + 1);
            }
            long totalChars = excerpt.length() + enabledAnchors.stream()
                    .filter(other -> !other.getId().equals(req.id()))
                    .mapToLong(other -> other.getExcerptText().trim().length())
                    .sum();
            if (totalChars > MAX_ENABLED_EXCERPT_CHARS) {
                throw BizException.badRequest("STYLE_ANCHOR_TOO_LONG", "已启用的文风锚点摘录总长不能超过 900 个字符");
            }
        }

        if (creating) {
            anchor.setId(UUID.randomUUID());
            anchor.setBookId(bookId);
            anchor.setOwnerId(ownerId);
            anchor.setSortOrder(1);
        }
        if (creating || req.title() != null) anchor.setTitle(Objects.requireNonNullElse(req.title(), "").trim());
        anchor.setExcerptText(excerpt);
        if (req.sortOrder() != null) anchor.setSortOrder(req.sortOrder());
        anchor.setEnabled(enabled);
        if (creating) anchorMapper.insert(anchor);
        else anchorMapper.updateById(anchor);
        return toResponse(anchor);
    }

    @Transactional
    public void deleteAnchor(UUID ownerId, UUID bookId, UUID anchorId) {
        bookService.requireOwnedBook(ownerId, bookId);
        bookService.lockStructure(bookId);
        requireAnchor(ownerId, bookId, anchorId);
        anchorMapper.deleteById(anchorId);
    }

    public String buildStyleAnchorsText(UUID bookId) {
        StringBuilder text = new StringBuilder();
        int remaining = MAX_ENABLED_EXCERPT_CHARS;
        for (StyleAnchorEntity anchor : enabledAnchors(bookId)) {
            String excerpt = anchor.getExcerptText().trim();
            if (excerpt.isEmpty()) continue;
            int length = Math.min(excerpt.length(), remaining);
            if (length < excerpt.length() && Character.isHighSurrogate(excerpt.charAt(length - 1))) length--;
            if (length == 0) break;
            if (!text.isEmpty()) text.append("\n\n");
            text.append(excerpt, 0, length);
            remaining -= length;
            if (length < excerpt.length() || remaining == 0) break;
        }
        return text.toString();
    }

    private void enforceQuota(UUID ownerId, int requested) {
        int limit = vipEntitlementService == null ? DEFAULT_MAX_STYLE_ANCHORS
                : vipEntitlementService.resolveForUser(ownerId).entitlements().maxStyleAnchors();
        if (requested > limit) {
            throw BizException.vipQuotaExceeded("当前套餐最多启用 " + limit + " 个文风锚点，升级会员可提高上限",
                    Map.of("feature", "STYLE_ANCHORS", "currentLimit", String.valueOf(limit),
                            "requested", String.valueOf(requested)));
        }
    }

    private StyleAnchorEntity requireAnchor(UUID ownerId, UUID bookId, UUID id) {
        StyleAnchorEntity anchor = anchorMapper.selectById(id);
        if (anchor == null || !bookId.equals(anchor.getBookId()) || !ownerId.equals(anchor.getOwnerId())) {
            throw BizException.notFound("STYLE_ANCHOR_NOT_FOUND", "文风锚点不存在");
        }
        return anchor;
    }

    private List<StyleAnchorEntity> enabledAnchors(UUID bookId) {
        return anchorMapper.selectList(orderedAnchors(bookId).eq(StyleAnchorEntity::getEnabled, true));
    }

    private static LambdaQueryWrapper<StyleAnchorEntity> orderedAnchors(UUID bookId) {
        return new LambdaQueryWrapper<StyleAnchorEntity>()
                .eq(StyleAnchorEntity::getBookId, bookId)
                .orderByAsc(StyleAnchorEntity::getSortOrder, StyleAnchorEntity::getCreatedAt);
    }

    private static StyleAnchorResponse toResponse(StyleAnchorEntity anchor) {
        return new StyleAnchorResponse(anchor.getId(), anchor.getBookId(), anchor.getTitle(), anchor.getExcerptText(),
                anchor.getSortOrder(), Boolean.TRUE.equals(anchor.getEnabled()), anchor.getCreatedAt(), anchor.getUpdatedAt());
    }
}
