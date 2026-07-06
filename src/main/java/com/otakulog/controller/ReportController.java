package com.otakulog.controller;

import com.otakulog.common.ApiResponse;
import com.otakulog.dto.AnnualReportDTO;
import com.otakulog.service.AnnualReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "年度报告", description = "年度追番统计报告")
@RestController
@RequestMapping("/api/report")
public class ReportController {

    private final AnnualReportService annualReportService;

    public ReportController(AnnualReportService annualReportService) {
        this.annualReportService = annualReportService;
    }

    @Operation(summary = "获取指定年份的年度报告")
    @GetMapping("/annual/{year}")
    public ResponseEntity<ApiResponse<AnnualReportDTO>> getAnnualReport(@PathVariable int year) {
        AnnualReportDTO report = annualReportService.getAnnualReport(year);
        return ResponseEntity.ok(ApiResponse.success(report));
    }

    @Operation(summary = "获取最新年度报告（当前年份）")
    @GetMapping("/annual/latest")
    public ResponseEntity<ApiResponse<AnnualReportDTO>> getLatestAnnualReport() {
        AnnualReportDTO report = annualReportService.getLatestAnnualReport();
        return ResponseEntity.ok(ApiResponse.success(report));
    }
}
