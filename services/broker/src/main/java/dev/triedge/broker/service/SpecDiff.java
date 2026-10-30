package dev.triedge.broker.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

public final class SpecDiff {

    public record Change(String path, @Nullable JsonNode before, @Nullable JsonNode after) {
    }

    private SpecDiff() {
    }

    public static List<Change> between(JsonNode before, JsonNode after) {
        List<Change> changes = new ArrayList<>();
        walk("", before, after, changes);
        return changes;
    }

    private static void walk(String path, @Nullable JsonNode before, @Nullable JsonNode after, List<Change> changes) {
        if (Objects.equals(before, after)) {
            return;
        }
        if (before != null && after != null && before.isObject() && after.isObject()) {
            TreeSet<String> names = new TreeSet<>();
            before.properties().stream().map(Map.Entry::getKey).forEach(names::add);
            after.properties().stream().map(Map.Entry::getKey).forEach(names::add);
            for (String name : names) {
                walk(path + "/" + name, before.get(name), after.get(name), changes);
            }
            return;
        }
        if (before != null && after != null && before.isArray() && after.isArray()) {
            for (int i = 0; i < Math.max(before.size(), after.size()); i++) {
                walk(path + "/" + i, before.get(i), after.get(i), changes);
            }
            return;
        }
        changes.add(new Change(path.isEmpty() ? "/" : path, before, after));
    }
}
