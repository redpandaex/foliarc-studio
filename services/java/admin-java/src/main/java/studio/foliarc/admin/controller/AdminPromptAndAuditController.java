package studio.foliarc.admin.controller;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import studio.foliarc.admin.service.AdminAuditService;
import studio.foliarc.biz.audit.entity.AdminAuditLogEntity;
import studio.foliarc.biz.audit.mapper.AdminAuditLogMapper;
import studio.foliarc.biz.auth.AuthenticatedUser;
import studio.foliarc.biz.common.BizException;
import studio.foliarc.biz.lore.entity.PromptTemplateEntity;
import studio.foliarc.biz.lore.mapper.PromptTemplateMapper;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminPromptAndAuditController {

    private final PromptTemplateMapper promptMapper;
    private final AdminAuditLogMapper auditMapper;
    private final AdminAuditService auditService;

    public AdminPromptAndAuditController(PromptTemplateMapper promptMapper, AdminAuditLogMapper auditMapper,
                                         AdminAuditService auditService) {
        this.promptMapper = promptMapper;
        this.auditMapper = auditMapper;
        this.auditService = auditService;
    }

    @GetMapping("/prompts")
    public List<PromptResponse> listPrompts() {
        return promptMapper.selectList(Wrappers.<PromptTemplateEntity>lambdaQuery()
                .orderByAsc(PromptTemplateEntity::getName)
                .orderByDesc(PromptTemplateEntity::getVersion)).stream().map(PromptResponse::new).toList();
    }

    @PostMapping("/prompts")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public PromptResponse publish(
            @AuthenticationPrincipal AuthenticatedUser principal, @Valid @RequestBody PublishPromptRequest request
    ) {
        String name = request.name().strip();
        promptMapper.lockPromptName(name);
        var latest = promptMapper.selectOne(Wrappers.<PromptTemplateEntity>lambdaQuery()
                .eq(PromptTemplateEntity::getName, name)
                .orderByDesc(PromptTemplateEntity::getVersion)
                .last("LIMIT 1"));

        PromptTemplateEntity prompt = new PromptTemplateEntity();
        prompt.setId(UUID.randomUUID());
        prompt.setName(name);
        prompt.setVersion(latest == null ? 1 : latest.getVersion() + 1);
        prompt.setStage(request.stage() == null || request.stage().isBlank()
                ? "DRAFT" : request.stage().strip().toUpperCase(Locale.ROOT));
        prompt.setDescription(request.description() == null ? "" : request.description().strip());
        prompt.setTemplateBody(request.templateBody());
        promptMapper.insert(prompt);
        if ("ACTIVE".equals(prompt.getStage())) {
            archiveOtherVersions(prompt);
        }
        auditService.record(principal, "PROMPT_PUBLISH", "PROMPT", prompt.getId(), null, prompt);
        return new PromptResponse(prompt);
    }

    @PostMapping("/prompts/{promptId}/activate")
    @Transactional
    public PromptResponse activate(
            @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID promptId
    ) {
        promptMapper.lockPromptName(requirePrompt(promptId).getName());
        PromptTemplateEntity prompt = requirePrompt(promptId);
        var before = PromptStage.from(prompt);
        archiveOtherVersions(prompt);
        prompt.setStage("ACTIVE");
        promptMapper.updateById(prompt);
        auditService.record(principal, "PROMPT_ACTIVATE", "PROMPT", promptId, before, PromptStage.from(prompt));
        return new PromptResponse(prompt);
    }

    @GetMapping("/audit")
    public List<AdminAuditLogEntity> listAudit(@RequestParam(defaultValue = "100") int limit) {
        return auditMapper.selectList(Wrappers.<AdminAuditLogEntity>lambdaQuery()
                .orderByDesc(AdminAuditLogEntity::getCreatedAt, AdminAuditLogEntity::getId)
                .last(AdminQuery.limit(limit)));
    }

    private PromptTemplateEntity requirePrompt(UUID promptId) {
        PromptTemplateEntity prompt = promptMapper.selectById(promptId);
        if (prompt == null) {
            throw BizException.notFound("PROMPT_NOT_FOUND", "Prompt 模板不存在");
        }
        return prompt;
    }

    private void archiveOtherVersions(PromptTemplateEntity prompt) {
        promptMapper.update(null, Wrappers.<PromptTemplateEntity>lambdaUpdate()
                .eq(PromptTemplateEntity::getName, prompt.getName())
                .ne(PromptTemplateEntity::getId, prompt.getId())
                .ne(PromptTemplateEntity::getStage, "ARCHIVED")
                .and(prior -> prior.lt(PromptTemplateEntity::getVersion, prompt.getVersion())
                        .or().eq(PromptTemplateEntity::getStage, "ACTIVE"))
                .set(PromptTemplateEntity::getStage, "ARCHIVED"));
    }

    public record PublishPromptRequest(@NotBlank @Size(max = 100) String name, @Size(max = 24) String stage,
                                       @Size(max = 255) String description, @NotBlank String templateBody) {}

    public record PromptResponse(@JsonUnwrapped PromptTemplateEntity template) {
        @JsonProperty
        public String status() {
            String stage = template.getStage();
            return "ACTIVE".equals(stage) || "ARCHIVED".equals(stage) ? stage : "DRAFT";
        }
    }

    private record PromptStage(UUID id, String name, int version, String stage) {
        static PromptStage from(PromptTemplateEntity prompt) {
            return new PromptStage(prompt.getId(), prompt.getName(), prompt.getVersion(), prompt.getStage());
        }
    }
}
