package com.pos.catalog.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Where product images are kept: S3 in production, an S3-compatible store in development.
 *
 * <p>With no bucket configured the catalog still runs; uploading an image answers 503 and says why,
 * rather than the service refusing to start over a feature it can live without.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "pos.storage")
public class ImageStorageProperties {

    /** Blank for AWS itself; the S3-compatible store's URL in development. */
    private String endpoint = "";

    private String region = "af-south-1";

    private String bucket = "";

    /** Blank to use the default credential chain (an instance or task role in production). */
    private String accessKey = "";

    private String secretKey = "";

    /** Path-style addressing, which S3-compatible stores need and AWS does not. */
    private boolean pathStyle = false;

    /** Create the bucket at first use if it is missing: for development and tests only. */
    private boolean createBucket = false;

    /** The largest image accepted, in bytes. */
    private long maxImageBytes = 2 * 1024 * 1024;

    public boolean configured() {
        return bucket != null && !bucket.isBlank();
    }
}
