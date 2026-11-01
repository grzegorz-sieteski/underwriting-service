package com.example.service.shared.kernel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behaviour of the railway itself.
 *
 * <p>Assertions compare whole {@code Result} values rather than calling {@code isSuccess()} and
 * then unwrapping, which is the habit the TDD instructions ask for everywhere else.
 */
class ResultTest {

    sealed interface SampleError {
        record TooShort(int actual) implements SampleError { }

        record NotFound() implements SampleError { }
    }

    @Nested
    @DisplayName("map / flatMap")
    class Transformations {

        @Test
        @DisplayName("maps the value on the happy path")
        void maps_value_on_success() {
            Result<String, SampleError> result = Result.success("abc");

            var mapped = result.map(String::length);

            assertThat(mapped).isEqualTo(Result.<Integer, SampleError>success(3));
        }

        @Test
        @DisplayName("leaves a failure untouched and does not run the mapper")
        void keeps_failure_on_map() {
            Result<String, SampleError> result = Result.failure(new SampleError.NotFound());

            var mapped = result.map(_ -> "never evaluated");

            assertThat(mapped).isEqualTo(Result.<String, SampleError>failure(new SampleError.NotFound()));
        }

        @Test
        @DisplayName("short-circuits the chain at the first failure")
        void flat_map_short_circuits() {
            Result<String, SampleError> result = Result.success("ab");

            var chained = result
                    .flatMap(value -> value.length() >= 3
                            ? Result.<String, SampleError>success(value)
                            : Result.<String, SampleError>failure(new SampleError.TooShort(value.length())))
                    .map(String::toUpperCase);

            assertThat(chained).isEqualTo(Result.<String, SampleError>failure(new SampleError.TooShort(2)));
        }
    }

    @Nested
    @DisplayName("ensure / fold")
    class InvariantsAndCollapse {

        @Test
        @DisplayName("turns a success into a failure when the invariant does not hold")
        void ensure_rejects_invalid_value() {
            Result<String, SampleError> result = Result.success("ab");

            var checked = result.ensure(v -> v.length() >= 3, v -> new SampleError.TooShort(v.length()));

            assertThat(checked).isEqualTo(Result.<String, SampleError>failure(new SampleError.TooShort(2)));
        }

        @Test
        @DisplayName("keeps a success when the invariant holds")
        void ensure_keeps_valid_value() {
            Result<String, SampleError> result = Result.success("abcd");

            var checked = result.ensure(v -> v.length() >= 3, v -> new SampleError.TooShort(v.length()));

            assertThat(checked).isEqualTo(Result.<String, SampleError>success("abcd"));
        }

        @Test
        @DisplayName("collapses both branches, which is how an adapter leaves the railway")
        void fold_collapses_both_branches() {
            Result<String, SampleError> ok = Result.success("abc");
            Result<String, SampleError> bad = Result.failure(new SampleError.TooShort(1));

            String okMessage = ok.fold(value -> "ok:" + value, error -> "err:" + error);
            String badMessage = bad.fold(value -> "ok:" + value, _ -> "err");

            assertThat(okMessage).isEqualTo("ok:abc");
            assertThat(badMessage).isEqualTo("err");
        }
    }

    @Nested
    @DisplayName("Optional bridge")
    class OptionalBridge {

        @Test
        @DisplayName("a present value becomes a success")
        void present_optional_becomes_success() {
            var result = Result.<String, SampleError>fromOptional(
                    Optional.of("found"), SampleError.NotFound::new);

            assertThat(result).isEqualTo(Result.<String, SampleError>success("found"));
        }

        @Test
        @DisplayName("an empty lookup becomes the supplied domain error")
        void empty_optional_becomes_supplied_failure() {
            var result = Result.<String, SampleError>fromOptional(
                    Optional.empty(), SampleError.NotFound::new);

            assertThat(result).isEqualTo(Result.<String, SampleError>failure(new SampleError.NotFound()));
        }
    }
}
