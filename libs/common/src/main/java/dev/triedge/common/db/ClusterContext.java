package dev.triedge.common.db;

import java.util.Optional;
import java.util.function.Supplier;

public final class ClusterContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private ClusterContext() {
    }

    public static Optional<String> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static <T> T call(String cluster, Supplier<T> work) {
        String previous = CURRENT.get();
        CURRENT.set(cluster);
        try {
            return work.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    public static void run(String cluster, Runnable work) {
        call(cluster, () -> {
            work.run();
            return null;
        });
    }
}
