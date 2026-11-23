package io.github.lorfersilve.javatodrawio;

import com.github.javaparser.ast.AccessSpecifier;
import javassist.bytecode.AccessFlag;
import javassist.bytecode.ClassFile;
import javassist.bytecode.InnerClassesAttribute;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipFile;

final class JavaClasspath {
    record ClassInfo(String qualifiedName, String packageName, AccessSpecifier access,
                     boolean staticMember, List<String> binaryParents) {
    }

    private final Map<String, Path> archivesByBinaryName;
    private final Map<String, Optional<ClassInfo>> binaryClasses = new HashMap<>();
    private final Map<String, Optional<ClassInfo>> canonicalClasses = new HashMap<>();

    private JavaClasspath(Map<String, Path> archivesByBinaryName) {
        this.archivesByBinaryName = archivesByBinaryName;
    }

    static JavaClasspath empty() {
        return new JavaClasspath(Map.of());
    }

    static JavaClasspath read(List<Path> paths) throws IOException {
        Map<String, Path> archives = new HashMap<>();
        for (Path path : paths) {
            Path filename = path.getFileName();
            if (filename == null || !filename.toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                throw new IOException("Classpath entry must name a local .jar file: " + path);
            }
            if (!Files.readAttributes(path, BasicFileAttributes.class).isRegularFile()) {
                throw new IOException("Classpath entry is not a regular file: " + path);
            }
            try (JarFile jar = openJar(path)) {
                jar.versionedStream().filter(entry -> !entry.isDirectory()).map(JarEntry::getName)
                        .filter(name -> name.endsWith(".class") && !name.startsWith("META-INF/"))
                        .forEach(name -> archives.putIfAbsent(binaryName(name), path));
            } catch (IOException | RuntimeException e) {
                throw new IOException("Cannot open classpath JAR " + path + ": " + e.getMessage(), e);
            }
        }
        return new JavaClasspath(archives);
    }

    Optional<ClassInfo> find(String canonicalName) {
        if (archivesByBinaryName.isEmpty()) {
            return Optional.empty();
        }
        return canonicalClasses.computeIfAbsent(canonicalName, this::findCanonicalClass);
    }

    private Optional<ClassInfo> findCanonicalClass(String name) {
        String candidate = name;
        for (int dot = name.lastIndexOf('.'); ; dot = name.lastIndexOf('.', dot - 1)) {
            Optional<ClassInfo> info = findBinary(candidate).filter(type -> name.equals(type.qualifiedName()));
            if (info.isPresent() || dot < 0) {
                return info;
            }
            candidate = candidate.substring(0, dot) + '$' + candidate.substring(dot + 1);
        }
    }

    Optional<ClassInfo> findBinary(String binaryName) {
        Path path = archivesByBinaryName.get(binaryName);
        return path == null ? Optional.empty()
                : binaryClasses.computeIfAbsent(binaryName, name -> readClass(path, name));
    }

    private static Optional<ClassInfo> readClass(Path archive, String binaryName) {
        String entryName = binaryName.replace('.', '/') + ".class";
        try (JarFile jar = openJar(archive)) {
            JarEntry entry = jar.getJarEntry(entryName);
            if (entry == null) {
                throw new IOException("Entry " + entryName + " no longer exists");
            }
            try (var input = new DataInputStream(new BufferedInputStream(jar.getInputStream(entry)))) {
                ClassFile classFile = new ClassFile(input);
                if (!binaryName.equals(classFile.getName())) {
                    throw new IOException("Class name differs from its JAR entry");
                }
                return classInfo(classFile);
            }
        } catch (IOException | RuntimeException e) {
            throw new UncheckedIOException(new IOException(
                    "Cannot read class " + binaryName + " from " + archive + ": " + e.getMessage(), e));
        }
    }

    private static Optional<ClassInfo> classInfo(ClassFile classFile) throws IOException {
        String binaryName = classFile.getName();
        String canonicalName = canonicalName(classFile);
        if (canonicalName == null) {
            return Optional.empty(); // Local and anonymous classes have no canonical name.
        }
        int dot = binaryName.lastIndexOf('.');
        String packageName = dot < 0 ? "" : binaryName.substring(0, dot);
        int innerAccess = classFile.getInnerAccessFlags();
        int flags = innerAccess < 0 ? classFile.getAccessFlags() : innerAccess;
        List<String> parents = new ArrayList<>();
        if (classFile.getSuperclass() != null) {
            parents.add(classFile.getSuperclass());
        }
        parents.addAll(List.of(classFile.getInterfaces()));
        return Optional.of(new ClassInfo(canonicalName, packageName, accessSpecifier(flags),
                innerAccess >= 0 && (flags & AccessFlag.STATIC) != 0, List.copyOf(parents)));
    }

    private static String canonicalName(ClassFile classFile) throws IOException {
        String name = classFile.getName();
        String suffix = "";
        if (classFile.getAttribute(InnerClassesAttribute.tag) instanceof InnerClassesAttribute innerClasses) {
            int depth = 0;
            for (int entry = innerClasses.find(name); entry >= 0; entry = innerClasses.find(name)) {
                if (++depth > innerClasses.tableLength()) {
                    throw new IOException("Cyclic inner class metadata");
                }
                name = innerClasses.outerClass(entry);
                String member = innerClasses.innerName(entry);
                if (name == null || member == null) {
                    return null;
                }
                suffix = "." + member + suffix;
            }
        }
        return name + suffix;
    }

    private static AccessSpecifier accessSpecifier(int flags) {
        if ((flags & AccessFlag.PUBLIC) != 0) {
            return AccessSpecifier.PUBLIC;
        }
        if ((flags & AccessFlag.PROTECTED) != 0) {
            return AccessSpecifier.PROTECTED;
        }
        return (flags & AccessFlag.PRIVATE) != 0 ? AccessSpecifier.PRIVATE : AccessSpecifier.NONE;
    }

    private static JarFile openJar(Path path) throws IOException {
        return new JarFile(path.toFile(), false, ZipFile.OPEN_READ, Runtime.version());
    }

    private static String binaryName(String entry) {
        return entry.substring(0, entry.length() - ".class".length()).replace('/', '.');
    }
}
