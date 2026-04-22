package com.str.backend.exception;

import com.str.backend.domain.Status;
import com.str.backend.domain.TransitionTrigger;

public class IllegalStatusTransitionException extends RuntimeException {

    public IllegalStatusTransitionException(Status from, Status to, TransitionTrigger trigger) {
        super("Illegal status transition: " + from + " -> " + to + " (trigger=" + trigger + ")");
    }
}
