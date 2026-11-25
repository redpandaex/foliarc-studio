package studio.foliarc.biz.ripple.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import studio.foliarc.biz.auth.AuthenticatedUser;
import studio.foliarc.biz.ripple.dto.RippleDtos.ApplyPatchesRequest;
import studio.foliarc.biz.ripple.dto.RippleDtos.ApplyRipplePatchesResponse;
import studio.foliarc.biz.ripple.dto.RippleDtos.GeneratePatchesRequest;
import studio.foliarc.biz.ripple.dto.RippleDtos.RippleJobDetailResponse;
import studio.foliarc.biz.ripple.dto.RippleDtos.RippleJobResponse;
import studio.foliarc.biz.ripple.dto.RippleDtos.StartRippleAnalyzeRequest;
import studio.foliarc.biz.ripple.service.RippleEditService;

@RestController
@RequestMapping("/api/v1/books/{bookId}")
public class RippleController {

    private final RippleEditService rippleService;

    public RippleController(RippleEditService rippleService) {
        this.rippleService = rippleService;
    }

    @PostMapping("/sections/{sectionId}/ripple-analyze")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RippleJobResponse startAnalyze(@AuthenticationPrincipal AuthenticatedUser actor,
                                          @PathVariable UUID bookId, @PathVariable UUID sectionId,
                                          @RequestBody StartRippleAnalyzeRequest request) {
        return rippleService.startAnalyze(actor.userId(), bookId, sectionId, request);
    }

    @GetMapping("/ripple-jobs")
    public List<RippleJobResponse> listJobs(@AuthenticationPrincipal AuthenticatedUser actor,
                                            @PathVariable UUID bookId) {
        return rippleService.listJobs(actor.userId(), bookId);
    }

    @GetMapping("/ripple-jobs/{jobId}")
    public RippleJobDetailResponse getJobDetail(@AuthenticationPrincipal AuthenticatedUser actor,
                                                 @PathVariable UUID bookId, @PathVariable UUID jobId) {
        return rippleService.getJobDetail(actor.userId(), bookId, jobId);
    }

    @PostMapping("/ripple-jobs/{jobId}/generate-patches")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RippleJobDetailResponse generatePatches(@AuthenticationPrincipal AuthenticatedUser actor,
                                                    @PathVariable UUID bookId, @PathVariable UUID jobId,
                                                    @RequestBody GeneratePatchesRequest request) {
        return rippleService.generatePatches(actor.userId(), bookId, jobId, request);
    }

    @PostMapping("/ripple-jobs/{jobId}/apply")
    public ApplyRipplePatchesResponse applyPatches(@AuthenticationPrincipal AuthenticatedUser actor,
                                                  @PathVariable UUID bookId, @PathVariable UUID jobId,
                                                  @RequestBody ApplyPatchesRequest request) {
        return rippleService.applyPatches(actor.userId(), bookId, jobId, request);
    }

    @PostMapping("/ripple-jobs/{jobId}/cancel")
    public RippleJobResponse cancelJob(@AuthenticationPrincipal AuthenticatedUser actor,
                                       @PathVariable UUID bookId, @PathVariable UUID jobId) {
        return rippleService.cancelJob(actor.userId(), bookId, jobId);
    }
}
