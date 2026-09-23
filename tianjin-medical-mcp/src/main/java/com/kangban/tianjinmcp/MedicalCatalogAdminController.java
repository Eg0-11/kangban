package com.kangban.tianjinmcp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/catalog")
public class MedicalCatalogAdminController {

    private final MedicalInfoCatalog catalog;
    private final String adminToken;

    public MedicalCatalogAdminController(MedicalInfoCatalog catalog,
                                         @Value("${mcp.catalog.admin-token:}") String adminToken) {
        this.catalog = catalog;
        this.adminToken = adminToken;
    }

    @GetMapping
    public ResponseEntity<?> snapshot() {
        return ResponseEntity.ok(Map.of("items", catalog.snapshot(), "count", catalog.snapshot().size()));
    }

    @PostMapping("/import")
    public ResponseEntity<?> importRecords(@RequestHeader(value = "X-MCP-Admin-Token", required = false) String token,
                                           @RequestBody List<MedicalPublicRecord> records) {
        if (adminToken == null || adminToken.isBlank()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("message", "目录导入接口未配置管理令牌"));
        }
        if (token == null || !MessageDigest.isEqual(adminToken.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "无权导入公共医疗目录"));
        }
        int imported = catalog.importRecords(records);
        return ResponseEntity.ok(Map.of("imported", imported, "status", "IMPORTED"));
    }
}
