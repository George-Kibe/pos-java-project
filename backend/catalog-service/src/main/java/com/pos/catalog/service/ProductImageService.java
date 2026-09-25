package com.pos.catalog.service;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.pos.catalog.config.ImageStorageProperties;
import com.pos.catalog.domain.Product;
import com.pos.catalog.repository.ProductRepository;
import com.pos.common.error.Errors;
import com.pos.common.id.UuidV7;

import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * A product's picture: kept in object storage, its key on the product, served back through this
 * service.
 *
 * <p>The type is decided by the bytes, never by what the browser declared: only JPEG, PNG and WebP
 * are kept, and anything else - an SVG with script in it, an HTML page renamed .png - is refused. A
 * replaced image's object is deleted only after the new key has committed, so a failed save never
 * leaves a product pointing at nothing.
 */
@Service
@RequiredArgsConstructor
public class ProductImageService {

    private static final Logger log = LoggerFactory.getLogger(ProductImageService.class);

    private final ProductRepository products;
    private final S3Client s3;
    private final ImageStorageProperties storage;
    private final AtomicBoolean bucketChecked = new AtomicBoolean();

    public record Image(byte[] bytes, String contentType) {}

    @Transactional
    public Product upload(UUID productId, byte[] bytes) {
        requireConfigured();
        if (bytes == null || bytes.length == 0) {
            throw new Errors.BadRequestException("image.empty", "The image file is empty.");
        }
        if (bytes.length > storage.getMaxImageBytes()) {
            throw new Errors.BadRequestException(
                    "image.too_large",
                    "The image is %d KB; the most accepted is %d KB."
                            .formatted(bytes.length / 1024, storage.getMaxImageBytes() / 1024));
        }
        String type = sniff(bytes);
        if (type == null) {
            throw new Errors.BadRequestException(
                    "image.unsupported", "Upload a JPEG, PNG or WebP image.");
        }
        Product product = requireForResponse(productId);

        String key =
                "products/%s/%s.%s"
                        .formatted(
                                productId, UuidV7.randomUUID(), type.substring("image/".length()));
        ensureBucket();
        try {
            s3.putObject(
                    request -> request.bucket(storage.getBucket()).key(key).contentType(type),
                    RequestBody.fromBytes(bytes));
        } catch (S3Exception | software.amazon.awssdk.core.exception.SdkClientException failure) {
            throw unavailable(failure);
        }

        String previous = product.getImageKey();
        product.setImageKey(key);
        product.setImageUrl(urlFor(product));
        deleteAfterCommit(previous);
        return products.save(product);
    }

    @Transactional
    public Product remove(UUID productId) {
        Product product = requireForResponse(productId);
        deleteAfterCommit(product.getImageKey());
        product.setImageKey(null);
        product.setImageUrl(null);
        return products.save(product);
    }

    @Transactional(readOnly = true)
    public Image image(UUID productId) {
        Product product =
                products.findById(productId)
                        .orElseThrow(() -> Errors.NotFoundException.of("Product", productId));
        if (product.getImageKey() == null) {
            throw new Errors.NotFoundException("image.none", "This product has no image.");
        }
        requireConfigured();
        try {
            var object =
                    s3.getObjectAsBytes(
                            request ->
                                    request.bucket(storage.getBucket()).key(product.getImageKey()));
            String type = object.response().contentType();
            return new Image(
                    object.asByteArray(), type == null ? sniff(object.asByteArray()) : type);
        } catch (NoSuchKeyException | NoSuchBucketException missing) {
            throw new Errors.NotFoundException("image.none", "This product's image is missing.");
        } catch (S3Exception | software.amazon.awssdk.core.exception.SdkClientException failure) {
            throw unavailable(failure);
        }
    }

    /**
     * The product with what its response reads - category, unit, tax class, brand, barcodes - so
     * the controller maps it after this transaction without touching a lazy association.
     */
    private Product requireForResponse(UUID productId) {
        Product product =
                products.findWithDetailsById(productId)
                        .orElseThrow(() -> Errors.NotFoundException.of("Product", productId));
        org.hibernate.Hibernate.initialize(product.getBarcodes());
        return product;
    }

    /** The API path the image is served from, versioned by its key so caches never go stale. */
    static String urlFor(Product product) {
        if (product.getImageKey() == null) {
            return null;
        }
        String version = Integer.toHexString(product.getImageKey().hashCode());
        return "/api/v1/products/%s/image?v=%s".formatted(product.getId(), version);
    }

    /** JPEG, PNG or WebP, by their magic numbers; anything else is null. */
    static String sniff(byte[] bytes) {
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xFF
                && (bytes[1] & 0xFF) == 0xD8
                && (bytes[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (bytes.length >= 8
                && (bytes[0] & 0xFF) == 0x89
                && bytes[1] == 'P'
                && bytes[2] == 'N'
                && bytes[3] == 'G'
                && bytes[4] == 0x0D
                && bytes[5] == 0x0A
                && bytes[6] == 0x1A
                && bytes[7] == 0x0A) {
            return "image/png";
        }
        if (bytes.length >= 12
                && bytes[0] == 'R'
                && bytes[1] == 'I'
                && bytes[2] == 'F'
                && bytes[3] == 'F'
                && bytes[8] == 'W'
                && bytes[9] == 'E'
                && bytes[10] == 'B'
                && bytes[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    private void deleteAfterCommit(String key) {
        if (key == null) {
            return;
        }
        Runnable delete =
                () -> {
                    try {
                        s3.deleteObject(request -> request.bucket(storage.getBucket()).key(key));
                    } catch (RuntimeException failure) {
                        // Only an orphaned object is left behind; the product is already right.
                        log.warn("Could not delete replaced image {}: {}", key, failure.toString());
                    }
                };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            delete.run();
                        }
                    });
        } else {
            delete.run();
        }
    }

    private void ensureBucket() {
        if (!storage.isCreateBucket() || bucketChecked.get()) {
            return;
        }
        try {
            s3.headBucket(request -> request.bucket(storage.getBucket()));
        } catch (NoSuchBucketException missing) {
            s3.createBucket(request -> request.bucket(storage.getBucket()));
        } catch (S3Exception failure) {
            if (failure.statusCode() != 404) {
                throw unavailable(failure);
            }
            s3.createBucket(request -> request.bucket(storage.getBucket()));
        }
        bucketChecked.set(true);
    }

    private void requireConfigured() {
        if (!storage.configured()) {
            throw new Errors.ServiceUnavailableException(
                    "image.storage_not_configured",
                    "Image storage is not configured for this installation.");
        }
    }

    private static Errors.ServiceUnavailableException unavailable(RuntimeException failure) {
        log.warn("Image storage failed: {}", failure.toString());
        return new Errors.ServiceUnavailableException(
                "image.storage_unavailable",
                "The image store could not be reached. Nothing was changed; try again.");
    }
}
