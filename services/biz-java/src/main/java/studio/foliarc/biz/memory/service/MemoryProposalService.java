package studio.foliarc.biz.memory.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import studio.foliarc.biz.common.BizException;
import studio.foliarc.biz.lore.entity.CharacterEntity;
import studio.foliarc.biz.lore.entity.ForeshadowingEntity;
import studio.foliarc.biz.lore.mapper.CharacterMapper;
import studio.foliarc.biz.lore.mapper.ForeshadowingMapper;
import studio.foliarc.biz.memory.dto.MemoryProposalDtos.MemoryProposalResponse;
import studio.foliarc.biz.memory.dto.MemoryProposalDtos.ProposalDecisionRequest;
import studio.foliarc.biz.memory.entity.MemoryProposalEntity;
import studio.foliarc.biz.memory.mapper.MemoryProposalMapper;
import studio.foliarc.biz.novel.entity.ChapterSectionEntity;
import studio.foliarc.biz.novel.mapper.ChapterSectionMapper;
import studio.foliarc.biz.novel.service.BookService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class MemoryProposalService {

    public enum Type {
        CHARACTER_DEATH, FORESHADOWING_RESOLVE, CORE_IDENTITY_SHIFT
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final BookService bookService;
    private final MemoryProposalMapper proposalMapper;
    private final CharacterMapper characterMapper;
    private final ForeshadowingMapper foreshadowingMapper;
    private ChapterSectionMapper chapterSectionMapper;

    public MemoryProposalService(BookService bookService, MemoryProposalMapper proposalMapper,
                                 CharacterMapper characterMapper, ForeshadowingMapper foreshadowingMapper) {
        this.bookService = bookService;
        this.proposalMapper = proposalMapper;
        this.characterMapper = characterMapper;
        this.foreshadowingMapper = foreshadowingMapper;
    }

    @Autowired(required = false)
    public void setChapterSectionMapper(ChapterSectionMapper chapterSectionMapper) {
        this.chapterSectionMapper = chapterSectionMapper;
    }

    public List<MemoryProposalResponse> list(UUID ownerId, UUID bookId, String status) {
        bookService.requireOwnedBook(ownerId, bookId);
        String filter = status == null ? "" : status.strip().toUpperCase(Locale.ROOT);
        return proposalMapper.selectList(new LambdaQueryWrapper<MemoryProposalEntity>()
                        .eq(MemoryProposalEntity::getBookId, bookId)
                        .eq(MemoryProposalEntity::getOwnerId, ownerId)
                        .eq(!filter.isEmpty(), MemoryProposalEntity::getStatus, filter)
                        .orderByDesc(MemoryProposalEntity::getCreatedAt))
                .stream().map(MemoryProposalService::toResponse).toList();
    }

    // Called within the book-locked extraction transaction.
    public void propose(UUID ownerId, UUID bookId, UUID sectionId, Type type, UUID targetId,
                        String targetName, String summary, Map<String, ?> diff) {
        var query = new LambdaQueryWrapper<MemoryProposalEntity>()
                .eq(MemoryProposalEntity::getBookId, bookId)
                .eq(MemoryProposalEntity::getOwnerId, ownerId)
                .eq(MemoryProposalEntity::getProposalType, type.name())
                .eq(MemoryProposalEntity::getStatus, "PENDING")
                .eq(sectionId != null, MemoryProposalEntity::getSectionId, sectionId)
                .isNull(sectionId == null, MemoryProposalEntity::getSectionId)
                .eq(targetId != null, MemoryProposalEntity::getTargetEntityId, targetId)
                .eq(targetId == null, MemoryProposalEntity::getTargetName, targetName)
                .last("LIMIT 1");
        MemoryProposalEntity row = proposalMapper.selectOne(query);
        if (row != null) {
            row.setSummary(summary);
            row.setDiffJson(JSON.writeValueAsString(diff));
            proposalMapper.updateById(row);
            return;
        }
        row = new MemoryProposalEntity();
        row.setId(UUID.randomUUID());
        row.setBookId(bookId);
        row.setOwnerId(ownerId);
        row.setSectionId(sectionId);
        row.setProposalType(type.name());
        row.setTargetEntityId(targetId);
        row.setTargetName(targetName);
        row.setSummary(summary);
        row.setDiffJson(JSON.writeValueAsString(diff));
        row.setStatus("PENDING");
        proposalMapper.insert(row);
    }

    void clearPendingForSection(UUID bookId, UUID sectionId) {
        proposalMapper.delete(new LambdaQueryWrapper<MemoryProposalEntity>()
                .eq(MemoryProposalEntity::getBookId, bookId)
                .eq(MemoryProposalEntity::getSectionId, sectionId)
                .eq(MemoryProposalEntity::getStatus, "PENDING"));
    }

    @Transactional
    public MemoryProposalResponse decide(UUID ownerId, UUID bookId, UUID proposalId, ProposalDecisionRequest request) {
        bookService.requireOwnedBook(ownerId, bookId);
        bookService.lockStructure(bookId);
        String decision = request.decision() == null ? "" : request.decision().strip().toUpperCase(Locale.ROOT);
        String outcome = switch (decision) {
            case "ACCEPT" -> "ACCEPTED";
            case "REJECT" -> "REJECTED";
            default -> throw BizException.badRequest("INVALID_PROPOSAL_DECISION", "仅支持 ACCEPT 或 REJECT");
        };
        MemoryProposalEntity row = proposalMapper.selectById(proposalId);
        if (row == null || !bookId.equals(row.getBookId()) || !ownerId.equals(row.getOwnerId())) {
            throw BizException.notFound("MEMORY_PROPOSAL_NOT_FOUND", "记忆变更提案不存在");
        }
        if (outcome.equals(row.getStatus())) return toResponse(row);
        if (!"PENDING".equals(row.getStatus())) {
            throw BizException.conflict("MEMORY_PROPOSAL_ALREADY_DECIDED", "记忆变更提案已经处理");
        }
        if ("ACCEPTED".equals(outcome)) apply(row);
        row.setStatus(outcome);
        row.setResolvedAt(OffsetDateTime.now());
        proposalMapper.updateById(row);
        return toResponse(row);
    }

    private void apply(MemoryProposalEntity row) {
        JsonNode diff = JSON.readTree(row.getDiffJson());
        switch (Type.valueOf(row.getProposalType())) {
            case CHARACTER_DEATH -> applyCharacterChange(row, diff, true);
            case CORE_IDENTITY_SHIFT -> applyCharacterChange(row, diff, false);
            case FORESHADOWING_RESOLVE -> applyForeshadowingResolution(row, diff);
        }
    }

    private void applyCharacterChange(MemoryProposalEntity proposal, JsonNode diff, boolean death) {
        UUID bookId = proposal.getBookId();
        UUID targetId = proposal.getTargetEntityId();
        CharacterEntity character = targetId == null
                ? characterMapper.selectOne(new LambdaQueryWrapper<CharacterEntity>()
                        .eq(CharacterEntity::getBookId, bookId).eq(CharacterEntity::getName, proposal.getTargetName()))
                : characterMapper.selectById(targetId);
        boolean create = character == null && targetId == null && death;
        if (create) {
            character = new CharacterEntity();
            character.setId(UUID.randomUUID());
            character.setBookId(bookId);
            character.setName(proposal.getTargetName());
            proposal.setTargetEntityId(character.getId());
        }
        if (character == null || !bookId.equals(character.getBookId())) {
            throw BizException.notFound("CHARACTER_NOT_FOUND", "人物设定不存在");
        }
        if (diff.hasNonNull("statusNotes")) character.setStatusNotes(diff.get("statusNotes").asString());
        if (death) {
            character.setLifecycleStatus("DEAD");
        } else {
            if (diff.hasNonNull("roleType")) character.setRoleType(diff.get("roleType").asString());
            if (diff.hasNonNull("inventory")) character.setInventory(diff.get("inventory").asString());
        }
        if (create) characterMapper.insert(character);
        else characterMapper.updateById(character);
    }

    private void applyForeshadowingResolution(MemoryProposalEntity proposal, JsonNode diff) {
        ForeshadowingEntity clue = foreshadowingMapper.selectById(proposal.getTargetEntityId());
        if (clue == null || !proposal.getBookId().equals(clue.getBookId())) {
            throw BizException.notFound("FORESHADOWING_NOT_FOUND", "伏笔记录不存在");
        }
        UUID sectionId = proposal.getSectionId();
        Integer chapterNo = diff.hasNonNull("resolvedChapterNo") ? diff.get("resolvedChapterNo").asInt() : null;
        if (sectionId != null && chapterSectionMapper != null) {
            ChapterSectionEntity section = chapterSectionMapper.selectById(sectionId);
            if (section == null || !proposal.getBookId().equals(section.getBookId())) {
                throw BizException.notFound("SECTION_NOT_FOUND", "小节不存在");
            }
            chapterNo = section.getChapterNo();
        }
        clue.setStatus("RESOLVED");
        clue.setResolvedSectionId(sectionId);
        if (chapterNo != null) clue.setResolvedChapterNo(chapterNo);
        foreshadowingMapper.updateById(clue);
    }

    private static MemoryProposalResponse toResponse(MemoryProposalEntity row) {
        return new MemoryProposalResponse(row.getId(), row.getBookId(), row.getSectionId(), row.getProposalType(),
                row.getTargetEntityId(), row.getTargetName(), row.getSummary(), row.getDiffJson(), row.getStatus(),
                row.getCreatedAt(), row.getResolvedAt());
    }
}
