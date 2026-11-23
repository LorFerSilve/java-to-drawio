package io.github.lorfersilve.javatodrawio;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class Main {
    private static final String HELP = """
            Java to Draw.io
            Usage: java -jar java-to-drawio.jar --source <directory> --output <file.drawio> [options]
                   java -jar java-to-drawio.jar drawio-to-java --input <diagram.drawio> --output <src-directory>
                   java -jar java-to-drawio.jar --help

              --source <directory>  Java project or source directory to scan recursively
              --output <file>       .drawio destination
              --create-output-dir   Create missing destination directories on successful analysis
              --overview            Create a package overview and detail pages
              --classpath <jar>     Resolve library types from a local JAR (repeatable)
              --force               Allow replacing an existing output file
              --help                Show this help

            Quote paths containing spaces. Java source is UTF-8 Java 17.
            Use drawio-to-java --help for the reverse command.
            Exit codes: 0 success, 2 invalid arguments, source or classpath, 3 analysis failure, 4 output failure.
            """;

    public static void main(String[] args) {
        int status = run(args, System.out, System.err);
        if (status != 0) {
            System.exit(status);
        }
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length > 0 && args[0].equals("drawio-to-java")) {
            return ReverseCommand.run(Arrays.copyOfRange(args, 1, args.length), out, err);
        }
        if (args.length == 1 && args[0].equals("--help")) {
            out.print(HELP);
            return 0;
        }
        CliOptions options;
        try {
            options = CliOptions.parse(args);
        } catch (IllegalArgumentException | SecurityException e) {
            err.println("Argument error: " + e.getMessage());
            err.println("Use --help to see the usage.");
            return 2;
        }
        try {
            OutputFile.validate(options.output(), options.force(), options.createOutputDir());
        } catch (IOException | SecurityException e) {
            err.println("Output error: " + describe(e));
            return 4;
        }

        List<Path> files;
        try {
            files = SourceDiscovery.findJavaFiles(options.source());
        } catch (IOException | SecurityException e) {
            err.println("Source error: " + describe(e));
            return 2;
        }

        JavaClasspath classpath;
        try {
            classpath = JavaClasspath.read(options.classpath());
        } catch (IOException | SecurityException e) {
            err.println("Classpath error: " + describe(e));
            return 2;
        }

        JavaAnalyzer.Result result;
        try {
            result = new JavaAnalyzer(classpath).analyze(files);
        } catch (JavaAnalyzer.AnalysisException | UncheckedIOException e) {
            err.println("Analysis failed.");
            err.println(e instanceof UncheckedIOException fileError ? describe(fileError.getCause()) : e.getMessage());
            return 3;
        }
        result.warnings().forEach(warning -> err.println("Warning: " + warning));
        try {
            if (options.createOutputDir()) {
                Files.createDirectories(options.output().getParent());
            }
            OutputFile.write(options.output(), result.model(), options.force(), options.overview());
        } catch (IOException | SecurityException e) {
            err.println("Cannot write " + options.output() + ": " + describe(e));
            return 4;
        }
        out.printf(Locale.ROOT, "Processed %d Java files, %d UML types, %d relationships, %d warnings.%n",
                result.fileCount(), result.model().types().size(), result.model().relationships().size(),
                result.warnings().size());
        out.println("Wrote " + options.output());
        return 0;
    }

    static String describe(Exception error) {
        if (error instanceof AccessDeniedException) {
            return "Access denied (" + error.getMessage() + ")";
        }
        return error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
    }

    private Main() {
    }
}
