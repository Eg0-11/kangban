package com.kangban.tianjinmcp;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

/**
 * 公共医疗目录记录。该模型不允许携带 userId、memberId、病历或用药字段。
 */
public record MedicalPublicRecord(
        @NotBlank String hospitalId,
        @NotBlank String hospitalName,
        String hospitalLevel,
        String department,
        String doctorName,
        String doctorTitle,
        String address,
        @NotBlank String sourceUrl,
        Instant updatedAt,
        @NotBlank String status
) {
    public MedicalPublicRecord {
        if (updatedAt == null) {
            updatedAt = Instant.now();
        }
        status = status == null || status.isBlank() ? "DEMO" : status.trim().toUpperCase();
    }
}
