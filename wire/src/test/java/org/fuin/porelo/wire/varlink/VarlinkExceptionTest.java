package org.fuin.porelo.wire.varlink;

import static org.assertj.core.api.Assertions.*;

import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;

class VarlinkExceptionTest {
    @Test
    void newerRefusalsCanCarryUnknownNullFieldsWithoutLosingTheNamedError() {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("detail", "safe detail");
        fields.put("future", null);
        var refusal = new VarlinkException("example.Store.Refused", fields);
        fields.clear();
        assertThat(refusal.getErrorName()).isEqualTo("example.Store.Refused");
        assertThat(refusal.getParameters())
                .containsEntry("detail", "safe detail")
                .containsKey("future");
        assertThatThrownBy(() -> refusal.getParameters().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
