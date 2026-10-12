package io.weidongxu.webapp.imagecreator;

class SavedPromptNameConflictException extends RuntimeException {
    SavedPromptNameConflictException(Throwable cause) {
        super("That name already exists. Choose another name.", cause);
    }
}
