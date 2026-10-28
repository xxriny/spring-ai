package dev.springrag.chat.service;

/** 방문자 또는 전체 일일 요청 한도를 넘은 경우다. */
public class VisitorRateLimitException extends RuntimeException {}
