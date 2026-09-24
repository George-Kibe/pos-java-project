package com.pos.notification.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Base64;

/** The logo that heads every email, read once from the classpath. */
public final class BrandLogo {

    private static final String RESOURCE = "/brand/logo.png";

    private BrandLogo() {}

    public static byte[] png() {
        try (InputStream in = BrandLogo.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing " + RESOURCE + " on the classpath");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** For a browser preview, where there is no message for a Content-ID to point into. */
    public static String dataUri() {
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(png());
    }
}
