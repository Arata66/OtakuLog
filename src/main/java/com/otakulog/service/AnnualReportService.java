package com.otakulog.service;

import com.otakulog.dto.AnnualReportDTO;

public interface AnnualReportService {
    AnnualReportDTO getAnnualReport(int year);
    AnnualReportDTO getLatestAnnualReport();
}
