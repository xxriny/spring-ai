package dev.springrag.service;

import java.util.List;

public interface Reranker {
    List<Double> scores(String query, List<String> passages);

    class RerankerUnavailableException extends RuntimeException {
        public RerankerUnavailableException(String message, Throwable cause) { super(message, cause); }
    }
}
