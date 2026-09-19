package io.floci.conformance.invoke;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code @http} URI template's literal query (e.g. {@code ?tagging}) must be
 * preserved — S3 disambiguates sub-resource operations by it. Dropping it would
 * degrade, say, GetObjectTagging into a plain GetObject and mis-measure the
 * operation.
 */
class RestXmlInvokerTest {

    @Test
    void staticQuery_extractsLiteralQueryFromTemplate() {
        assertThat(RestXmlInvoker.staticQuery("/{Bucket}/{Key+}?tagging")).isEqualTo("tagging");
        assertThat(RestXmlInvoker.staticQuery("/{Bucket}?acl")).isEqualTo("acl");
    }

    @Test
    void staticQuery_emptyWhenTemplateHasNoQuery() {
        assertThat(RestXmlInvoker.staticQuery("/{Bucket}/{Key+}")).isEmpty();
        assertThat(RestXmlInvoker.staticQuery("/")).isEmpty();
    }
}
