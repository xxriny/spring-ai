package dev.springrag.chat.service;

/** 방문자 토큰이 없거나 만료되어 요청을 인증할 수 없는 경우다. */
public class VisitorUnauthorizedException extends RuntimeException {}
