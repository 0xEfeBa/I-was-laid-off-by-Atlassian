package dev.triedge.broker.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SpecDiffTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void reportsChangedAddedAndRemovedValuesByPath() {
        JsonNode before = json.readTree("""
                {"name":"shop","hosts":["shop.test"],"routes":[{"path":"/","backend":"api"}],"hook":{"failOpen":true}}
                """);
        JsonNode after = json.readTree("""
                {"name":"shop","hosts":["shop.test","www.shop.test"],"routes":[{"path":"/v2","backend":"api"}]}
                """);

        List<SpecDiff.Change> changes = SpecDiff.between(before, after);

        assertThat(changes).extracting(SpecDiff.Change::path)
                .containsExactly("/hook", "/hosts/1", "/routes/0/path");
        assertThat(changes.get(0).after()).isNull();
        assertThat(changes.get(1).before()).isNull();
        assertThat(changes.get(2).after().asString()).isEqualTo("/v2");
    }

    @Test
    void identicalAndReplacedDocuments() {
        JsonNode spec = json.readTree("{\"name\":\"shop\"}");

        assertThat(SpecDiff.between(spec, spec.deepCopy())).isEmpty();
        assertThat(SpecDiff.between(spec, json.readTree("[1]")))
                .singleElement().satisfies(change -> assertThat(change.path()).isEqualTo("/"));
    }
}
