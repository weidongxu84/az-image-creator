package io.weidongxu.webapp.imagecreator;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class ChatResponseParser {

    public ChatOutput parse(String text) {
        String normalized = safe(text).replace("\r\n", "\n");

        ChatOutput fallback = new ChatOutput();
        fallback.assistant_reply = normalized.isBlank() ? "Sorry, I could not parse the model output." : normalized;
        fallback.image_summary = "NONE";
        fallback.improvement_actions = List.of();
        fallback.best_prompt_candidate = "";

        if (normalized.isBlank()) {
            return fallback;
        }

        if (!normalized.contains("ASSISTANT_REPLY:") && !normalized.contains("IMAGE_SUMMARY:")) {
            return fallback;
        }

        ChatOutput out = new ChatOutput();
        out.assistant_reply = section(normalized, "ASSISTANT_REPLY:", "IMAGE_SUMMARY:");
        out.image_summary = section(normalized, "IMAGE_SUMMARY:", "IMPROVEMENT_ACTIONS:");
        out.improvement_actions = parseBullets(section(normalized, "IMPROVEMENT_ACTIONS:", "BEST_PROMPT_CANDIDATE:"));
        out.best_prompt_candidate = sectionFrom(normalized, "BEST_PROMPT_CANDIDATE:");

        normalize(out, fallback.assistant_reply);
        return out;
    }

    private void normalize(ChatOutput out, String fallbackReply) {
        if (out.assistant_reply == null || out.assistant_reply.isBlank()) {
            out.assistant_reply = fallbackReply;
        }
        if (out.image_summary == null || out.image_summary.isBlank()) {
            out.image_summary = "NONE";
        }
        if (out.improvement_actions == null) {
            out.improvement_actions = List.of();
        }
        if (out.best_prompt_candidate == null) {
            out.best_prompt_candidate = "";
        }
    }

    private List<String> parseBullets(String block) {
        if (block == null || block.isBlank()) {
            return List.of();
        }
        return block.lines()
                .map(String::trim)
                .filter(line -> line.startsWith("- "))
                .map(line -> line.substring(2).trim())
                .filter(line -> !line.isBlank())
                .collect(Collectors.toList());
    }

    private String section(String text, String start, String end) {
        int s = text.indexOf(start);
        if (s < 0) return "";
        s += start.length();
        int e = text.indexOf(end, s);
        if (e < 0) e = text.length();
        return text.substring(s, e).trim();
    }

    private String sectionFrom(String text, String start) {
        int s = text.indexOf(start);
        if (s < 0) return "";
        s += start.length();
        return text.substring(s).trim();
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
