package studio.foliarc.biz.memory.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import studio.foliarc.biz.auth.AuthenticatedUser;
import studio.foliarc.biz.memory.dto.MemoryProposalDtos.MemoryProposalResponse;
import studio.foliarc.biz.memory.dto.MemoryProposalDtos.ProposalDecisionRequest;
import studio.foliarc.biz.memory.service.MemoryProposalService;

@RestController
@RequestMapping("/api/v1/books/{bookId}/memory/proposals")
public class MemoryProposalController {

    private final MemoryProposalService proposalService;

    public MemoryProposalController(MemoryProposalService proposalService) {
        this.proposalService = proposalService;
    }

    @GetMapping
    public List<MemoryProposalResponse> list(@AuthenticationPrincipal AuthenticatedUser actor,
                                            @PathVariable UUID bookId,
                                            @RequestParam(defaultValue = "PENDING") String status) {
        return proposalService.list(actor.userId(), bookId, status);
    }

    @PostMapping("/{id}/decision")
    public MemoryProposalResponse decide(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID bookId,
                                         @PathVariable UUID id, @Valid @RequestBody ProposalDecisionRequest request) {
        return proposalService.decide(actor.userId(), bookId, id, request);
    }
}
