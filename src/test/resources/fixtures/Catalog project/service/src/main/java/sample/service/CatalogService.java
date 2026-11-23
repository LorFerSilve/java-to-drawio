/** The service joins source types from two other roots. */
package sample.service;

import java.io.IOException;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
import sample.catalog.*;
import static sample.catalog.Item.Availability;

/** Presents the catalog to application callers. */
@Reviewed
public class
CatalogService<I extends Item> {
    private final Repository<I> repository;
    private final Map<String, List<? extends I[]>> batches = Map.of();

    CatalogService(Repository<I> repository) { this.repository = repository; }

    protected static <T extends Item & Serializable> List<T> find(T... candidates) throws IOException {
        Runnable report = () -> {
            String template = """
                    A text block may contain tokens like class Fake {}.
                    """;
            if (template.isBlank()) throw new IllegalStateException();
        };
        report.run();
        return List.of(candidates);
    }

    public sample.audit.Product export(Product product) {
        return new sample.audit.Product(product.displayName());
    }

    private class Cursor {
        I current;
        Availability state;
    }
}

interface Repository<T extends Item> extends Lookup<T> {
    T save(T item);
}

interface Lookup<T> {
    T find(String name);
}

@interface Reviewed {
    String value() default "";
}
