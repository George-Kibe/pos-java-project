import { z } from "zod";

/** The catalog as the back office manages it. Money arrives as JSON numbers with four places. */

export const CatalogProductSchema = z.object({
  id: z.uuid(),
  sku: z.string(),
  name: z.string(),
  description: z.string().nullable(),
  categoryId: z.uuid(),
  categoryName: z.string(),
  brandId: z.uuid().nullable(),
  brandName: z.string().nullable(),
  unitOfMeasure: z.string(),
  unitOfMeasureId: z.uuid(),
  taxClassCode: z.string(),
  taxClassId: z.uuid(),
  sellByWeight: z.boolean(),
  priceIncludesTax: z.boolean(),
  basePrice: z.number(),
  currency: z.string(),
  active: z.boolean(),
  barcodes: z.array(z.string()),
  reorderPoint: z.number().nullable(),
  reorderQuantity: z.number().nullable(),
  imageUrl: z.string().nullable(),
});
export type CatalogProduct = z.infer<typeof CatalogProductSchema>;

export const CategorySchema = z.object({ id: z.uuid(), code: z.string(), name: z.string(), parentId: z.uuid().nullable(), active: z.boolean() });
export type Category = z.infer<typeof CategorySchema>;

export const BrandSchema = z.object({ id: z.uuid(), code: z.string(), name: z.string(), active: z.boolean() });
export type Brand = z.infer<typeof BrandSchema>;

export const UnitSchema = z.object({ id: z.uuid(), code: z.string(), name: z.string(), allowsDecimal: z.boolean(), decimalPlaces: z.number().int() });
export type Unit = z.infer<typeof UnitSchema>;

export const TaxClassSchema = z.object({
  id: z.uuid(),
  code: z.string(),
  name: z.string(),
  description: z.string().nullable(),
  rates: z.array(z.object({ rate: z.number(), validFrom: z.string(), validTo: z.string().nullable() })),
  active: z.boolean(),
  isDefault: z.boolean(),
});
export type TaxClass = z.infer<typeof TaxClassSchema>;

export const PriceListSchema = z.object({
  id: z.uuid(),
  code: z.string(),
  name: z.string(),
  branchId: z.uuid().nullable(),
  priority: z.number().int(),
  validFrom: z.string().nullable(),
  validTo: z.string().nullable(),
  active: z.boolean(),
  items: z.number().int(),
});
export type PriceList = z.infer<typeof PriceListSchema>;

export const PriceListItemSchema = z.object({
  productId: z.uuid(),
  sku: z.string(),
  productName: z.string(),
  basePrice: z.number(),
  price: z.number(),
  currency: z.string(),
});

export const PROMOTION_TYPES = ["PERCENTAGE_OFF", "AMOUNT_OFF", "BUY_X_GET_Y", "BUNDLE"] as const;
export const PROMOTION_SCOPES = ["PRODUCT", "CATEGORY", "ALL"] as const;

export const PromotionSchema = z.object({
  id: z.uuid(),
  code: z.string(),
  name: z.string(),
  type: z.enum(PROMOTION_TYPES),
  value: z.number().nullable(),
  buyQuantity: z.number().nullable(),
  getQuantity: z.number().nullable(),
  minQuantity: z.number().nullable(),
  priority: z.number().int(),
  stackable: z.boolean(),
  memberOnly: z.boolean(),
  branchId: z.uuid().nullable(),
  validFrom: z.string().nullable(),
  validTo: z.string().nullable(),
  active: z.boolean(),
  rules: z.array(z.object({ scope: z.enum(PROMOTION_SCOPES), scopeId: z.uuid().nullable() })),
});
export type Promotion = z.infer<typeof PromotionSchema>;

export const PricePreviewSchema = z.object({
  productName: z.string().nullable(),
  quantity: z.number(),
  unitPrice: z.number(),
  priceSource: z.string(),
  subtotal: z.number(),
  discounts: z.array(z.object({ code: z.string().nullable(), name: z.string().nullable(), type: z.string(), amount: z.number() })),
  discountTotal: z.number(),
  tax: z.number(),
  lineTotal: z.number(),
  currency: z.string(),
});
export type PricePreview = z.infer<typeof PricePreviewSchema>;
