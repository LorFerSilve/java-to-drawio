/* License and comments can appear before the package and declaration. */
package sample.catalog;

/** An item that can be offered to a customer. */
public sealed
interface Item /* Must share the module with its permitted implementations. */
        permits Product, Bundle {
    enum Availability { READY, BACK_ORDER }

    String displayName();
}
