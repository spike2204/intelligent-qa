package com.example.qa.service.llm;

import com.example.qa.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * LLM Router - Manages LLM clients and directs queries
 * Restored functionality + Hierarchy Prediction
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LLMRouter {

    private final List<LLMClient> llmClients;
    private final AppProperties appProperties;

    // Cache map for faster lookup, initialized in constructor if needed,
    // but List lookup is fast enough for small N.

    /**
     * Get the primary configured LLM client
     */
    public LLMClient getPrimary() {
        String primaryType = appProperties.getLlm().getPrimary().getType();
        return getClient(primaryType);
    }

    /**
     * Get a specific LLM client by type (mock, azure, openai, dashscope)
     */
    public LLMClient getClient(String type) {
        if (type == null || type.isEmpty()) {
            return getPrimary();
        }

        // Strategy: Match by class name or some property?
        // Usually "mock" -> MockLLMClient, "azure" -> AzureOpenAILLMClient
        // Let's iterate and match loosely or add a getType() to LLMClient interface if
        // possible.
        // Since I can't easily change LLMClient interface without touching all impls,
        // I will use instanceof or simple name matching.

        return llmClients.stream()
                .filter(client -> isMatch(client, type))
                .findFirst()
                .orElseGet(() -> {
                    log.warn("LLM Client type '{}' not found, using primary", type);
                    return getPrimary(); // Fallback to primary to avoid null
                });
    }

    private boolean isMatch(LLMClient client, String type) {
        String className = client.getClass().getSimpleName().toLowerCase();
        String typeLower = type.toLowerCase();

        if (className.contains(typeLower))
            return true;
        // Specific mapping
        if (typeLower.equals("azure") && className.contains("azure"))
            return true;
        if (typeLower.equals("openai") && className.contains("openai") && !className.contains("azure"))
            return true;
        if (typeLower.equals("dashscope") && className.contains("dashscope"))
            return true;
        if (typeLower.equals("mock") && className.contains("mock"))
            return true;

        return false;
    }

    /**
     * Get fallback client for the current client
     */
    public LLMClient fallback(LLMClient currentClient) {
        String fallbackType = appProperties.getLlm().getFallback().getType();
        if (fallbackType == null || fallbackType.equals("none")) {
            return currentClient; // No fallback
        }

        LLMClient fallback = getClient(fallbackType);
        if (fallback == currentClient) {
            log.warn("Fallback client is same as current, cannot fallback");
            return currentClient;
        }
        return fallback;
    }

    /**
     * Predicts the target hierarchy for a given query.
     */
    public String predictHierarchy(String query, List<String> availableHierarchies) {
        if (availableHierarchies == null || availableHierarchies.isEmpty()) {
            return null;
        }

        String prompt = buildRouterPrompt(query, availableHierarchies);

        try {
            // Use Primary LLM for routing
            LLMClient client = getPrimary();
            if (!client.isAvailable()) {
                client = fallback(client);
            }

            LLMClient.ChatRequest request = LLMClient.ChatRequest.builder()
                    .messages(Collections.singletonList(
                            new LLMClient.Message("user", prompt)))
                    .temperature(0.0)
                    .maxTokens(50)
                    .build();

            String response = client.chat(request).trim();
            response = response.replace("\"", "").replace("'", "").trim();

            if ("NONE".equalsIgnoreCase(response)) {
                return null;
            }

            // Simple validation: check if response is contained in available hierarchies
            // allow fuzzy match?
            String finalResponse = response;
            return availableHierarchies.stream()
                    .filter(h -> h.contains(finalResponse) || finalResponse.contains(h))
                    .findFirst()
                    .orElse(null);

        } catch (Exception e) {
            log.warn("Router prediction failed: {}", e.getMessage());
            return null;
        }
    }

    private String buildRouterPrompt(String query, List<String> hierarchies) {
        StringBuilder sb = new StringBuilder();
        sb.append(
                "You are a query router. Given a User Query and a list of Document Hierarchies, predict which hierarchy best matches the query intent.\n");
        sb.append(
                "Return ONLY the exact string of the matching hierarchy (or the most specific part). If no specific hierarchy matches, return 'NONE'.\n\n");
        sb.append("Hierarchies:\n");
        for (int i = 0; i < Math.min(hierarchies.size(), 20); i++) {
            sb.append("- ").append(hierarchies.get(i)).append("\n");
        }
        sb.append("...\n\n");
        sb.append("User Query: ").append(query).append("\n");
        sb.append("Target Hierarchy:");
        return sb.toString();
    }
}
