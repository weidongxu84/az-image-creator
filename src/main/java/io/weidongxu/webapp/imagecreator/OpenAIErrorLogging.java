package io.weidongxu.webapp.imagecreator;

import com.openai.core.http.Headers;
import com.openai.errors.OpenAIServiceException;
import org.slf4j.Logger;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

final class OpenAIErrorLogging {

    private static final Set<String> REDACTED_HEADERS = Set.of(
            "authorization", "proxy-authorization", "api-key", "x-api-key",
            "x-amz-security-token", "cookie", "set-cookie");

    private OpenAIErrorLogging() {
    }

    static void logResponse(Logger log, String context, OpenAIServiceException error) {
        Headers headers = error.headers();
        Map<String, List<String>> sanitizedHeaders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String name : headers.names()) {
            sanitizedHeaders.put(name, REDACTED_HEADERS.contains(name.toLowerCase(Locale.ROOT))
                    ? List.of("[REDACTED]") : headers.values(name));
        }
        log.warn("{}: OpenAI error response HTTP {} headers={} body={}",
                context, error.statusCode(), sanitizedHeaders, error.body());
    }
}
