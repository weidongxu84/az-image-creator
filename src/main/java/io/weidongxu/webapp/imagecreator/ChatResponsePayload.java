package io.weidongxu.webapp.imagecreator;

import java.util.List;

public record ChatResponsePayload(
        String assistantReply,
        String imageSummary,
        List<String> improvementActions,
        String bestPromptCandidate,
        boolean usedImage) {
}
