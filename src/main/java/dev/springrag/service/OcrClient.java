package dev.springrag.service;

public interface OcrClient {
    String recognize(byte[] image);

    class OcrUnavailableException extends RuntimeException {
        public OcrUnavailableException(String message, Throwable cause) { super(message, cause); }
    }
}
