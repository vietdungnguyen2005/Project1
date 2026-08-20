package dev.vcore.shared.web;

public class ApiConflictException extends RuntimeException {

    public ApiConflictException(String message) {
        super(message);
    }
}
