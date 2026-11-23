# Changelog

## [0.1.0]

- Generate editable Draw.io UML class diagrams from Java 17 source with the standalone `java-to-drawio.jar` CLI.
- Discover Java files across multiple source roots and extract classes, interfaces, nested types, records, enums, annotations, and their field and operation signatures.
- Identify inheritance, interface realization, field associations, and signature dependencies using fully qualified names. Report unresolved references with source locations.
- Generate a deterministic layout and protect manually edited files with explicit `--force` overwrite behavior.
- Verify the packaged JAR end to end on Windows and Linux.
