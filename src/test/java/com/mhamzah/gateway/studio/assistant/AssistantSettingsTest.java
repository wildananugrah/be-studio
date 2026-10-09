package com.mhamzah.gateway.studio.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class AssistantSettingsTest {

    @TempDir
    Path dir;

    @Test
    void readsTheEnvFileWithQuotesCommentsAndExport() throws Exception {
        Path env = dir.resolve(".env");
        Files.writeString(env, """
                # comment
                AI_BASE_URL='https://ai.example.com'
                export AI_AUTH_KEY="sk-test"
                AI_MODEL=sonnet # trailing comment
                OTHER=x
                """);
        AssistantSettings s = AssistantSettings.load(new MockEnvironment(), env);
        assertThat(s.baseUrl()).isEqualTo("https://ai.example.com");
        assertThat(s.authKey()).isEqualTo("sk-test");
        assertThat(s.model()).isEqualTo("sonnet");
        assertThat(s.effort()).isNull();
        assertThat(s.configured()).isTrue();
        assertThat(s.toString()).doesNotContain("sk-test");
    }

    @Test
    void environmentWinsAndModelHasADefault() throws Exception {
        Path env = dir.resolve(".env");
        Files.writeString(env, "AI_AUTH_KEY=from-file\n");
        AssistantSettings s = AssistantSettings.load(new MockEnvironment().withProperty("AI_AUTH_KEY", "from-env"), env);
        assertThat(s.authKey()).isEqualTo("from-env");
        assertThat(s.model()).isEqualTo(AssistantSettings.DEFAULT_MODEL);
        assertThat(s.baseUrl()).isNull();
    }

    @Test
    void authTypeIsBearerExceptForAnthropicApiKeysUnlessSet() {
        MockEnvironment env = new MockEnvironment().withProperty("AI_AUTH_KEY", "sk-or-v1-abc");
        assertThat(AssistantSettings.load(env, dir.resolve("x")).bearer()).isTrue();
        env.setProperty("AI_AUTH_KEY", "sk-ant-api03-abc");
        assertThat(AssistantSettings.load(env, dir.resolve("x")).bearer()).isFalse();
        env.setProperty("AI_AUTH_TYPE", "bearer");
        assertThat(AssistantSettings.load(env, dir.resolve("x")).bearer()).isTrue();
        env.setProperty("AI_AUTH_KEY", "gateway-key");
        env.setProperty("AI_AUTH_TYPE", "api-key");
        assertThat(AssistantSettings.load(env, dir.resolve("x")).bearer()).isFalse();
    }

    @Test
    void missingFileMeansNotConfigured() {
        assertThat(AssistantSettings.load(new MockEnvironment(), dir.resolve("nope.env")).configured()).isFalse();
    }

    @Test
    void historyIsTrimmedToAlternatingTurnsStartingWithTheUser() {
        StudioAssistant a = new StudioAssistant(AssistantSettings.load(new MockEnvironment(), dir.resolve("x")), null, 1000, 3);
        List<StudioAssistant.Turn> turns = a.normalize(List.of(
                new StudioAssistant.Turn("user", "q1"), new StudioAssistant.Turn("assistant", "a1"),
                new StudioAssistant.Turn("user", "q2"), new StudioAssistant.Turn("user", "q2b"),
                new StudioAssistant.Turn("assistant", "a2"), new StudioAssistant.Turn("user", "q3")));
        assertThat(turns).extracting(StudioAssistant.Turn::role).containsExactly("user", "assistant", "user");
        assertThat(turns.getFirst().content()).isEqualTo("q2\n\nq2b");
    }
}
