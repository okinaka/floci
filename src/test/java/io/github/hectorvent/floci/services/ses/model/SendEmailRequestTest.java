package io.github.hectorvent.floci.services.ses.model;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.fail;

class SendEmailRequestTest {

    // Iterates the record components so a field added to the record but not to toBuilder() fails
    // here instead of silently resetting to its default on every bulk entry.
    @Test
    void toBuilderCopiesEveryComponent() throws Exception {
        SendEmailRequest.Builder builder = SendEmailRequest.builder();
        for (RecordComponent component : SendEmailRequest.class.getRecordComponents()) {
            Method setter = SendEmailRequest.Builder.class.getMethod(component.getName(), component.getType());
            setter.invoke(builder, sampleValue(component));
        }
        SendEmailRequest original = builder.build();
        SendEmailRequest defaults = SendEmailRequest.builder().build();
        for (RecordComponent component : SendEmailRequest.class.getRecordComponents()) {
            assertNotEquals(component.getAccessor().invoke(defaults), component.getAccessor().invoke(original),
                    component.getName() + " must differ from its default for the round trip to prove anything");
        }

        assertEquals(original, original.toBuilder().build());
    }

    private static Object sampleValue(RecordComponent component) {
        if (component.getType() == String.class) {
            return component.getName() + "-value";
        }
        if (component.getType() == ListManagementOptions.class) {
            return new ListManagementOptions("list", "topic");
        }
        if (component.getType() == List.class) {
            Object element = ((ParameterizedType) component.getGenericType()).getActualTypeArguments()[0];
            if (element == String.class) {
                return List.of(component.getName() + "@example.com");
            }
            if (element == MessageTag.class) {
                return List.of(new MessageTag("tag", "value"));
            }
            if (element == MessageHeader.class) {
                return List.of(new MessageHeader("X-Header", "value"));
            }
        }
        return fail("no sample value for component " + component.getName() + " of type " + component.getGenericType());
    }
}
