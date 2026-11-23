package io.github.lorfersilve.javatodrawio;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

record CliOptions(Path source, Path output, boolean force, boolean createOutputDir, boolean overview,
                  List<Path> classpath) {
    static CliOptions parse(String[] args) {
        Path source = null;
        Path output = null;
        boolean force = false;
        boolean createOutputDir = false;
        boolean overview = false;
        List<Path> classpath = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < args.length; i++) {
            String option = args[i];
            if (!option.equals("--classpath") && !seen.add(option)) {
                throw new IllegalArgumentException("Repeated argument " + option + ".");
            }
            switch (option) {
                case "--source" -> source = pathArgument(args, ++i, option);
                case "--output" -> output = pathArgument(args, ++i, option);
                case "--force" -> force = true;
                case "--create-output-dir" -> createOutputDir = true;
                case "--overview" -> overview = true;
                case "--classpath" -> classpath.add(pathArgument(args, ++i, option));
                default -> throw new IllegalArgumentException("Unexpected argument " + option + ".");
            }
        }
        if (source == null || output == null) {
            throw new IllegalArgumentException("Both --source and --output are required.");
        }
        if (output.getFileName() == null
                || !output.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".drawio")) {
            throw new IllegalArgumentException("--output must name a .drawio file.");
        }
        return new CliOptions(source, output, force, createOutputDir, overview, List.copyOf(classpath));
    }

    static Path pathArgument(String[] args, int index, String option) {
        if (index >= args.length || args[index].isBlank() || args[index].startsWith("--")) {
            throw new IllegalArgumentException("Missing path after " + option + ".");
        }
        return Path.of(args[index]).toAbsolutePath().normalize();
    }
}
