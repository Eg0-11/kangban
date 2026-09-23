package com.kangban.tianjinmcp;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MedicalInfoCatalogTest {

    @Test
    void importsAndFiltersPublicRecordsWithoutPatientFields() {
        MedicalInfoCatalog catalog = new MedicalInfoCatalog();
        catalog.importRecords(List.of(new MedicalPublicRecord(
                "demo-tj-001", "演示天津医院", "DEMO", "心内科", "演示医生", "主任医师",
                "天津市演示地址", "https://example.invalid/tianjin-demo", Instant.parse("2026-01-01T00:00:00Z"), "DEMO")));

        assertThat(catalog.searchHospitals("天津", null, "心内科")).hasSize(1);
        assertThat(catalog.searchDoctors(null, "心内科", "演示天津医院", null)).hasSize(1);
        assertThat(catalog.snapshot().get(0).status()).isEqualTo("DEMO");
    }

    @Test
    void rejectsIncompleteImportedRecord() {
        MedicalInfoCatalog catalog = new MedicalInfoCatalog();
        assertThatThrownBy(() -> catalog.importRecords(List.of(new MedicalPublicRecord(
                "", "", null, null, null, null, null, "", Instant.now(), "DEMO"))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
