package io.weidongxu.webapp.imagecreator;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class ChatResponseMapper {

    public ChatOutput fromRawTextFallback(String text, boolean usedImage) {
        ChatOutput out = new ChatOutput();
        out.assistant_reply = safe(text).isBlank() ? "Sorry, I could not parse the model output." : safe(text);
        out.image_summary = "NONE";
        out.improvement_actions = List.of();
        out.best_prompt_candidate = "";
        return normalize(out, usedImage);
    }

    public ChatResponsePayload toPayload(ChatOutput output, boolean usedImage) {
        ChatOutput out = normalize(output, usedImage);
        return new ChatResponsePayload(
                out.assistant_reply,
                out.image_summary,
                out.improvement_actions,
                out.best_prompt_candidate,
                usedImage
        );
    }

    private ChatOutput normalize(ChatOutput output, boolean usedImage) {
        ChatOutput out = output == null ? new ChatOutput() : output;
        out.assistant_reply = safe(out.assistant_reply);
        if (out.assistant_reply.isBlank()) {
            out.assistant_reply = "Sorry, I could not parse the model output.";
        }

        if (!usedImage) {
            out.image_summary = "NONE";
            out.improvement_actions = List.of();
            out.best_prompt_candidate = "";
            return out;
        }

        out.image_summary = safe(out.image_summary);
        if (out.image_summary.isBlank()) {
            out.image_summary = "NONE";
        }

        if (out.improvement_actions == null) {
            out.improvement_actions = List.of();
        } else {
            out.improvement_actions = out.improvement_actions.stream()
                    .filter(s -> s != null && !s.isBlank())
                    .map(String::trim)
                    .collect(Collectors.toList());
        }

        out.best_prompt_candidate = safe(out.best_prompt_candidate);
        return out;
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
