package dev.langchain4j.model.anthropic;

import static dev.langchain4j.data.message.UserMessage.userMessage;
import static dev.langchain4j.model.anthropic.AnthropicChatModelName.CLAUDE_3_5_HAIKU_20241022;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import dev.langchain4j.model.anthropic.internal.client.AnthropicHttpException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import io.ktor.http.HttpStatusCode;
import java.time.Duration;
import java.util.Random;
import me.kpavlov.aimocks.anthropic.MockAnthropic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@Execution(ExecutionMode.CONCURRENT)
class AnthropicChatModelErrorsTest {

    private static final MockAnthropic MOCK = new MockAnthropic(0, true);

    public static final Duration TIMEOUT = Duration.ofSeconds(2);

    private static final ChatModel model = AnthropicChatModel.builder()
            .apiKey("dummy-key")
            .baseUrl(MOCK.baseUrl() + "/v1")
            .modelName(CLAUDE_3_5_HAIKU_20241022)
            .maxTokens(20)
            .timeout(TIMEOUT)
            .logRequests(true)
            .logResponses(true)
            .build();

    private double seed;

    @BeforeEach
    void setUp() {
        seed = new Random().nextDouble(0.0, 1.0);
    }

    /**
     * See <a href="https://docs.anthropic.com/en/api/errors#http-errors">Anthropic HTTP errors</a>
     */
    @ParameterizedTest
    @CsvSource({
        "400, invalid_request_error",
        "401, authentication_error",
        "403, permission_error",
        "404, not_found_error",
        "413, request_too_large",
        "429, rate_limit_error",
        "500, api_error",
        "529, overloaded_error",
    })
    void should_handle_error_response(int httpStatusCode, String type) {
        final var question = "What is the number: " + seed;
        final var message = "Error with seed: " + seed;

        // language=json
        final var responseBody =
                """
                        {
                          "type": "error",
                          "error": {
                            "type": "%s",
                            "message": "%s"
                          }
                        }
                        """
                        .formatted(type, message);

        MOCK.messages(req -> req.userMessageContains(question)).respondsError(res -> {
            res.setBody(responseBody);
            res.setHttpStatus(HttpStatusCode.Companion.fromValue(httpStatusCode));
        });

        // when-then
        final var chatRequest =
                ChatRequest.builder().messages(userMessage(question)).build();
        assertThatExceptionOfType(AnthropicHttpException.class)
                // when
                .isThrownBy(() -> model.chat(chatRequest))
                .satisfies(ex -> {
                    assertThat(ex.statusCode()).as("statusCode").isEqualTo(httpStatusCode);
                    assertThat(ex.getMessage())
                            .as("message")
                            .isEqualTo(responseBody); // not sure if returning full body is right
                });
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 10, 100})
    void should_handle_timeout(int millis) {

        // given
        Duration timeout = Duration.ofMillis(millis);

        ChatModel model = AnthropicChatModel.builder()
                .apiKey("dummy-key")
                .baseUrl(MOCK.baseUrl() + "/v1")
                .modelName(CLAUDE_3_5_HAIKU_20241022)
                .maxTokens(20)
                .timeout(timeout)
                .logRequests(true)
                .logResponses(true)
                .build();

        final var question = "Simulate timeout " + System.currentTimeMillis();
        MOCK.messages(req -> req.userMessageContains(question)).respondsError(res -> {
            // don't really care about the response, just simulate a timeout
            res.delayMillis(TIMEOUT.plusMillis(250).toMillis());
            res.setHttpStatus(HttpStatusCode.Companion.getNoContent());
            res.setBody("");
        });

        // when-then
        assertThatExceptionOfType(RuntimeException.class) // TODO
                // when
                .isThrownBy(() -> model.chat(question))
                // both socket timeout and read timeout are currently possible
                .withMessageMatching(".*(timeout|Read timed out)");
    }
}
