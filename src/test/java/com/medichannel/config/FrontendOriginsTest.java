package com.medichannel.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class FrontendOriginsTest {

    @Test
    void configuredOriginsAreCombinedWithProductionOrigins() {
        List<String> origins = FrontendOrigins.resolve(
                "http://localhost:3000, https://medichannel-f.vercel.app"
        );

        assertThat(origins).containsExactly(
                "http://localhost:3000",
                "https://medichannel-f.vercel.app",
                "http://34.80.137.3:3000",
                "https://app.medichannel.dpdns.org"
        );
    }

    @Test
    void blankConfigurationStillAllowsProductionOrigins() {
        assertThat(FrontendOrigins.resolve(" ")).containsExactly(
                "http://34.80.137.3:3000",
                "https://medichannel-f.vercel.app",
                "https://app.medichannel.dpdns.org"
        );
    }

    @Test
    void wildcardConfigurationIsRejected() {
        assertThatThrownBy(() -> FrontendOrigins.resolve("*"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("explicit origins");
    }
}
