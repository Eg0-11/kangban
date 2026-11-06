package com.kangban.agent.internal;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 内部回调默认关闭；凭据不参与自动生成的 toString。 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "app.agent.internal")
public class InternalAgentProperties {
    private boolean enabled = false;
    private String serviceToken = "";
    private long maxRequestMilliseconds = 170_000;
    private String pythonBaseUrl = "http://127.0.0.1:8093";
    private long connectTimeoutMilliseconds = 3_000;
}
