# Java to Draw.io

Generate editable UML class diagrams from Java source, or create Java skeletons from Draw.io class diagrams. Both directions run locally in the same standalone CLI.

## Run the converter

Download `java-to-drawio.jar` from [GitHub Releases](https://github.com/LorFerSilve/java-to-drawio/releases). A Java 17 or newer runtime is sufficient.

```powershell
java -jar java-to-drawio.jar --source "C:\Projects\My Java Project" --output ".\architecture.drawio"
java -jar java-to-drawio.jar --help
```

Open the resulting file in Draw.io Desktop or diagrams.net. Move or resize classes, edit their labels, and add shapes or connectors using the editor.

Pass `--create-output-dir` to create the destination directory after the source has been analyzed. To regenerate into an existing file, pass `--force`. Regeneration replaces the entire file, including any manual edits.

For a presentation, use `--overview`. It opens with a compact class and relationship diagram grouped by package. Select a package tab to see its fields and operations.

```powershell
java -jar java-to-drawio.jar --source ".\src" --output ".\drawio\architecture.drawio" --create-output-dir --overview
```

To resolve types from local dependencies, add one `--classpath <jar>` per archive. Relative paths start at the current directory.

```powershell
java -jar java-to-drawio.jar --source ".\src" --output ".\architecture.drawio" --classpath ".\lib\Library A.jar" --classpath ".\lib\Library B.jar"
```

| Argument | Purpose |
| --- | --- |
| `--source <directory>` | Scan a project root or a specific source directory recursively. |
| `--output <file.drawio>` | Write the diagram to this path. Quote paths with spaces. |
| `--create-output-dir` | Create missing destination directories after successful analysis. |
| `--overview` | Add a compact overview followed by detailed pages for each package. |
| `--classpath <jar>` | Read type information from an explicitly supplied local JAR. Repeat for multiple archives. |
| `--force` | Allow the converter to replace an existing regular file. |
| `--help` | Show usage and exit. |

The summary reports files processed, UML types, relationships, warnings, and the destination. Diagnostics appear on standard error. Parse errors show a source line and position when available.

| Exit code | Meaning |
| --- | --- |
| `0` | Diagram written successfully, with any unresolved references reported as warnings. `--help` also exits with `0`. |
| `2` | Invalid arguments or classpath entries, an inaccessible source directory, or no Java files to scan. |
| `3` | Source or class metadata read failure, parse failure, or conflicting type identities. |
| `4` | Invalid output destination or failure to write the file. |

## Supported input

The converter accepts UTF-8 Java source using the Java 17 language level, including syntax from earlier versions that remains valid in Java 17. The parser accepts comments, Javadoc, annotations, lambdas, text blocks, generics, records, enums, and sealed and nested types. Java previews and source syntax introduced after Java 17 fall outside this language level.

It supports Maven, Gradle, and ordinary folder layouts, including multiple source roots and files in the unnamed package. It scans `.java` files throughout the supplied directory, including test sources in that tree. Directories named `.git`, `.gradle`, `target`, `build`, `out`, and `node_modules` are skipped, as are symbolic links and directory junctions within the tree. To diagram production sources only, pass the appropriate source directory.

Analysis uses the declarations in the selected source tree, JDK types from the running Java installation, and any JARs passed with `--classpath`. Unavailable external types and ambiguous references are reported with source locations. Read the [analysis rules and limitations](docs/analysis.md) for details.

## Generate Java from a Draw.io diagram

```powershell
java -jar java-to-drawio.jar drawio-to-java --input ".\drawio\architecture.drawio" --output ".\src-generated"
java -jar java-to-drawio.jar drawio-to-java --help
```

The command reads editable UML shapes from Draw.io, including older Java to Draw.io exports and exports with package overview pages. It creates the output directory and Java package folders as needed. An existing Java file at any destination stops the conversion before new files are written.

Use a UML class shape or put the title and each declaration on their own line in a box. A qualified title sets the Java package. Fields, operations and constructors use standard UML type notation.

```text
+ shop.Order
- quantity: int
+ Order(quantity: int)
+ total(unitPrice: int): int
```

This generates `shop/Order.java` under the output directory. Concrete methods and constructors contain TODO placeholders. For inheritance, point a solid hollow triangle to the superclass or superinterface. For interface implementation, use a dashed hollow triangle. The [reverse conversion guide](docs/reverse-conversion.md) covers generics, imports, supported shapes and diagnostics.

## Build from source

Use JDK 17 or newer and Maven 3.9 or newer.

```powershell
mvn clean verify
java -jar target/java-to-drawio.jar --help
```

`verify` runs the JUnit 5 unit tests and launches the packaged JAR against integration fixtures. The executable is `target/java-to-drawio.jar`. GitHub Actions runs the same build on Windows and Linux and uploads both build artifacts.

To publish a release, set the Maven version, add the corresponding `## [version]` section to [CHANGELOG.md](CHANGELOG.md), and push the tested commit and its `vMAJOR.MINOR.PATCH` tag. The tagged build runs the tests before publishing the Linux job's executable JAR with the changelog entry as release notes.

## License

[MIT](LICENSE)
