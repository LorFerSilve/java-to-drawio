package sample.catalog;

public abstract class StockItem {
    protected final String id;

    protected StockItem(String id) { this.id = id; }

    public abstract String displayName();
}
