# Changelog

## [0.4.0]

- Add Javadoc templates to generated Java types, constructors, methods, fields and enum constants.
- Populate Javadoc tags for type parameters, arguments, return values, record components and declared exceptions.
- Add an optional `--author <name>` to label each generated type.

## [0.3.0]

- Add `drawio-to-java --input <diagram.drawio> --output <src-directory>` to generate Java 17 skeletons from editable UML class diagrams.
- Read native Draw.io class shapes, typed UML labels, and older exports, including compressed and multipage diagrams.
- Preserve imports and Java inheritance declarations in new exports to improve round-trip conversion.
- Generate placeholders for concrete operations and missing inherited methods from modeled types. Report malformed declarations and protect existing source files.
- Compile generated skeletons in the end-to-end tests.

## [0.2.0]

- Show more actionable parse errors with source lines, carets, and a specific hint for missing package semicolons.
- Add `--overview` for a compact package diagram and detailed pages with signatures.
- Add `--create-output-dir` to create missing destination directories on successful analysis.
- Add repeatable `--classpath <jar>` for local library type resolution.

## [0.1.0]

- Generate editable Draw.io UML class diagrams from Java 17 source with the standalone `java-to-drawio.jar` CLI.
- Discover Java files across multiple source roots and extract classes, interfaces, nested types, records, enums, annotations, and their field and operation signatures.
- Identify inheritance, interface realization, field associations, and signature dependencies using fully qualified names. Report unresolved references with source locations.
- Generate a deterministic layout and protect manually edited files with explicit `--force` overwrite behavior.
- Verify the packaged JAR end to end on Windows and Linux.
