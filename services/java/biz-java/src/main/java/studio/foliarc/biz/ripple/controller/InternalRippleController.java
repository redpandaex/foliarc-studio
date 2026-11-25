package studio.foliarc.biz.ripple.controller;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import studio.foliarc.biz.ripple.dto.RippleDtos.InternalAnalysisResultRequest;
import studio.foliarc.biz.ripple.dto.RippleDtos.InternalCandidateSearchRequest;
import studio.foliarc.biz.ripple.dto.RippleDtos.InternalCandidateSearchResponse;
import studio.foliarc.biz.ripple.dto.RippleDtos.InternalPatchBatchContext;
import studio.foliarc.biz.ripple.dto.RippleDtos.InternalPatchBatchResultRequest;
import studio.foliarc.biz.ripple.dto.RippleDtos.InternalRippleJobContext;
import studio.foliarc.biz.ripple.dto.RippleDtos.RippleJobResponse;
import studio.foliarc.biz.ripple.service.RippleEditService;

@RestController
@RequestMapping("/internal/v1/ripple/jobs/{jobId}")
public class InternalRippleController {

    private final RippleEditService rippleService;
    public InternalRippleController(RippleEditService rippleService) {
        this.rippleService = rippleService;
    }

    @GetMapping("/context")
    public InternalRippleJobContext getJobContext(@PathVariable UUID jobId) {
        return rippleService.getJobContext(jobId);
    }

    @PostMapping("/candidates")
    public InternalCandidateSearchResponse searchCandidates(
            @PathVariable UUID jobId, @RequestBody InternalCandidateSearchRequest request
    ) {
        return rippleService.searchCandidates(jobId, request);
    }

    @PostMapping("/analysis-result")
    public RippleJobResponse saveAnalysisResult(
            @PathVariable UUID jobId, @RequestBody InternalAnalysisResultRequest request
    ) {
        return rippleService.saveAnalysisResult(jobId, request);
    }

    @GetMapping("/batches/{batchNo}/targets")
    public InternalPatchBatchContext getPatchBatchTargets(@PathVariable UUID jobId, @PathVariable int batchNo) {
        return rippleService.getPatchBatchTargets(jobId, batchNo);
    }

    @PostMapping("/batches/{batchNo}/patches-result")
    public RippleJobResponse savePatchBatchResult(
            @PathVariable UUID jobId, @PathVariable int batchNo, @RequestBody InternalPatchBatchResultRequest request
    ) {
        return rippleService.savePatchBatchResult(jobId, batchNo, request);
    }
}
