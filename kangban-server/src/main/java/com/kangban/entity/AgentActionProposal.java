package com.kangban.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_action_proposals")
public class AgentActionProposal {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String proposalId;
    private Long actorUserId;
    private Long subjectUserId;
    private Long memberId;
    private Long sessionId;
    private String actionType;
    private String payloadJson;
    private String payloadHash;
    private String status;
    private LocalDateTime expiresAt;
    private LocalDateTime confirmedAt;
    private LocalDateTime executedAt;
    private String idempotencyKey;
    private String resultReference;
    private String errorCode;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
