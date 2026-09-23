package com.kangban.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ConfirmActionProposalRequest {

    @NotBlank(message = "草案摘要不能为空")
    private String payloadHash;
}
