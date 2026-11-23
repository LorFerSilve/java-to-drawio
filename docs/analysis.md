# Analysis and diagram rules

The CLI validates the source and output paths, discovers Java files, builds a UML model, and writes uncompressed Draw.io XML to a temporary sibling of the destination. It closes the output stream before publication. With `--create-output-dir`, the CLI creates missing directories after successful analysis. An explicit `--force` allows replacement.

## Source and type identity

JavaParser parses each discovered file once at the Java 17 language level. The index uses declared packages and enclosing types, so type names are independent of file names and directory layout. For example, `shop.Service.Cursor` and `inventory.Service.Cursor` have separate identities. A duplicate fully qualified name is an error that identifies both declaration locations.

The model includes top-level and member classes, interfaces, annotation interfaces, enums, and records. It lists declared fields, enum constants, record components, constructors, and methods. Member signatures show visibility, parameter names and types, return types, generic parameters and bounds, and declared exceptions. Static elements are underlined and abstract elements italicized. A record's explicit compact constructor uses the parameter list from its record header. Compiler-generated constructors, enum operations, and record accessors are outside the model. JavaParser reads method bodies and initializers so their syntax is validated. Local and anonymous classes and the contents of statements are outside the diagram.

Module descriptors, package descriptors, and comment-only files count as analyzed files. A tree with Java files and no modeled types produces an empty editable page and a warning. Parse and source read errors are fatal even when other files in the tree are valid. A parse diagnostic includes the source position, line, and caret when JavaParser supplies a position. A recognizable unterminated package declaration gets a specific missing-semicolon message.

## Name resolution

The resolver uses the AST to look up type parameters, lexical and inherited member types, explicit imports, package members, on-demand imports, and qualified names. JavaParser SymbolSolver supplies type information from the running JDK's platform class loader. With `--classpath`, the converter also reads class metadata from the supplied archives using Javassist. When JARs contain the same binary class name, the first supplied archive supplies its metadata. Type parameters are modeled through their bounds. A missing explicit type import blocks fallback to another type with the same simple name. Competing known on-demand candidates produce an ambiguity warning.

If a signature type cannot be resolved, the warning names the file, line, column, and uncertain name. The diagram retains the signature text and any independently resolved types in the same signature. Only types declared in the scanned sources receive UML nodes and relationships, so ordinary JDK types such as `String` and `List` do not clutter the graph.

## Edges

| Relationship | Java evidence and direction | Appearance |
| --- | --- | --- |
| Generalization | `extends` from a class to its superclass or from an interface to its superinterface. | Solid line with a hollow triangle toward the parent. |
| Realization | `implements` from a class, enum, or record to its interface. | Dashed line with a hollow triangle toward the interface. |
| Association | From the declaring type to a type named in a field or record component, including array elements and generic arguments. | Solid line. |
| Dependency | From the declaring type to types in constructor and method parameters, return types, declared exceptions, generic bounds, and type arguments in `extends` or `implements`. | Dashed line with an open arrow toward the referenced type. |

Each directed source, target, and kind combination appears once. An association and an inheritance edge between the same types can both carry information. A dependency is omitted when that pair already has a generalization, realization, or association. Self-associations are shown as loops. Self-dependencies are omitted. A field is evidence of a reference and carries no ownership or multiplicity inference. A sealed type's `permits` list is checked for unresolved names. Each permitted subtype's own declaration supplies its inheritance edge.

## Layout

The generator starts with a deterministic grid, grouping closely connected types through a breadth-first traversal of the model. It sizes the editable class header and member compartments from their text. Row and column gaps leave room for orthogonal connectors. Larger, dense graphs can have crossing or shared connector segments, which can be adjusted in the editor.

With `--overview`, the first page shows compact type shapes and every generated relationship, with types grouped into editable package containers. Each package has a separate detail page with signatures and relationships within that package. Relationships between packages appear on the overview. Code in the unnamed package gets its own group and detail page.

## Limits of source-only analysis

- Supply the local JARs you want the converter to use, including any relevant transitive dependencies. Archive resolution uses class metadata. Maven and Gradle build scripts, manifest classpaths, and annotation processors are outside the analysis. External supertypes outside the supplied inputs can hide inherited names. On-demand imports are checked against the sources, platform types, and supplied archives.
- The converter performs structural analysis. Compiler checks such as module accessibility, generic type checking, and inherited members generated by a tool like Lombok are outside its scope.
- Dependency extraction covers declarations and signatures. Relationships that exist only in bodies, field initializers, annotations, or reflective calls are outside the model.
- When source variants declare the same fully qualified name, point the converter at the variant you want to diagram.

See the [Java Language Specification on names and scope](https://docs.oracle.com/javase/specs/jls/se17/html/jls-6.html), [JavaParser documentation](https://github.com/javaparser/javaparser/blob/master/doc/readme.md), and [Draw.io XML file format reference](https://www.drawio.com/docs/reference/file-format/).
