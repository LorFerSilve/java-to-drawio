# Java to Draw.io

Generate an editable UML class diagram from a Java project or source directory. The CLI reads source files locally and writes a `.drawio` file with class shapes, editable member labels, and connectors.

## Run the converter

Download `java-to-drawio.jar` from [GitHub Releases](https://github.com/LorFerSilve/java-to-drawio/releases). A Java 17 or newer runtime is sufficient.

```powershell
java -jar java-to-drawio.jar --source "C:\Projects\My Java Project" --output ".\architecture.drawio"
java -jar java-to-drawio.jar --help
```

Open the resulting file in Draw.io Desktop or diagrams.net. Move or resize classes, edit their labels, and add shapes or connectors using the editor.

The output directory must exist. To regenerate into an existing file, pass `--force`. Regeneration replaces the entire file, including any manual edits.

| Argument | Purpose |
| --- | --- |
| `--source <directory>` | Scan a project root or a specific source directory recursively. |
| `--output <file.drawio>` | Write the diagram to this path. Quote paths with spaces. |
| `--force` | Allow the converter to replace an existing regular file. |
| `--help` | Show usage and exit. |

The summary reports files processed, UML types, relationships, warnings, and the destination. Diagnostics appear on standard error.

| Exit code | Meaning |
| --- | --- |
| `0` | Diagram written successfully, with any unresolved references reported as warnings. `--help` also exits with `0`. |
| `2` | Invalid arguments, an inaccessible source directory, or no Java files to scan. |
| `3` | Source read or parse failure, or conflicting type identities. The output remains intact. |
| `4` | Invalid output destination or failure to write the file. |

## Supported input

The converter accepts UTF-8 Java source using the Java 17 language level, including syntax from earlier versions that remains valid in Java 17. The parser accepts comments, Javadoc, annotations, lambdas, text blocks, generics, records, enums, and sealed and nested types. Java previews and source syntax introduced after Java 17 fall outside this language level.

It supports Maven, Gradle, and ordinary folder layouts, including multiple source roots and files in the unnamed package. It scans `.java` files throughout the supplied directory, including test sources in that tree. Directories named `.git`, `.gradle`, `target`, `build`, `out`, and `node_modules` are skipped, as are symbolic links and directory junctions within the tree. To diagram production sources only, pass the appropriate source directory.

Analysis uses the declarations in the selected source tree and recognizes JDK types from the running Java installation. Unavailable external types and ambiguous references are reported with source locations. Read the [analysis rules and limitations](docs/analysis.md) for details.

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
