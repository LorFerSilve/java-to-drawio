package sample.catalog;

import java.io.Serializable;

public final class Product extends StockItem implements Item, Serializable {
    private Availability availability;
    private Product replacement;

    public Product(String id) {
        super(id);
        availability = Availability.READY;
    }

    @Override
    public String displayName() { return id; }
}
