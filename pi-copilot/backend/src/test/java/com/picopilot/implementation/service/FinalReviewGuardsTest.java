package com.picopilot.implementation.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FinalReviewGuardsTest {

    @Test
    void implementationNeverWritesOutsideTheConnectorFolders() {
        List<String> includes = List.of("employee-management");
        ImplementationExecutor.requireWithinIncludes(
            List.of("employee-management/backend/src/main/java/A.java"), includes);
        assertThatThrownBy(() -> ImplementationExecutor.requireWithinIncludes(
            List.of("pi-copilot/backend/pom.xml"), includes))
            .hasMessageContaining("outside the GitHub connector's selected folders");
        // a sibling folder sharing the prefix is still outside
        assertThatThrownBy(() -> ImplementationExecutor.requireWithinIncludes(
            List.of("employee-management-old/x.java"), includes))
            .isInstanceOf(IllegalStateException.class);
        // no folders selected means the whole repository is the application
        ImplementationExecutor.requireWithinIncludes(List.of("anything.txt"), List.of());
    }

    @Test
    void aiEditsAreLimitedToTheApplicationAndMustChangeSomething() {
        StringBuilder log = new StringBuilder();
        Map<String, String> edits = ImprovementExecutor.validEdits(List.of(
                new CodeReviser.FileEdit("employee-management/src/main/java/A.java", "new"),
                new CodeReviser.FileEdit("pi-copilot/backend/src/Main.java", "evil"),
                new CodeReviser.FileEdit("employee-management/../pi-copilot/x", "evil"),
                new CodeReviser.FileEdit("/employee-management/src/main/java/B.java", "b"),
                new CodeReviser.FileEdit("employee-management/src/main/java/C.java", null)),
            Map.of(), List.of("employee-management/"), log);

        assertThat(edits).containsOnlyKeys(
            "employee-management/src/main/java/A.java", "employee-management/src/main/java/B.java");
        assertThat(log.toString()).contains("outside the application folders", "Ignored invalid AI edit");
    }

    @Test
    void allowedRootsFallBackToTheApplicationFoldersInThePullRequest() {
        assertThat(ImprovementExecutor.allowedRoots(List.of("employee-management"), Set.of()))
            .containsExactly("employee-management/");
        assertThat(ImprovementExecutor.allowedRoots(List.of(),
            Set.of("employee-management/backend/src/main/java/A.java", "employee-management/backend/src/main/resources/application.yml")))
            .containsExactly("employee-management/backend/");
    }

    @Test
    void lineCommentsBecomePartOfTheImprovementRequest() {
        String request = FinalReviewService.improvementRequest("Tidy up the entity", List.of(
            new FinalReviewService.LineComment("em/Employee.java", 23, "NEW", "    // PI Co-Pilot: encrypted copy", "Shorten this comment"),
            new FinalReviewService.LineComment("em/Employee.java", 7, "OLD", null, "Why was this removed?")));
        assertThat(request).isEqualTo("Tidy up the entity\n\nLine comments:\n"
            + "- em/Employee.java line 23 `// PI Co-Pilot: encrypted copy`: Shorten this comment\n"
            + "- em/Employee.java line 7 (original): Why was this removed?");
        // line comments alone are enough to request changes
        assertThat(FinalReviewService.improvementRequest("", List.of(
            new FinalReviewService.LineComment("a.java", 1, "NEW", "x", "y"))))
            .startsWith("Line comments:\n- a.java line 1");
    }

    @Test
    void localDevKeyBecomesTheConfigDefault() {
        String yaml = "server:\n  port: 8080\n";
        assertThat(EncryptionCodeTemplates.withYamlKey(yaml, "abc="))
            .endsWith("pi:\n  encryption:\n"
                + "    # Base64 AES-256 key shared with PI Co-Pilot's LOCAL_DEV provider (development only)\n"
                + "    local-key: ${PI_LOCAL_ENCRYPTION_KEY:abc=}\n");
        assertThat(EncryptionCodeTemplates.withYamlKey(yaml, null)).contains("local-key: ${PI_LOCAL_ENCRYPTION_KEY}");
        // never duplicate an existing top-level pi: block
        assertThat(EncryptionCodeTemplates.withYamlKey("pi:\n  other: 1\n", "abc=")).isNull();
        assertThat(EncryptionCodeTemplates.withPropertiesKey("a=b\n", "abc="))
            .contains("pi.encryption.local-key=${PI_LOCAL_ENCRYPTION_KEY:abc=}");
    }
}
