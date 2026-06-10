package io.floci.conformance.util;

import io.floci.conformance.generator.BoundaryGenerator;
import io.floci.conformance.generator.EmptyInputGenerator;
import io.floci.conformance.generator.EnumExhaustGenerator;
import io.floci.conformance.generator.Generator;
import io.floci.conformance.generator.IdentifierFanoutGenerator;
import io.floci.conformance.generator.ModelExamplesGenerator;
import io.floci.conformance.generator.NegativeGenerator;
import io.floci.conformance.generator.OptionalsGenerator;
import io.floci.conformance.generator.PropertyBasedGenerator;

import java.util.List;

/**
 * The canonical generator lineup for full conformance runs. Shared by the
 * reporting and baseline-gate tests so the two can't drift apart — a generator
 * added here is automatically part of both the report and the gate.
 */
public final class AllGenerators {

    public static final List<Generator> ALL = List.of(
            new EmptyInputGenerator(),
            new OptionalsGenerator(),
            new EnumExhaustGenerator(),
            new NegativeGenerator(),
            new BoundaryGenerator(),
            new PropertyBasedGenerator(),
            new ModelExamplesGenerator(),
            new IdentifierFanoutGenerator());

    private AllGenerators() {
    }
}
