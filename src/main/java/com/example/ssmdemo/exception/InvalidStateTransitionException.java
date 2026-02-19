package com.example.ssmdemo.exception;

public class InvalidStateTransitionException extends RuntimeException {

    public InvalidStateTransitionException(String message) {
        super(message);
    }
}
