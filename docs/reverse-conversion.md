# Draw.io to Java

The `drawio-to-java` command reads Draw.io XML in either plain or compressed form. It uses every page in the file, so a package overview and its detail pages form one model. Use editable class shapes or text boxes with UML declarations.

## Class declarations

An ordinary box can use a qualified title such as `+ shop.Order`. A title can also have a `package shop` line, or sit inside a container titled `package shop`. The UML class shape from the Draw.io library can keep fields and operations in separate child rows. Use a visibility marker on the title of an empty ordinary box.

| Text in the diagram | Java declaration |
| --- | --- |
| `- quantity: int` | Private instance field. |
| `+ limit: int {static}` | Public static field. Underlining also marks a static member. |
| `+ Order(quantity: int)` | Constructor. Its name matches the class. |
| `+ total(unitPrice: int): int` | Public method that returns an `int`. Use `void` for methods with no result. |
| `# save(items: String...): void {throws java.io.IOException}` | Protected method with a variable-arity parameter and a throws clause. |
| `~ refresh(): void` | Package-private method. |

Use `«interface»`, `«abstract»`, `«enumeration»`, `«record»`, or `«annotation»` before the name, on a separate line or on the same line. Italics mark an abstract class or method. Text modifiers include `{abstract}`, `{static}`, `{default}`, `{final}`, `{sealed}`, and `{non-sealed}` where Java permits them. List enum constants by name. Nonstatic record fields become record components. For nested types, use a name such as `Order.Line` and give its enclosing type and nested type the same explicit package.

## Types and relationships

Generics can appear in the title and signatures, for example `+ Repository<T>` and `+ find(ids: List<T>): T`. Put optional `import`, `extends`, `implements` and `permits` lines below the class name and before its members.

```text
«interface»
+ stock.Reader<T>
import java.util.List
+ read(): List<T>
```

Use a solid UML generalization arrow for `extends` and a dashed UML realization arrow for `implements`. The hollow triangle points to the parent type. Association and dependency lines describe references already present in the signatures.

New Java to Draw.io exports store imports and Java inheritance clauses as shape data. This retains imported types and clauses such as `extends Base<String>` when the diagram shows an arrow to `Base`. When editing an exported diagram, keep any existing typed clauses consistent with the arrows. In diagrams without this data, an unambiguous public project or JDK type can be imported automatically. Use a qualified type name or an explicit `import` line to resolve an ambiguous short name.

## Generated sources

The output has one file per top-level type. Nested types go in their enclosing file. Abstract methods and annotation elements use declaration syntax. Concrete methods and constructors use TODO placeholders. The converter can also add a TODO method for an abstract operation inherited from a type present in the diagram. Its diagnostic names the affected shape and signature.

The diagram defines what can be recovered. Older exports may omit imports, external inheritance, or generic arguments on an inheritance arrow. Original method bodies, field initializers, annotations on members and build files require source or build information beyond the UML model. Some designs need additional constructor arguments, method overrides, or library dependencies before compilation. Java 17 is the source language level.

Invalid declarations report the input file and, when available, the page and shape ID. The CLI exits with code `2` for invalid arguments or an invalid input path, `3` for invalid XML or UML, and `4` when it cannot safely write the output.
