package io.github.lorfersilve.javatodrawio;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

final class ReverseCommand {
    private static final String HELP = """
            Usage: java -jar java-to-drawio.jar drawio-to-java --input <diagram.drawio> --output <src-directory> [--author <name>]

              --input   Draw.io UML class diagram.
              --output  Directory for the package folders and .java files. Created if needed.
                        Existing files are never overwritten.
              --author  Name to include in each type's generated Javadoc.
              --help    Show this help text.
            """;

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 1 && args[0].equals("--help")) {
            out.print(HELP);
            return 0;
        }
        Path input = null;
        Path output = null;
        String author = "";
        try {
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < args.length; i++) {
                String option = args[i];
                if (!seen.add(option)) {
                    throw new IllegalArgumentException("Repeated argument " + option + ".");
                }
                switch (option) {
                    case "--input" -> input = CliOptions.pathArgument(args, ++i, option);
                    case "--output" -> output = CliOptions.pathArgument(args, ++i, option);
                    case "--author" -> author = authorArgument(args, ++i);
                    default -> throw new IllegalArgumentException("Unexpected argument " + option + ".");
                }
            }
            if (input == null || output == null) {
                throw new IllegalArgumentException("Both --input and --output are required.");
            }
            if (!Files.isRegularFile(input)) {
                throw new IllegalArgumentException("Input is not a Draw.io file: " + input);
            }
        } catch (IllegalArgumentException | SecurityException e) {
            err.println("Argument error: " + Main.describe(e));
            err.println("Use drawio-to-java --help to see the usage.");
            return 2;
        }

        DiagramModelReader.Diagram diagram;
        JavaSkeletons.Result result;
        try {
            diagram = new DiagramModelReader().read(input);
            result = new JavaSkeletons(diagram).generate(author);
        } catch (IOException | IllegalArgumentException | SecurityException e) {
            err.println("Cannot convert " + input + ": " + Main.describe(e));
            return 3;
        }
        result.warnings().forEach(warning -> err.println("Warning: " + warning));
        try {
            JavaSourceOutput.write(output, result.sources());
        } catch (IOException | SecurityException e) {
            err.println("Output error: " + Main.describe(e));
            return 4;
        }
        out.printf(Locale.ROOT, "Generated %d Java files from %d UML types.%n", result.sources().size(),
                diagram.model().types().size());
        out.println("Wrote " + output);
        return 0;
    }

    private static String authorArgument(String[] args, int index) {
        if (index >= args.length || args[index].isBlank() || args[index].startsWith("--")) {
            throw new IllegalArgumentException("Missing name after --author.");
        }
        String name = args[index].strip();
        if (name.codePoints().anyMatch(c -> Character.isISOControl(c) || c == 0x2028 || c == 0x2029)) {
            throw new IllegalArgumentException("--author must contain a name on one line.");
        }
        return name;
    }

    private ReverseCommand() {
    }
}
