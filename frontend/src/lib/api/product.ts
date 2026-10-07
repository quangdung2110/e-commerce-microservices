import type { AxiosRequestConfig } from "axios";

import { api } from "@/lib/api/client";
import {
  getVersioned,
  ifMatchHeaders,
  toVersioned,
  type Versioned,
} from "@/lib/api/etag";
import type { components, operations } from "@/lib/api/generated/product";

type Schemas = components["schemas"];

export type Product = Schemas["Product"];
export type ProductSummary = Schemas["ProductSummary"];
export type ProductPage = Schemas["ProductPage"];
export type ProductStatus = Schemas["ProductStatus"];
export type ProductWrite = Schemas["CreateProductRequest"];
export type ProductReplace = Schemas["ReplaceProductRequest"];
export type ProductStatusWrite = Schemas["ChangeProductStatusRequest"];
export type Sku = Schemas["Sku"];
export type SkuStatus = Schemas["SkuStatus"];
export type SkuWrite = Schemas["CreateSkuRequest"];
export type SkuReplace = Schemas["ReplaceSkuRequest"];
export type Media = Schemas["Media"];
export type MediaType = Schemas["MediaType"];
export type MediaWrite = Schemas["CreateMediaRequest"];
export type MediaReplace = Schemas["ReplaceMediaRequest"];
export type Category = Schemas["Category"];
export type CategoryWrite = Schemas["CreateCategoryRequest"];
export type CategoryReplace = Schemas["ReplaceCategoryRequest"];
export type CategoryAttribute = Schemas["CategoryAttribute"];
export type CategoryAttributeWrite = Schemas["PutCategoryAttributeRequest"];
export type Attribute = Schemas["Attribute"];
export type AttributeWrite = Schemas["CreateAttributeRequest"];
export type AttributeReplace = Schemas["ReplaceAttributeRequest"];
export type AttributeOption = Schemas["AttributeOption"];
export type AttributeOptionWrite = Schemas["CreateAttributeOptionRequest"];
export type AttributeOptionReplace = Schemas["ReplaceAttributeOptionRequest"];
export type AttributeValue = Schemas["AttributeValue"];
export type AttributeValues = Schemas["AttributeValues"];
export type AttributeValueType = Schemas["AttributeValueType"];
export type ProductListParams = NonNullable<
  operations["listProducts"]["parameters"]["query"]
>;

export interface CategoryListParams {
  parent_id?: string;
  root_only?: boolean;
}

const productPath = (productId: string) => `/api/v1/products/${productId}`;

function versionedConfig(etag: string): AxiosRequestConfig {
  return { headers: ifMatchHeaders(etag) };
}

