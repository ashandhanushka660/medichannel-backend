package com.medichannel.config;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

public final class FrontendOrigins {

    private static final List<String> REQUIRED_ORIGINS = List.of(
            "http://34.80.137.3:3000",
            "https://medichannel-f.vercel.app",
            "https://app.medichannel.dpdns.org"
    );

    private FrontendOrigins() {
    }

    public static List<String> resolve(String configuredOrigins) {
        LinkedHashSet<String> origins = new LinkedHashSet<>();

        if (configuredOrigins != null) {
            Arrays.stream(configuredOrigins.split(","))
                    .map(String::trim)
                    .filter(origin -> !origin.isEmpty())
                    .forEach(origins::add);
        }

        origins.addAll(REQUIRED_ORIGINS);

        if (origins.isEmpty() || origins.contains("*")) {
            throw new IllegalStateException(
                    "APP_FRONTEND_ORIGIN must list explicit origins, not '*'."
            );
        }

        return List.copyOf(origins);
    }
}
