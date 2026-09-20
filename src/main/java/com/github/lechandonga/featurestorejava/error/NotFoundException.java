package com.github.lechandonga.featurestorejava.error;

/**
 * Thrown when a referenced feature set / version / point does not exist.
 */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
