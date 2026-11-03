package com.gs5.underwriting.submission.domain;

import com.gs5.underwriting.shared.kernel.Result;

import java.math.BigDecimal;

final class SubmissionCommandFixtures {

    private SubmissionCommandFixtures() { }

    static RegisterSubmissionCommand parsedCommand(String externalRef, BigDecimal insuredLimit) {
        return switch (RegisterSubmissionCommand.parse(externalRef, insuredLimit)) {
            case Result.Success<RegisterSubmissionCommand, RegisterSubmissionError>(var command) ->
                    command;
            case Result.Failure<RegisterSubmissionCommand, RegisterSubmissionError>(var error) ->
                    throw new AssertionError("Expected valid command, got " + error);
        };
    }
}
