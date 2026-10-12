package io.weidongxu.webapp.imagecreator;

class SavedPromptRenameException extends RuntimeException {
    SavedPromptRenameException(Throwable cause) {
        super("Rename could not finish reliably. Both names may be present; refresh before retrying.", cause);
    }
}
