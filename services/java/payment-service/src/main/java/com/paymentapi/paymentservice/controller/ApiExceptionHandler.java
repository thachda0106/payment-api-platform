package com.paymentapi.paymentservice.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * Maps request-validation failures to explicit client-error responses
 * (422 for body constraint violations, 400 for missing required headers)
 * instead of leaking a generic 500.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail onValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
            .map(f -> f.getField() + ": " + f.getDefaultMessage())
            .collect(Collectors.joining("; "));
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, detail);
        pd.setTitle("Validation failed");
        return pd;
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ProblemDetail onMissingHeader(MissingRequestHeaderException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
            "Missing required header: " + ex.getHeaderName());
        pd.setTitle("Bad request");
        return pd;
    }
}
