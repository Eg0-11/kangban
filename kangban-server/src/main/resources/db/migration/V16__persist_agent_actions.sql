ALTER TABLE chat_messages
    ADD COLUMN actions_json LONGTEXT DEFAULT NULL
        COMMENT 'Structured Agent action proposals for assistant messages'
        AFTER agent_tool_traces_json;
