package com.mhamzah.gateway.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.mhamzah.gateway.extension.InboundFile;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StorageKeysTest {

    private final InboundFile file = new InboundFile("doc", "Laporan Q3 (final).PDF", "application/pdf", new byte[] {1});

    @Test
    void builtInsAndRuleValuesFillTheTemplate() {
        String key = StorageKeys.build("/{field}/{name}.{ext}/{id}", Map.of("id", "42"), file, "c-1");
        assertThat(key).isEqualTo("doc/Laporan_Q3__final_.pdf/42");
        assertThat(StorageKeys.build("/{correlationId}-{filename}", Map.of(), file, "c-1"))
                .isEqualTo("c-1-Laporan_Q3__final_.PDF");
        // a rule wins over a built-in of the same name
        assertThat(StorageKeys.build("/{yyyy}/{filename}", Map.of("yyyy", "1999"), file, "c")).startsWith("1999/");
    }

    @Test
    void valuesCannotEscapeTheirSegment() {
        InboundFile evil = new InboundFile("f", "..\\..\\windows\\system.ini", "text/plain", new byte[0]);
        assertThat(StorageKeys.build("/{filename}", Map.of(), evil, "c")).isEqualTo("system.ini");
        assertThat(StorageKeys.build("/a/{x}", Map.of("x", "../../etc/passwd"), evil, "c")).isEqualTo("a/____etc_passwd");
        assertThat(StorageKeys.build("/a/{x}", Map.of("x", ".."), evil, "c")).isEqualTo("a/_");
        assertThat(StorageKeys.build("/a/{x}", Map.of("x", ""), evil, "c")).isEqualTo("a/_");
    }

    @Test
    void templatesAreChecked() {
        assertThat(StorageKeys.check("/{yyyy}/{uuid}-{filename}")).isNull();
        assertThat(StorageKeys.check("uploads/{filename}")).contains("must start with '/'");
        assertThat(StorageKeys.check("/../{filename}")).contains("no '..'");
        assertThat(StorageKeys.check("/a b/{filename}")).contains("may only contain");
        assertThat(StorageKeys.check("/dir/")).contains("must end with a file name");
        assertThat(StorageKeys.variables("/{yyyy}/{MM}/{uuid}")).containsExactly("yyyy", "MM", "uuid");
    }
}
