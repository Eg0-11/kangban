package com.kangban.tianjinmcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class McpMedicalToolsTest {

    @Test
    void returnsExplicitDemoStatusAndSource() {
        MedicalInfoCatalog catalog = new MedicalInfoCatalog();
        catalog.importRecords(List.of(new MedicalPublicRecord(
                "demo-tj-002", "演示医院二", "DEMO", "儿科", null, null,
                "天津市演示地址", "https://example.invalid/tianjin-demo-2", Instant.now(), "DEMO")));

        String response = new McpMedicalTools(catalog, new ObjectMapper())
                .searchHospitals("演示医院二", null, null);

        assertThat(response).contains("\"dataStatus\":\"DEMO\"")
                .contains("天津公共医疗目录")
                .contains("演示医院二");
    }

    @Test
    void rejectsHospitalInfoWithoutIdentifier() {
        String response = new McpMedicalTools(new MedicalInfoCatalog(), new ObjectMapper())
                .getHospitalInfo("", "");
        assertThat(response).contains("INVALID_ARGUMENT");
    }

    @Test
    void marksEmptyCatalogWithoutInventingPublicRecords() {
        String response = new McpMedicalTools(new MedicalInfoCatalog(), new ObjectMapper())
                .searchHospitals("天津", null, null);
        assertThat(response).contains("\"dataStatus\":\"EMPTY\"")
                .contains("\"resultCount\":0");
    }
}
