"use client";

import { useMemo, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { LoaderCircle, Plus, Trash2 } from "lucide-react";

import { AttributeValueFields } from "@/components/admin/catalog/attribute-value-fields";
import { FormSection } from "@/components/admin/form-section";
import { MoneyInput } from "@/components/admin/money-input";
import { PageHeader } from "@/components/admin/page-header";
import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { ErrorState } from "@/components/ui/error-state";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { NativeSelect } from "@/components/ui/native-select";
import { Textarea } from "@/components/ui/textarea";
import { useSubmitError } from "@/hooks/use-submit-error";
import {
  flattenCategoryTree,
  missingRequiredAttributes,
  normalizeAttributeValues,
  slugifyCatalogName,
} from "@/lib/catalog";
import { isRetryable } from "@/lib/api/problem";
import {
  productApi,
  type AttributeValue,
  type SkuStatus,
} from "@/lib/api/product";
import { SKU_STATUS_LABELS } from "@/lib/labels/product";

interface SkuDraft {
  key: string;
  skuCode: string;
  basePriceVnd?: number;
  status: SkuStatus;
  variantAttributes: Record<string, AttributeValue | "">;
}

function newSku(): SkuDraft {
  return {
    key: crypto.randomUUID(),
    skuCode: "",
    basePriceVnd: undefined,
    status: "ACTIVE",
    variantAttributes: {},
  };
}

export function ProductCreateForm() {
  const router = useRouter();
  const queryClient = useQueryClient();
  const submitError = useSubmitError();
  const [validationMessage, setValidationMessage] = useState<string | null>(null);
  const [uncertain, setUncertain] = useState(false);
  const [name, setName] = useState("");
  const [slug, setSlug] = useState("");
  const [slugEdited, setSlugEdited] = useState(false);
  const [description, setDescription] = useState("");
  const [categoryId, setCategoryId] = useState("");
  const [attributes, setAttributes] = useState<Record<string, AttributeValue | "">>({});
  const [skus, setSkus] = useState<SkuDraft[]>(() => [newSku()]);

  const categoriesQuery = useQuery({
    queryKey: ["product", "categories"],
    queryFn: ({ signal }) => productApi.listCategories({}, signal),
  });
  const attributesQuery = useQuery({
    queryKey: ["product", "attributes"],
    queryFn: ({ signal }) => productApi.listAttributes(signal),
  });
  const tree = useMemo(() => flattenCategoryTree(categoriesQuery.data ?? []), [categoriesQuery.data]);
  const selectedCategory = (categoriesQuery.data ?? []).find((category) => category.category_id === categoryId);
  const productMappings = (selectedCategory?.attributes ?? []).filter((mapping) => !mapping.is_variant);
  const variantMappings = (selectedCategory?.attributes ?? []).filter((mapping) => mapping.is_variant);

  const create = useMutation({
    mutationFn: productApi.createProduct,
    onSuccess: async (result) => {
      queryClient.setQueryData(["product", "detail", result.data.product_id], result);
      await queryClient.invalidateQueries({ queryKey: ["product", "products"] });
      router.replace(`/admin/products/${result.data.product_id}`);
    },
  });

  function updateSku(key: string, patch: Partial<SkuDraft>) {
    setSkus((current) => current.map((sku) => (sku.key === key ? { ...sku, ...patch } : sku)));
  }

  function validate(): string | null {
    if (!name.trim() || !slug.trim() || !selectedCategory) return "Vui lòng nhập đủ tên, slug và danh mục.";
    const selectedTreeItem = tree.find(({ category }) => category.category_id === selectedCategory.category_id);
    if (!selectedTreeItem?.isLeaf) return "Sản phẩm chỉ được gán vào danh mục cấp cuối.";
    const missingProduct = missingRequiredAttributes(productMappings, attributes);
    if (missingProduct.length) return `Vui lòng nhập thuộc tính bắt buộc: ${missingProduct.join(", ")}.`;
    if (skus.length === 0) return "Mỗi sản phẩm cần ít nhất một SKU.";
    const codes = new Set<string>();
    for (const [index, sku] of skus.entries()) {
      if (!sku.skuCode.trim()) return `Vui lòng nhập mã cho SKU ${index + 1}.`;
      if (codes.has(sku.skuCode.trim())) return `Mã SKU ${sku.skuCode.trim()} đang bị lặp trong form.`;
      codes.add(sku.skuCode.trim());
      if (!sku.basePriceVnd || sku.basePriceVnd < 1) return `Giá của SKU ${sku.skuCode || index + 1} phải lớn hơn 0.`;
      const missingVariant = missingRequiredAttributes(variantMappings, sku.variantAttributes);
      if (missingVariant.length) return `SKU ${sku.skuCode || index + 1} còn thiếu: ${missingVariant.join(", ")}.`;
    }
    return null;
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    submitError.reset();
    setUncertain(false);
    const invalid = validate();
    setValidationMessage(invalid);
    if (invalid || !selectedCategory) return;

    try {
      await create.mutateAsync({
        category_id: selectedCategory.category_id,
        name: name.trim(),
        slug: slug.trim(),
        description: description.trim() || null,
        attributes: normalizeAttributeValues(productMappings, attributes),
        skus: skus.map((sku) => ({
          sku_code: sku.skuCode.trim(),
          base_price_vnd: sku.basePriceVnd ?? 0,
          status: sku.status,
          variant_attributes: normalizeAttributeValues(variantMappings, sku.variantAttributes),
        })),
      });
    } catch (error) {
      submitError.capture(error);
      setUncertain(isRetryable(error));
    }
  }

  const metadataError = categoriesQuery.error ?? attributesQuery.error;

  return (
    <form onSubmit={submit} className="space-y-8">
      <PageHeader
        title="Tạo sản phẩm"
        description="Sản phẩm mới luôn ở trạng thái Nháp và phải có ít nhất một SKU."
      />

      {metadataError ? (
        <ErrorState
          error={metadataError}
          onRetry={() => {
            void categoriesQuery.refetch();
            void attributesQuery.refetch();
          }}
          message="Không tải được cấu hình catalog. Vui lòng thử lại trước khi tạo sản phẩm."
        />
      ) : null}
      {validationMessage ? <Alert variant="danger">{validationMessage}</Alert> : null}
      {submitError.message ? <ErrorState error={create.error} message={submitError.message} /> : null}
      {uncertain ? (
        <Alert variant="warning" title="Chưa xác định lệnh tạo đã thành công hay chưa">
          Product Service không hỗ trợ idempotency cho lệnh này. Không gửi lại ngay; hãy mở danh sách sản phẩm và kiểm tra slug trước để tránh tạo trùng.
          <div className="mt-3"><Button variant="outline" size="sm" render={<Link href="/admin/products" />}>Về danh sách kiểm tra</Button></div>
        </Alert>
      ) : null}

      <FormSection title="Thông tin chung" description="Tên và slug sẽ được dùng trong catalog cho người mua.">
        <div className="grid gap-5 sm:grid-cols-2">
          <div className="space-y-2">
            <Label htmlFor="product-name">Tên sản phẩm *</Label>
            <Input
              id="product-name"
              value={name}
              required
              maxLength={255}
              onChange={(event) => {
                setName(event.target.value);
                if (!slugEdited) setSlug(slugifyCatalogName(event.target.value));
              }}
            />
          </div>
          <div className="space-y-2">
            <Label htmlFor="product-slug">Slug *</Label>
            <Input
              id="product-slug"
              value={slug}
              required
              maxLength={160}
              pattern="[a-z0-9]+(?:-[a-z0-9]+)*"
              onChange={(event) => { setSlugEdited(true); setSlug(event.target.value.toLowerCase()); }}
            />
          </div>
        </div>
        <div className="space-y-2">
          <Label htmlFor="product-category">Danh mục cấp cuối *</Label>
          <NativeSelect
            id="product-category"
            value={categoryId}
            required
            disabled={categoriesQuery.isPending || categoriesQuery.isError}
            onChange={(event) => {
              setCategoryId(event.target.value);
              setAttributes({});
              setSkus((current) => current.map((sku) => ({ ...sku, variantAttributes: {} })));
            }}
          >
            <option value="">Chọn danh mục</option>
            {tree.map(({ category, depth, isLeaf }) => (
              <option key={category.category_id} value={category.category_id} disabled={!isLeaf}>
                {`${"— ".repeat(depth)}${category.name}${isLeaf ? "" : " (nhóm)"}`}
              </option>
            ))}
          </NativeSelect>
        </div>
        <div className="space-y-2">
          <Label htmlFor="product-description">Mô tả</Label>
          <Textarea id="product-description" value={description} onChange={(event) => setDescription(event.target.value)} />
        </div>
      </FormSection>

      <FormSection title="Thuộc tính sản phẩm" description="Các field xuất hiện theo cấu hình của danh mục đã chọn.">
        <AttributeValueFields
          mappings={productMappings}
          attributes={attributesQuery.data ?? []}
          values={attributes}
          disabled={!selectedCategory || attributesQuery.isPending}
          emptyMessage={selectedCategory ? "Danh mục này không có thuộc tính cấp sản phẩm." : "Chọn danh mục để xem thuộc tính."}
          onChange={(code, value) => setAttributes((current) => ({ ...current, [code]: value }))}
        />
      </FormSection>

      <section className="space-y-5">
        <div className="flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
          <div className="space-y-1">
            <h2 className="text-headline-5 font-serif font-normal">SKU</h2>
            <p className="text-[14px] text-muted-foreground">Giá là số nguyên VND; Product Service tự tính giá bán sau markdown.</p>
          </div>
          <Button type="button" variant="outline" onClick={() => setSkus((current) => [...current, newSku()])}>
            <Plus aria-hidden />Thêm SKU
          </Button>
        </div>
        <div className="space-y-5">
          {skus.map((sku, index) => (
            <FormSection key={sku.key} title={`SKU ${index + 1}`}>
              <div className="grid gap-5 sm:grid-cols-3">
                <div className="space-y-2">
                  <Label htmlFor={`sku-code-${sku.key}`}>Mã SKU *</Label>
                  <Input id={`sku-code-${sku.key}`} value={sku.skuCode} required maxLength={64} onChange={(event) => updateSku(sku.key, { skuCode: event.target.value })} />
                </div>
                <div className="space-y-2">
                  <Label htmlFor={`sku-price-${sku.key}`}>Giá gốc *</Label>
                  <MoneyInput id={`sku-price-${sku.key}`} value={sku.basePriceVnd} required min={1} onValueChange={(value) => updateSku(sku.key, { basePriceVnd: value })} />
                </div>
                <div className="space-y-2">
                  <Label htmlFor={`sku-status-${sku.key}`}>Trạng thái *</Label>
                  <NativeSelect id={`sku-status-${sku.key}`} value={sku.status} onChange={(event) => updateSku(sku.key, { status: event.target.value as SkuStatus })}>
                    {(Object.keys(SKU_STATUS_LABELS) as SkuStatus[]).map((status) => <option key={status} value={status}>{SKU_STATUS_LABELS[status].text}</option>)}
                  </NativeSelect>
                </div>
              </div>
              <AttributeValueFields
                mappings={variantMappings}
                attributes={attributesQuery.data ?? []}
                values={sku.variantAttributes}
                emptyMessage={selectedCategory ? "Danh mục này không có trục biến thể." : "Chọn danh mục để xem trục biến thể."}
                onChange={(code, value) => updateSku(sku.key, { variantAttributes: { ...sku.variantAttributes, [code]: value } })}
              />
              <div className="flex justify-end border-t border-border pt-4">
                <Button type="button" variant="ghost" disabled={skus.length === 1} onClick={() => setSkus((current) => current.filter((item) => item.key !== sku.key))}>
                  <Trash2 aria-hidden />Bỏ SKU
                </Button>
              </div>
            </FormSection>
          ))}
        </div>
      </section>

      <div className="flex flex-col-reverse gap-3 border-t border-border pt-6 sm:flex-row sm:justify-end">
        <Button type="button" variant="secondary" render={<Link href="/admin/products" />}>Huỷ</Button>
        <Button type="submit" disabled={create.isPending || Boolean(metadataError) || uncertain}>
          {create.isPending ? <LoaderCircle className="animate-spin" aria-hidden /> : null}
          {create.isPending ? "Đang tạo" : "Tạo sản phẩm nháp"}
        </Button>
      </div>
    </form>
  );
}
