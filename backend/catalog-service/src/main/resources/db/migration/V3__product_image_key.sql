-- Product images live in object storage (S3; an S3-compatible store in development). The row keeps
-- the object's key; image_url becomes the API path the image is served from, versioned by the key
-- so a replaced image is never served stale from a cache.
--
-- Adds a nullable column only, so it is safe with the previous version still running.
ALTER TABLE products ADD COLUMN image_key VARCHAR(300);
