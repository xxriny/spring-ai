package dev.springrag.dto;

public record CrawlRequest(String url, String category, Integer maxPages) {}
