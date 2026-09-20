package com.pos.catalog.api;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pos.catalog.api.dto.CatalogDtos;
import com.pos.catalog.service.ProductImportService;
import com.pos.catalog.service.ProductService;
import com.pos.common.web.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/** Product administration. */
@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
@Tag(name = "Products")
public class ProductController {

    private final ProductService products;
    private final ProductImportService importer;

    @GetMapping
    @PreAuthorize("hasAuthority('product:view')")
    @Operation(summary = "Search products by name or SKU")
    public PageResponse<CatalogDtos.ProductResponse> list(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) UUID categoryId,
            @PageableDefault(size = 25) Pageable pageable) {
        return PageResponse.of(
                products.search(query, categoryId, pageable), CatalogDtos.ProductResponse::from);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('product:view')")
    @Operation(summary = "Fetch one product")
    public CatalogDtos.ProductResponse get(@PathVariable UUID id) {
        return CatalogDtos.ProductResponse.from(products.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('product:manage')")
    @Operation(summary = "Create a product")
    public ResponseEntity<CatalogDtos.ProductResponse> create(
            @Valid @RequestBody CatalogDtos.ProductRequest request) {

        var created = products.create(toCommand(request));
        if (request.barcodes() != null && !request.barcodes().isEmpty()) {
            created = products.replaceBarcodes(created.getId(), request.barcodes());
        }
        CatalogDtos.ProductResponse body = CatalogDtos.ProductResponse.from(created);
        return ResponseEntity.created(URI.create("/api/v1/products/" + body.id())).body(body);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('product:manage')")
    @Operation(summary = "Update a product")
    public CatalogDtos.ProductResponse update(
            @PathVariable UUID id, @Valid @RequestBody CatalogDtos.ProductRequest request) {
        return CatalogDtos.ProductResponse.from(products.update(id, toCommand(request)));
    }

    @PutMapping("/{id}/barcodes")
    @PreAuthorize("hasAuthority('product:manage')")
    @Operation(summary = "Replace a product's barcodes; the first becomes the primary")
    public CatalogDtos.ProductResponse barcodes(
            @PathVariable UUID id, @Valid @RequestBody CatalogDtos.BarcodesRequest request) {
        return CatalogDtos.ProductResponse.from(products.replaceBarcodes(id, request.barcodes()));
    }

    @PutMapping("/{id}/active")
    @PreAuthorize("hasAuthority('product:manage')")
    @Operation(summary = "Activate or deactivate a product")
    public CatalogDtos.ProductResponse setActive(
            @PathVariable UUID id, @RequestParam boolean active) {
        return CatalogDtos.ProductResponse.from(products.setActive(id, active));
    }

    @PostMapping(
            value = "/import",
            consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('product:manage')")
    @Operation(
            summary = "Bulk load products from CSV",
            description =
                    "Columns: sku, name, categoryCode, uomCode, taxClassCode, basePrice, and"
                            + " optionally brandCode, sellByWeight, priceIncludesTax and"
                            + " barcodes (pipe-separated). Good rows are applied even when others fail;"
                            + " the response lists each failure with its line number.")
    public ProductImportService.ImportReport importProducts(
            @org.springframework.web.bind.annotation.RequestParam("file")
                    org.springframework.web.multipart.MultipartFile file)
            throws java.io.IOException {

        if (file.isEmpty()) {
            throw new com.pos.common.error.Errors.BadRequestException(
                    "import.empty_file", "The uploaded file is empty.");
        }
        return importer.importFrom(file.getInputStream());
    }

    private static ProductService.NewProduct toCommand(CatalogDtos.ProductRequest request) {
        return new ProductService.NewProduct(
                request.sku(),
                request.name(),
                request.description(),
                request.categoryId(),
                request.brandId(),
                request.unitOfMeasureId(),
                request.taxClassId(),
                request.sellsByWeight(),
                request.pricedInclusiveOfTax(),
                request.basePrice(),
                request.reorderPoint(),
                request.reorderQuantity(),
                request.imageUrl(),
                request.active());
    }
}
