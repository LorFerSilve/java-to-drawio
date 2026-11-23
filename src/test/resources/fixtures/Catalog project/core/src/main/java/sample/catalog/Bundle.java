package sample.catalog;

import java.util.List;

public record Bundle(List<Product> products) implements Item {
    public Bundle {
        products = List.copyOf(products);
    }

    @Override
    public String displayName() { return "Bundle of " + products.size(); }
}
