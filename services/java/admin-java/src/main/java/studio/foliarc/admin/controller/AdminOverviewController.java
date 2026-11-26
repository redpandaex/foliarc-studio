package studio.foliarc.admin.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import studio.foliarc.admin.service.AdminReportingService;
import studio.foliarc.admin.service.AdminReportingService.Overview;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminOverviewController {

    private final AdminReportingService reportingService;

    public AdminOverviewController(AdminReportingService reportingService) {
        this.reportingService = reportingService;
    }

    @GetMapping("/overview")
    public Overview overview() {
        return reportingService.overview();
    }
}
