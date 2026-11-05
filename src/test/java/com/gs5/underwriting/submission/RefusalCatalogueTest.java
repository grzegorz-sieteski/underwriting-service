package com.gs5.underwriting.submission;

import com.gs5.underwriting.submission.adapters.access.AccessRefusal;
import com.gs5.underwriting.submission.domain.RegisterSubmissionError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The refusal catalogue is the contract a caller relies on, and it must not shrink or grow silently.
 *
 * <p>Registration has exactly nine reasons (BR-01..BR-12, BR-29, BR-06/BR-09), produced in two layers:
 * the access gate produces the first two, before any input is parsed, and the domain produces the
 * other seven. No single type proves the nine complete, so this test asserts their union.
 *
 * <p>Search's single reason, caller not identified, is not a domain type any more and is proven
 * where it is produced: {@code PermissionMatrixTest} and {@code OperationGateTest} show the search
 * gate refuses with nothing but {@code CallerNotIdentified}.
 *
 * <p>Access refusals are owned by the gate; registration domain refusals are owned by the
 * registration operation. The union is exactly the nine reasons visible to a caller.
 */
class RefusalCatalogueTest {

    @Test
    @DisplayName("the gate's two refusals and the domain's seven are together exactly the nine "
            + "registration refusals the specification lists, each produced once")
    void shouldHaveExactlyNineRegistrationRefusalsAcrossGateAndDomain() {
        var names = Stream.concat(
                        Arrays.stream(AccessRefusal.class.getPermittedSubclasses()),
                        Arrays.stream(RegisterSubmissionError.class.getPermittedSubclasses()))
                .map(Class::getSimpleName)
                .sorted()
                .toList();

        assertThat(names).containsExactly(
                "CallerNotIdentified",
                "InsuredLimitMissing",
                "InsuredLimitNotPositive",
                "InsuredLimitPrecisionInvalid",
                "NumberAlreadyUsed",
                "NumberContainsControlCharacter",
                "NumberMissing",
                "NumberTooLong",
                "PermissionDenied");
    }

    @Test
    @DisplayName("the access gate refuses for exactly two reasons, both decided before any input")
    void shouldHaveExactlyTwoAccessRefusals() {
        var names = Arrays.stream(AccessRefusal.class.getPermittedSubclasses())
                .map(Class::getSimpleName)
                .sorted()
                .toList();

        assertThat(names).containsExactly("CallerNotIdentified", "PermissionDenied");
    }
}
