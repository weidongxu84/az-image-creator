package io.weidongxu.webapp.imagecreator;

public class OpenAIException extends RuntimeException {

    private final int statusCode;

    public OpenAIException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
