package io.floci.conformance.coverage;

import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.loader.ModelAssembler;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The {@code models/} directory of a checkout of
 * <a href="https://github.com/aws/api-models-aws">aws/api-models-aws</a>:
 * one directory per service, holding
 * {@code service/<version>/<service>-<version>.json}.
 */
final class ModelCatalog {

    private final Path modelsDir;

    ModelCatalog(Path modelsDir) {
        this.modelsDir = modelsDir;
    }

    /** Service directory names, sorted. */
    List<String> services() {
        try (Stream<Path> dirs = Files.list(modelsDir)) {
            return dirs.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The newest model version of {@code service}, if it has one. */
    Optional<Path> modelFile(String service) {
        Path versions = modelsDir.resolve(service).resolve("service");
        if (!Files.isDirectory(versions)) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.walk(versions, 2)) {
            return files.filter(p -> p.toString().endsWith(".json"))
                    .max(Path::compareTo);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Model load(Path file) {
        return new ModelAssembler()
                .addImport(file)
                .assemble()
                .getResult()
                .orElseThrow(() -> new IllegalStateException("Failed to load Smithy model: " + file));
    }
}
