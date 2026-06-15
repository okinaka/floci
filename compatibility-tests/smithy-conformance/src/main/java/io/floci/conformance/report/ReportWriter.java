package io.floci.conformance.report;

import io.floci.conformance.model.VariantResult;

import java.io.IOException;
import java.io.Writer;
import java.util.List;

/**
 * Renders a list of {@link VariantResult}s into a serialized report.
 *
 * <p>All writers must produce output deterministically (sort by op name,
 * verdict, generator) so future baseline-diff phases can compare files
 * line-for-line.
 */
public interface ReportWriter {

    void write(ReportMeta meta, List<VariantResult> results, Writer out) throws IOException;
}
