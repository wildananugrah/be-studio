package com.mhamzah.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mhamzah.gateway.config.ConfigRows.TargetRow;
import com.mhamzah.gateway.extension.DefaultErrorHandler;
import com.mhamzah.gateway.sql.SqlDatasources;
import com.mhamzah.gateway.storage.FileStores;
import com.mhamzah.gateway.storage.LocalFileStore;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** File storage steps: PUT/DELETE, a valid key template, a rule for every key variable, the file to store. */
class StorageStepCompileTest {

    private Rows rows = new Rows();
    private final FileStores stores = new FileStores(Map.of("FILES",
            new LocalFileStore("FILES", Path.of("build-test-files"), List.of(), 0)));

    private final java.util.List<ConfigRows.StorageRow> storageRows = new java.util.ArrayList<>();

    private ConfigRows.StorageRow storageRow(String code, String type, String baseDir, String bucket, String maxSize,
            boolean enabled) {
        ConfigRows.StorageRow r = new ConfigRows.StorageRow(storageRows.size() + 1, code, type, baseDir, bucket, null,
                null, null, false, null, null, null, maxSize, enabled);
        storageRows.add(r);
        return r;
    }

    private FlowRegistry compile() {
        ConfigCompiler compiler = new ConfigCompiler(
                new ConfigCompiler.HandlerLookup() {
                    @Override
                    public <T> T find(String name, Class<T> type) {
                        Object bean = "defaultErrorHandler".equals(name) ? new DefaultErrorHandler() : null;
                        return type.isInstance(bean) ? type.cast(bean) : null;
                    }
                },
                Map.of(), s -> s, Duration.ofSeconds(30), Duration.ofSeconds(10), SqlDatasources.none(), stores);
        ConfigRows built = rows.build();
        return compiler.compile(new ConfigRows(built.flows(), built.steps(), built.rules(), built.lookups(),
                built.schemas(), built.targets(), built.targetHeaders(), storageRows));
    }

    private void assertInvalid(String... fragments) {
        assertThatThrownBy(this::compile).isInstanceOfSatisfying(ConfigValidationException.class, e -> {
            for (String f : fragments) {
                assertThat(e.errors()).anySatisfy(err -> assertThat(err).contains(f));
            }
        });
    }

    private ConfigRows.StepRow storeStep(String method, String key) {
        var flow = rows.flow("UP", "POST", "/up/{id}");
        return rows.step(flow, "store", 1, s -> s.withTarget("FILES").withMethod(method).withPathTemplate(key));
    }

    @Test
    void compilesAStoreStep() {
        var step = storeStep("PUT", "/{yyyy}/{id}-{filename}");
        rows.stepRule(step, "$.file", "$.request.files.file");
        rows.rule(step.flowId(), step.id(), "STEP_REQUEST", "PATH", "id", "$.request.path.id", null);

        StepDefinition def = compile().flows().getFirst().allSteps().getFirst();

        assertThat(def.isStorage()).isTrue();
        assertThat(def.fileStore().name()).isEqualTo("FILES");
        assertThat(def.methodName()).isEqualTo("PUT");
    }

    @Test
    void storeNeedsTheFileAndEveryKeyVariable() {
        storeStep("PUT", "/{yyyy}/{id}-{filename}");
        assertInvalid("needs a BODY rule writing $.file", "key variable {id} has no PATH mapping rule");
    }

    @Test
    void methodAndKeyTemplateAreChecked() {
        storeStep("POST", "/../{filename}");
        assertInvalid("http_method is PUT (store the file) or DELETE, not POST", "no '..'");
    }

    @Test
    void aNameCannotBeBothATargetAndAStorage() {
        rows.target(new TargetRow(1, "FILES", "http://x", null, null, null, true));
        storeStep("DELETE", "/{filename}");
        assertInvalid("'FILES' is both a target system and a file storage");
    }

    @Test
    void storagesFromTheDatabaseAreUsableAndCheckedOnReload() {
        storageRow("ARCHIVE", "LOCAL", "${ARCHIVE_DIR:build-test-archive}", null, "2MB", true);
        var flow = rows.flow("UP", "POST", "/up");
        var step = rows.step(flow, "store", 1, s -> s.withTarget("ARCHIVE").withMethod("PUT").withPathTemplate("/{uuid}"));
        rows.stepRule(step, "$.file", "$.request.files.file");

        StepDefinition def = compile().flows().getFirst().allSteps().getFirst();
        assertThat(def.fileStore().name()).isEqualTo("ARCHIVE");
        assertThat(def.fileStore().maxSize()).isEqualTo(2L * 1024 * 1024);
        assertThat(def.fileStore().location()).contains("build-test-archive");
    }

    @Test
    void aDisabledRowHidesTheApplicationYmlStorageOfTheSameName() {
        storageRow("FILES", "LOCAL", "x", null, null, false);
        storeStep("DELETE", "/{filename}");
        assertInvalid("target_system 'FILES' is not configured");
    }

    @Test
    void badStorageRowsAreReported() {
        storageRow("S3_X", "S3", null, null, null, true);
        storageRow("WEIRD", "FTP", null, null, null, true);
        storageRow("SIZE", "LOCAL", "d", null, "ten megs", true);
        storageRow("NODIR", "LOCAL", null, null, null, true);
        assertInvalid("storage 'S3_X': bucket is required", "storage 'WEIRD': storage_type must be LOCAL or S3",
                "storage 'SIZE': max_size 'ten megs' is not a size", "storage 'NODIR': base_dir is required");
    }
}