export const productApi = {
  listProducts: async (
    params: ProductListParams,
    signal?: AbortSignal
  ): Promise<ProductPage> => {
    const response = await api.get<ProductPage>("/api/v1/products", { params, signal });
    return response.data;
  },

  createProduct: async (input: ProductWrite): Promise<Versioned<Product>> =>
    toVersioned(await api.post<Product>("/api/v1/products", input)),

  getProduct: (productId: string, signal?: AbortSignal): Promise<Versioned<Product>> =>
    getVersioned(productPath(productId), { signal }),

  replaceProduct: async (
    productId: string,
    etag: string,
    input: ProductReplace
  ): Promise<Versioned<Product>> =>
    toVersioned(
      await api.put<Product>(productPath(productId), input, versionedConfig(etag))
    ),

  changeProductStatus: async (
    productId: string,
    etag: string,
    input: ProductStatusWrite
  ): Promise<Versioned<Product>> =>
    toVersioned(
      await api.patch<Product>(`${productPath(productId)}/status`, input, versionedConfig(etag))
    ),

  deleteProduct: async (productId: string, etag: string): Promise<void> => {
    await api.delete(productPath(productId), versionedConfig(etag));
  },

  createSku: async (
    productId: string,
    etag: string,
    input: SkuWrite
  ): Promise<Versioned<Sku>> =>
    toVersioned(
      await api.post<Sku>(`${productPath(productId)}/skus`, input, versionedConfig(etag))
    ),

  replaceSku: async (
    productId: string,
    skuId: string,
    etag: string,
    input: SkuReplace
  ): Promise<Versioned<Sku>> =>
    toVersioned(
      await api.put<Sku>(
        `${productPath(productId)}/skus/${skuId}`,
        input,
        versionedConfig(etag)
      )
    ),

  deleteSku: async (productId: string, skuId: string, etag: string): Promise<string | null> => {
    const response = await api.delete(`${productPath(productId)}/skus/${skuId}`, versionedConfig(etag));
    return typeof response.headers.etag === "string" ? response.headers.etag : null;
  },

  createMedia: async (
    productId: string,
    etag: string,
    input: MediaWrite
  ): Promise<Versioned<Media>> =>
    toVersioned(
      await api.post<Media>(`${productPath(productId)}/media`, input, versionedConfig(etag))
    ),

  replaceMedia: async (
    productId: string,
    mediaId: string,
    etag: string,
    input: MediaReplace
  ): Promise<Versioned<Media>> =>
    toVersioned(
      await api.put<Media>(
        `${productPath(productId)}/media/${mediaId}`,
        input,
        versionedConfig(etag)
      )
    ),

  deleteMedia: async (
    productId: string,
    mediaId: string,
    etag: string
  ): Promise<string | null> => {
    const response = await api.delete(
      `${productPath(productId)}/media/${mediaId}`,
      versionedConfig(etag)
    );
    return typeof response.headers.etag === "string" ? response.headers.etag : null;
  },

  listCategories: async (
    params: CategoryListParams = {},
    signal?: AbortSignal
  ): Promise<Category[]> => {
    const response = await api.get<Schemas["CategoryList"]>("/api/v1/categories", {
      params,
      signal,
    });
    return response.data.items;
  },

  createCategory: async (input: CategoryWrite): Promise<Category> =>
    (await api.post<Category>("/api/v1/categories", input)).data,

  getCategory: async (categoryId: string, signal?: AbortSignal): Promise<Category> =>
    (await api.get<Category>(`/api/v1/categories/${categoryId}`, { signal })).data,

  replaceCategory: async (categoryId: string, input: CategoryReplace): Promise<Category> =>
    (await api.put<Category>(`/api/v1/categories/${categoryId}`, input)).data,

  deleteCategory: async (categoryId: string): Promise<void> => {
    await api.delete(`/api/v1/categories/${categoryId}`);
  },

  putCategoryAttribute: async (
    categoryId: string,
    attributeId: string,
    input: CategoryAttributeWrite
  ): Promise<CategoryAttribute> =>
    (
      await api.put<CategoryAttribute>(
        `/api/v1/categories/${categoryId}/attributes/${attributeId}`,
        input
      )
    ).data,

  deleteCategoryAttribute: async (
    categoryId: string,
    attributeId: string
  ): Promise<void> => {
    await api.delete(`/api/v1/categories/${categoryId}/attributes/${attributeId}`);
  },

  listAttributes: async (signal?: AbortSignal): Promise<Attribute[]> =>
    (await api.get<Schemas["AttributeList"]>("/api/v1/attributes", { signal })).data.items,

  createAttribute: async (input: AttributeWrite): Promise<Attribute> =>
    (await api.post<Attribute>("/api/v1/attributes", input)).data,

  getAttribute: async (attributeId: string, signal?: AbortSignal): Promise<Attribute> =>
    (await api.get<Attribute>(`/api/v1/attributes/${attributeId}`, { signal })).data,

  replaceAttribute: async (
    attributeId: string,
    input: AttributeReplace
  ): Promise<Attribute> =>
    (await api.put<Attribute>(`/api/v1/attributes/${attributeId}`, input)).data,

  deleteAttribute: async (attributeId: string): Promise<void> => {
    await api.delete(`/api/v1/attributes/${attributeId}`);
  },

  createAttributeOption: async (
    attributeId: string,
    input: AttributeOptionWrite
  ): Promise<AttributeOption> =>
    (await api.post<AttributeOption>(`/api/v1/attributes/${attributeId}/options`, input)).data,

  replaceAttributeOption: async (
    attributeId: string,
    optionId: string,
    input: AttributeOptionReplace
  ): Promise<AttributeOption> =>
    (
      await api.put<AttributeOption>(
        `/api/v1/attributes/${attributeId}/options/${optionId}`,
        input
      )
    ).data,

  deleteAttributeOption: async (attributeId: string, optionId: string): Promise<void> => {
    await api.delete(`/api/v1/attributes/${attributeId}/options/${optionId}`);
  },
};
