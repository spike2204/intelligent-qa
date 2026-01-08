package com.example.qa.config;

import com.example.qa.service.llm.AzureOpenAILLMClient;
import com.example.qa.service.llm.AzureResponsesLLMClient;
import com.example.qa.service.llm.LLMClient;
import com.example.qa.service.llm.MockLLMClient;
import lombok.extern.slf4j.Slf4j;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * LLM Configuration to support dual models
 */
@Slf4j
@Configuration
public class LLMConfiguration {

    @Bean
    @Primary
    public LLMClient primaryLLMClient(AppProperties appProperties) {
        log.info("Configuring Primary LLM Client: {}", appProperties.getLlm().getPrimary().getModel());
        return createClient(appProperties.getLlm().getPrimary(), appProperties);
    }

    @Bean
    public LLMClient secondaryLLMClient(AppProperties appProperties) {
        log.info("Configuring Secondary LLM Client: {}", appProperties.getLlm().getFallback().getModel());
        return createClient(appProperties.getLlm().getFallback(), appProperties);
    }

    private LLMClient createClient(AppProperties.LlmConfig.ModelConfig config, AppProperties appProperties) {
        String apiType = config.getApiType();
        String type = config.getType();

        if ("mock".equalsIgnoreCase(type)) {
            return new MockLLMClient();
        }

        if ("responses".equalsIgnoreCase(apiType)) {
            return new AzureResponsesLLMClient(config);
        } else {
            // Default to Chat Completions API (AzureOpenAILLMClient)
            // Assuming type is 'azure' or 'openai' which this client handles
            return new AzureOpenAILLMClient(config);
        }
    }
}
