package com.kangban.rag;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VectorStoreModeTest {

    @Test
    void keepsLegacyNamesOnTheMySqlMode() {
        assertThat(VectorStoreMode.parse("mysql")).isEqualTo(VectorStoreMode.MYSQL);
        assertThat(VectorStoreMode.parse("mysql-jdbc")).isEqualTo(VectorStoreMode.MYSQL);
        assertThat(VectorStoreMode.parse("memory")).isEqualTo(VectorStoreMode.MYSQL);
    }

    @Test
    void supportsDualAndQdrantModes() {
        assertThat(VectorStoreMode.parse("dual")).isEqualTo(VectorStoreMode.DUAL);
        assertThat(VectorStoreMode.parse("QDRANT")).isEqualTo(VectorStoreMode.QDRANT);
    }

    @Test
    void rejectsUnknownMode() {
        assertThatThrownBy(() -> VectorStoreMode.parse("pinecone"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持");
    }
}
