package dev.oblivruin.preprocessor.core;

/**
 * Thrown when a file cannot be preprocessed, e.g. because of an unbalanced block,
 * an invalid condition or an unknown variable in strict mode.
 */
public class ProcessingException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String fileName;
    private final int lineNumber;

    public ProcessingException(String message) {
        this(message, null, -1);
    }

    public ProcessingException(String message, String fileName, int lineNumber) {
        super(message);
        this.fileName = fileName;
        this.lineNumber = lineNumber;
    }

    /** @return the file the exception refers to, or {@code null} */
    public String getFileName() {
        return fileName;
    }

    /** @return the 1-based line the exception refers to, or {@code -1} */
    public int getLineNumber() {
        return lineNumber;
    }

    @Override
    public String getMessage() {
        String message = super.getMessage();
        if (fileName != null && lineNumber > 0) {
            return message + " (" + fileName + ":" + lineNumber + ")";
        }
        if (fileName != null) {
            return message + " (" + fileName + ")";
        }
        return message;
    }
}
