"use client";

import { useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Eye, EyeOff, ImageIcon, LoaderCircle, Pencil, Plus, Trash2 } from "lucide-react";

import { AttributeValueFields } from "@/components/admin/catalog/attribute-value-fields";
import { ConfirmDialog } from "@/components/admin/confirm-dialog";
import { FormSection } from "@/components/admin/form-section";
import { IdChip } from "@/components/admin/id-chip";
import { MoneyInput } from "@/components/admin/money-input";
import { PageHeader } from "@/components/admin/page-header";
import { StatusBadge } from "@/components/admin/status-badge";
import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { EmptyState } from "@/components/ui/empty-state";
import { ErrorState } from "@/components/ui/error-state";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { NativeSelect } from "@/components/ui/native-select";
import { Skeleton } from "@/components/ui/skeleton";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Textarea } from "@/components/ui/textarea";
import { useToast } from "@/components/ui/toast";
import { useSubmitError } from "@/hooks/use-submit-error";
import {
  flattenCategoryTree,
  missingRequiredAttributes,
  normalizeAttributeValues,
} from "@/lib/catalog";
import { isStaleVersion, type Versioned } from "@/lib/api/etag";
import {
  productApi,
  type Attribute,
  type AttributeValue,
  type Category,
  type Media,
  type MediaType,
  type Product,
  type Sku,
  type SkuStatus,
} from "@/lib/api/product";
import { formatDateTime, formatVND } from "@/lib/format";
import {
  MEDIA_TYPE_LABELS,
  PRODUCT_STATUS_LABELS,
  SKU_STATUS_LABELS,
} from "@/lib/labels/product";

const productKey = (productId: string) => ["product", "detail", productId] as const;

export function ProductDetail({ productId }: { productId: string }) {
  const router = useRouter();
  const queryClient = useQueryClient();
  const { addToast } = useToast();
  const [deleteOpen, setDeleteOpen] = useState(false);

  const productQuery = useQuery({
    queryKey: productKey(productId),
    queryFn: ({ signal }) => productApi.getProduct(productId, signal),
  });
  const categoriesQuery = useQuery({
    queryKey: ["product", "categories"],
    queryFn: ({ signal }) => productApi.listCategories({}, signal),
  });
  const attributesQuery = useQuery({
    queryKey: ["product", "attributes"],
    queryFn: ({ signal }) => productApi.listAttributes(signal),
  });

  const remove = useMutation({
    mutationFn: async () => {
      const current = productQuery.data;
      if (!current?.etag) throw new Error("MISSING_ETAG");
      await productApi.deleteProduct(productId, current.etag);
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["product", "products"] });
      addToast({ type: "success", title: "Đã xoá sản phẩm" });
      router.replace("/admin/products");
    },
  });

  if (productQuery.isPending) return <ProductDetailSkeleton />;
  if (productQuery.isError || !productQuery.data) {
    return <ErrorState error={productQuery.error} onRetry={() => void productQuery.refetch()} />;
  }

  const versioned = productQuery.data;
  const product = versioned.data;
  const metadataError = categoriesQuery.error ?? attributesQuery.error;

  async function refresh() {
    await productQuery.refetch();
  }

  function acceptProduct(next: Versioned<Product>) {
    queryClient.setQueryData(productKey(productId), next);
    void queryClient.invalidateQueries({ queryKey: ["product", "products"] });
  }

  return (
    <div className="space-y-8">
      <PageHeader
        title={product.name}
        description={`Revision ${product.catalog_revision} · cập nhật ${formatDateTime(product.updated_at)}`}
        action={<Button variant="destructive" onClick={() => setDeleteOpen(true)}><Trash2 aria-hidden />Xoá sản phẩm</Button>}
      />

      <div className="flex flex-wrap items-center gap-3">
        <StatusBadge value={product.status} labels={PRODUCT_STATUS_LABELS} />
        <IdChip value={product.product_id} label="Sao chép mã sản phẩm" />
        {!versioned.etag ? (
          <Alert variant="warning">Response không có ETag nên các lệnh sửa đang bị khoá. Hãy tải lại dữ liệu.</Alert>
        ) : null}
      </div>

      {metadataError ? (
        <ErrorState
          error={metadataError}
          onRetry={() => { void categoriesQuery.refetch(); void attributesQuery.refetch(); }}
          message="Một phần cấu hình category/attribute chưa tải được. Các tab còn lại vẫn dùng được."
        />
      ) : null}

      <Tabs defaultValue="information">
        <TabsList aria-label="Nội dung sản phẩm">
          <TabsTrigger value="information">Thông tin</TabsTrigger>
          <TabsTrigger value="skus">SKU ({product.skus.length})</TabsTrigger>
          <TabsTrigger value="media">Media</TabsTrigger>
          <TabsTrigger value="status">Trạng thái</TabsTrigger>
        </TabsList>
        <TabsContent value="information">
          <ProductInformation
            versioned={versioned}
            categories={categoriesQuery.data ?? []}
            attributes={attributesQuery.data ?? []}
            metadataReady={!metadataError && !categoriesQuery.isPending && !attributesQuery.isPending}
            onSaved={acceptProduct}
            onReload={refresh}
          />
        </TabsContent>
        <TabsContent value="skus">
          <SkuManager
            versioned={versioned}
            category={(categoriesQuery.data ?? []).find((item) => item.category_id === product.category_id)}
            attributes={attributesQuery.data ?? []}
            onReload={refresh}
          />
        </TabsContent>
        <TabsContent value="media">
          <MediaManager versioned={versioned} onReload={refresh} />
        </TabsContent>
        <TabsContent value="status">
          <ProductLifecycle versioned={versioned} onSaved={acceptProduct} onReload={refresh} />
        </TabsContent>
      </Tabs>

      <ConfirmDialog
        open={deleteOpen}
        onOpenChange={setDeleteOpen}
        title="Xoá vĩnh viễn sản phẩm?"
        description="Product, toàn bộ SKU và media liên quan sẽ bị xoá. Hành động này không thể hoàn tác."
        confirmLabel="Xoá sản phẩm"
        expectedText={product.name}
        onConfirm={async () => remove.mutateAsync()}
      />
    </div>
  );
}

function ProductInformation({
  versioned,
  categories,
  attributes: attributeDefinitions,
  metadataReady,
  onSaved,
  onReload,
}: {
  versioned: Versioned<Product>;
  categories: Category[];
  attributes: Attribute[];
  metadataReady: boolean;
  onSaved: (product: Versioned<Product>) => void;
  onReload: () => Promise<void>;
}) {
  const submitError = useSubmitError();
  const { addToast } = useToast();
  const [stale, setStale] = useState(false);
  const [name, setName] = useState(versioned.data.name);
  const [slug, setSlug] = useState(versioned.data.slug);
  const [description, setDescription] = useState(versioned.data.description ?? "");
  const [categoryId, setCategoryId] = useState(versioned.data.category_id);
  const [attributeValues, setAttributeValues] = useState<Record<string, AttributeValue | "">>(versioned.data.attributes);
  const [validationMessage, setValidationMessage] = useState<string | null>(null);
  const [formSource, setFormSource] = useState(versioned);

  if (versioned !== formSource) {
    setFormSource(versioned);
    setName(versioned.data.name);
    setSlug(versioned.data.slug);
    setDescription(versioned.data.description ?? "");
    setCategoryId(versioned.data.category_id);
    setAttributeValues(versioned.data.attributes);
    setStale(false);
  }

  const tree = useMemo(() => flattenCategoryTree(categories), [categories]);
  const selectedCategory = categories.find((category) => category.category_id === categoryId);
  const mappings = (selectedCategory?.attributes ?? []).filter((mapping) => !mapping.is_variant);
  const mutation = useMutation({
    mutationFn: () => {
      if (!versioned.etag) throw new Error("MISSING_ETAG");
      return productApi.replaceProduct(versioned.data.product_id, versioned.etag, {
        category_id: categoryId,
        name: name.trim(),
        slug: slug.trim(),
        description: description.trim() || null,
        attributes: normalizeAttributeValues(mappings, attributeValues),
      });
    },
    onSuccess: (product) => {
      onSaved(product);
      addToast({ type: "success", title: "Đã lưu thông tin sản phẩm" });
    },
  });

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setValidationMessage(null);
    submitError.reset();
    const leaf = tree.find(({ category }) => category.category_id === categoryId)?.isLeaf;
    const missing = missingRequiredAttributes(mappings, attributeValues);
    if (!name.trim() || !slug.trim() || !selectedCategory) return setValidationMessage("Vui lòng nhập đủ tên, slug và danh mục.");
    if (!leaf) return setValidationMessage("Sản phẩm chỉ được gán vào danh mục cấp cuối.");
    if (missing.length) return setValidationMessage(`Vui lòng nhập thuộc tính bắt buộc: ${missing.join(", ")}.`);
    try {
      await mutation.mutateAsync();
    } catch (error) {
      setStale(isStaleVersion(error));
      submitError.capture(error);
    }
  }

  const dirty = name !== versioned.data.name || slug !== versioned.data.slug || description !== (versioned.data.description ?? "") || categoryId !== versioned.data.category_id || JSON.stringify(attributeValues) !== JSON.stringify(versioned.data.attributes);

  return (
    <form onSubmit={submit} className="space-y-6">
      {validationMessage ? <Alert variant="danger">{validationMessage}</Alert> : null}
      {submitError.message ? <ErrorState error={mutation.error} message={submitError.message} /> : null}
      {stale ? <StaleAlert onReload={onReload} /> : null}
      <FormSection title="Thông tin chung">
        <div className="grid gap-5 sm:grid-cols-2">
          <div className="space-y-2"><Label htmlFor="edit-product-name">Tên sản phẩm *</Label><Input id="edit-product-name" value={name} required maxLength={255} onChange={(event) => setName(event.target.value)} /></div>
          <div className="space-y-2"><Label htmlFor="edit-product-slug">Slug *</Label><Input id="edit-product-slug" value={slug} required maxLength={160} pattern="[a-z0-9]+(?:-[a-z0-9]+)*" onChange={(event) => setSlug(event.target.value.toLowerCase())} /></div>
        </div>
        <div className="space-y-2">
          <Label htmlFor="edit-product-category">Danh mục cấp cuối *</Label>
          <NativeSelect id="edit-product-category" value={categoryId} disabled={!metadataReady} onChange={(event) => { setCategoryId(event.target.value); setAttributeValues({}); }}>
            {tree.map(({ category, depth, isLeaf }) => <option key={category.category_id} value={category.category_id} disabled={!isLeaf}>{`${"— ".repeat(depth)}${category.name}${isLeaf ? "" : " (nhóm)"}`}</option>)}
          </NativeSelect>
        </div>
        <div className="space-y-2"><Label htmlFor="edit-product-description">Mô tả</Label><Textarea id="edit-product-description" value={description} onChange={(event) => setDescription(event.target.value)} /></div>
      </FormSection>
      <FormSection title="Thuộc tính sản phẩm">
        <AttributeValueFields
          mappings={mappings}
          attributes={attributeDefinitions}
          values={attributeValues}
          disabled={!metadataReady}
          emptyMessage="Danh mục này không có thuộc tính cấp sản phẩm."
          onChange={(code, value) => setAttributeValues((current) => ({ ...current, [code]: value }))}
        />
      </FormSection>
      <div className="flex justify-end gap-3 border-t border-border pt-5">
        <Button type="button" variant="secondary" disabled={!dirty || mutation.isPending} onClick={() => { setName(versioned.data.name); setSlug(versioned.data.slug); setDescription(versioned.data.description ?? ""); setCategoryId(versioned.data.category_id); setAttributeValues(versioned.data.attributes); }}>Hoàn tác</Button>
        <Button type="submit" disabled={!dirty || mutation.isPending || !versioned.etag || !metadataReady || stale}>{mutation.isPending ? <LoaderCircle className="animate-spin" aria-hidden /> : null}{mutation.isPending ? "Đang lưu" : "Lưu thay đổi"}</Button>
      </div>
    </form>
  );
}

function SkuManager({
  versioned,
  category,
  attributes,
  onReload,
}: {
  versioned: Versioned<Product>;
  category?: Category;
  attributes: Attribute[];
  onReload: () => Promise<void>;
}) {
  const { addToast } = useToast();
  const submitError = useSubmitError();
  const [dialogOpen, setDialogOpen] = useState(false);
  const [editing, setEditing] = useState<Sku | null>(null);
  const [deleting, setDeleting] = useState<Sku | null>(null);
  const [stale, setStale] = useState(false);
  const remove = useMutation({
    mutationFn: async (skuId: string) => {
      if (!versioned.etag) throw new Error("MISSING_ETAG");
      await productApi.deleteSku(versioned.data.product_id, skuId, versioned.etag);
    },
    onSuccess: async () => {
      await onReload();
      addToast({ type: "success", title: "Đã xoá SKU" });
      setDeleting(null);
    },
  });

  const variantMappings = (category?.attributes ?? []).filter((mapping) => mapping.is_variant);
  return (
    <div className="space-y-5">
      {stale ? <StaleAlert onReload={onReload} /> : null}
      {submitError.message ? <ErrorState error={remove.error} message={submitError.message} /> : null}
      <div className="flex justify-end"><Button variant="outline" disabled={!versioned.etag || !category} onClick={() => { setEditing(null); setDialogOpen(true); }}><Plus aria-hidden />Thêm SKU</Button></div>
      <div className="grid gap-4 lg:grid-cols-2">
        {versioned.data.skus.map((sku) => (
          <div key={sku.sku_id} className="rounded-xl border border-border bg-surface-overlay p-6">
            <div className="flex items-start justify-between gap-4">
              <div className="min-w-0"><h2 className="truncate text-headline-6 font-serif font-normal">{sku.sku_code}</h2><div className="mt-2"><IdChip value={sku.sku_id} label="Sao chép mã SKU" /></div></div>
              <StatusBadge value={sku.status} labels={SKU_STATUS_LABELS} />
            </div>
            <dl className="mt-5 grid grid-cols-2 gap-4 text-[14px]">
              <div><dt className="text-caption text-muted-foreground">Giá gốc</dt><dd className="mt-1 font-mono tabular-nums">{formatVND(sku.base_price_vnd)}</dd></div>
              <div><dt className="text-caption text-muted-foreground">Giá đang bán</dt><dd className="mt-1 font-mono tabular-nums">{formatVND(sku.selling_price_vnd)}</dd></div>
              <div className="col-span-2"><dt className="text-caption text-muted-foreground">Biến thể</dt><dd className="mt-1 font-mono text-caption">{Object.entries(sku.variant_attributes).map(([key, value]) => `${key}: ${String(value)}`).join(" · ") || "Không có"}</dd></div>
            </dl>
            <div className="mt-5 flex justify-end gap-2 border-t border-border pt-4">
              <Button variant="ghost" size="sm" onClick={() => { setEditing(sku); setDialogOpen(true); }}><Pencil aria-hidden />Sửa</Button>
              <Button variant="ghost" size="sm" disabled={versioned.data.skus.length === 1} onClick={() => setDeleting(sku)}><Trash2 aria-hidden />Xoá</Button>
            </div>
          </div>
        ))}
      </div>
      {dialogOpen ? (
        <SkuDialog
          key={editing?.sku_id ?? "new"}
          onOpenChange={setDialogOpen}
          productId={versioned.data.product_id}
          etag={versioned.etag}
          sku={editing}
          mappings={variantMappings}
          attributes={attributes}
          onSaved={onReload}
          onStale={() => setStale(true)}
        />
      ) : null}
      <ConfirmDialog
        open={Boolean(deleting)}
        onOpenChange={(open) => { if (!open) setDeleting(null); }}
        title="Xoá vĩnh viễn SKU?"
        description="Media gắn riêng với SKU cũng bị xoá. Không thể xoá SKU cuối cùng của sản phẩm."
        confirmLabel="Xoá SKU"
        expectedText={deleting?.sku_code}
        onConfirm={async () => {
          if (!deleting) return;
          try { await remove.mutateAsync(deleting.sku_id); } catch (error) { setStale(isStaleVersion(error)); submitError.capture(error); throw error; }
        }}
      />
    </div>
  );
}

function SkuDialog({
  onOpenChange,
  productId,
  etag,
  sku,
  mappings,
  attributes,
  onSaved,
  onStale,
}: {
  onOpenChange: (open: boolean) => void;
  productId: string;
  etag: string | null;
  sku: Sku | null;
  mappings: Category["attributes"];
  attributes: Attribute[];
  onSaved: () => Promise<void>;
  onStale: () => void;
}) {
  const { addToast } = useToast();
  const submitError = useSubmitError();
  const [skuCode, setSkuCode] = useState(sku?.sku_code ?? "");
  const [price, setPrice] = useState<number | undefined>(sku?.base_price_vnd);
  const [status, setStatus] = useState<SkuStatus>(sku?.status ?? "ACTIVE");
  const [values, setValues] = useState<Record<string, AttributeValue | "">>(sku?.variant_attributes ?? {});
  const [validation, setValidation] = useState<string | null>(null);

  const mutation = useMutation({
    mutationFn: () => {
      if (!etag) throw new Error("MISSING_ETAG");
      const input = { sku_code: skuCode.trim(), base_price_vnd: price ?? 0, status, variant_attributes: normalizeAttributeValues(mappings, values) };
      return sku ? productApi.replaceSku(productId, sku.sku_id, etag, input) : productApi.createSku(productId, etag, input);
    },
    onSuccess: async () => {
      await onSaved();
      addToast({ type: "success", title: sku ? "Đã lưu SKU" : "Đã thêm SKU" });
      onOpenChange(false);
    },
  });

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    submitError.reset();
    const missing = missingRequiredAttributes(mappings, values);
    const invalid = !skuCode.trim() ? "Vui lòng nhập mã SKU." : !price || price < 1 ? "Giá SKU phải lớn hơn 0." : missing.length ? `Vui lòng nhập: ${missing.join(", ")}.` : null;
    setValidation(invalid);
    if (invalid) return;
    try { await mutation.mutateAsync(); } catch (error) { if (isStaleVersion(error)) onStale(); submitError.capture(error); }
  }

  return (
    <Dialog open onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl">
        <DialogHeader><DialogTitle>{sku ? "Sửa SKU" : "Thêm SKU"}</DialogTitle><DialogDescription>Giá bán sau markdown là dữ liệu chỉ đọc do Product Service tính.</DialogDescription></DialogHeader>
        <form onSubmit={submit} className="mt-6 space-y-5">
          {validation ? <Alert variant="danger">{validation}</Alert> : null}
          {submitError.message ? <ErrorState error={mutation.error} message={submitError.message} /> : null}
          <div className="grid gap-5 sm:grid-cols-3">
            <div className="space-y-2"><Label htmlFor="sku-code">Mã SKU *</Label><Input id="sku-code" value={skuCode} required maxLength={64} onChange={(event) => setSkuCode(event.target.value)} /></div>
            <div className="space-y-2"><Label htmlFor="sku-price">Giá gốc *</Label><MoneyInput id="sku-price" value={price} required min={1} onValueChange={setPrice} /></div>
            <div className="space-y-2"><Label htmlFor="sku-status">Trạng thái *</Label><NativeSelect id="sku-status" value={status} onChange={(event) => setStatus(event.target.value as SkuStatus)}>{(Object.keys(SKU_STATUS_LABELS) as SkuStatus[]).map((value) => <option key={value} value={value}>{SKU_STATUS_LABELS[value].text}</option>)}</NativeSelect></div>
          </div>
          <AttributeValueFields mappings={mappings} attributes={attributes} values={values} emptyMessage="Danh mục này không có trục biến thể." onChange={(code, value) => setValues((current) => ({ ...current, [code]: value }))} />
          <DialogFooter><Button type="button" variant="secondary" disabled={mutation.isPending} onClick={() => onOpenChange(false)}>Huỷ</Button><Button type="submit" disabled={mutation.isPending || !etag}>{mutation.isPending ? <LoaderCircle className="animate-spin" aria-hidden /> : null}{mutation.isPending ? "Đang lưu" : "Lưu SKU"}</Button></DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

function MediaManager({ versioned, onReload }: { versioned: Versioned<Product>; onReload: () => Promise<void> }) {
  const { addToast } = useToast();
  const submitError = useSubmitError();
  const [dialogOpen, setDialogOpen] = useState(false);
  const [editing, setEditing] = useState<Media | null>(null);
  const [deleting, setDeleting] = useState<Media | null>(null);
  const [stale, setStale] = useState(false);
  const media = [...versioned.data.media, ...versioned.data.skus.flatMap((sku) => sku.media)].sort((left, right) => left.sort_order - right.sort_order);
  const remove = useMutation({
    mutationFn: async (mediaId: string) => {
      if (!versioned.etag) throw new Error("MISSING_ETAG");
      await productApi.deleteMedia(versioned.data.product_id, mediaId, versioned.etag);
    },
    onSuccess: async () => { await onReload(); addToast({ type: "success", title: "Đã xoá media" }); setDeleting(null); },
  });

  return (
    <div className="space-y-5">
      <Alert variant="info">Product Service chưa có API tải file. Hãy tải file qua quy trình lưu trữ hiện có rồi nhập <span className="font-mono">storage_key</span> tại đây.</Alert>
      {stale ? <StaleAlert onReload={onReload} /> : null}
      {submitError.message ? <ErrorState error={remove.error} message={submitError.message} /> : null}
      <div className="flex justify-end"><Button variant="outline" disabled={!versioned.etag} onClick={() => { setEditing(null); setDialogOpen(true); }}><Plus aria-hidden />Thêm media</Button></div>
      {media.length === 0 ? (
        <EmptyState icon={ImageIcon} title="Chưa có media" description="Thêm ảnh hoặc video cấp sản phẩm, hoặc gắn riêng với một SKU." action={<Button variant="outline" size="sm" onClick={() => setDialogOpen(true)}>Thêm media</Button>} />
      ) : (
        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
          {media.map((item) => (
            <div key={item.media_id} className="rounded-xl border border-border bg-surface-overlay p-5">
              <div className="flex items-start justify-between gap-4"><StatusBadge value={item.media_type} labels={MEDIA_TYPE_LABELS} /><span className="text-caption text-muted-foreground">Thứ tự {item.sort_order}</span></div>
              <p className="mt-4 break-all font-mono text-caption">{item.storage_key}</p>
              <p className="mt-2 text-caption text-muted-foreground">{item.sku_id ? `SKU: ${versioned.data.skus.find((sku) => sku.sku_id === item.sku_id)?.sku_code ?? item.sku_id}` : "Cấp sản phẩm"}</p>
              {item.alt_text ? <p className="mt-2 text-[14px] text-muted-foreground">Alt: {item.alt_text}</p> : null}
              <div className="mt-4 flex justify-end gap-2 border-t border-border pt-4"><Button variant="ghost" size="icon" aria-label="Sửa media" onClick={() => { setEditing(item); setDialogOpen(true); }}><Pencil aria-hidden /></Button><Button variant="ghost" size="icon" aria-label="Xoá media" onClick={() => setDeleting(item)}><Trash2 aria-hidden /></Button></div>
            </div>
          ))}
        </div>
      )}
      {dialogOpen ? (
        <MediaDialog
          key={editing?.media_id ?? "new"}
          onOpenChange={setDialogOpen}
          versioned={versioned}
          media={editing}
          onSaved={onReload}
          onStale={() => setStale(true)}
        />
      ) : null}
      <ConfirmDialog
        open={Boolean(deleting)}
        onOpenChange={(open) => { if (!open) setDeleting(null); }}
        title="Xoá media?"
        description="Bản ghi media sẽ bị xoá khỏi Product; file trong storage không được Product Service quản lý."
        confirmLabel="Xoá media"
        expectedText={deleting?.storage_key}
        onConfirm={async () => { if (!deleting) return; try { await remove.mutateAsync(deleting.media_id); } catch (error) { setStale(isStaleVersion(error)); submitError.capture(error); throw error; } }}
      />
    </div>
  );
}

function MediaDialog({
  onOpenChange,
  versioned,
  media,
  onSaved,
  onStale,
}: {
  onOpenChange: (open: boolean) => void;
  versioned: Versioned<Product>;
  media: Media | null;
  onSaved: () => Promise<void>;
  onStale: () => void;
}) {
  const { addToast } = useToast();
  const submitError = useSubmitError();
  const [skuId, setSkuId] = useState(media?.sku_id ?? "");
  const [mediaType, setMediaType] = useState<MediaType>(media?.media_type ?? "IMAGE");
  const [storageKey, setStorageKey] = useState(media?.storage_key ?? "");
  const [altText, setAltText] = useState(media?.alt_text ?? "");
  const [sortOrder, setSortOrder] = useState(media?.sort_order ?? 0);

  const mutation = useMutation({
    mutationFn: () => {
      if (!versioned.etag) throw new Error("MISSING_ETAG");
      const input = { sku_id: skuId || null, media_type: mediaType, storage_key: storageKey.trim(), alt_text: altText.trim() || null, sort_order: sortOrder };
      return media ? productApi.replaceMedia(versioned.data.product_id, media.media_id, versioned.etag, input) : productApi.createMedia(versioned.data.product_id, versioned.etag, input);
    },
    onSuccess: async () => { await onSaved(); addToast({ type: "success", title: media ? "Đã lưu media" : "Đã thêm media" }); onOpenChange(false); },
  });

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    submitError.reset();
    try { await mutation.mutateAsync(); } catch (error) { if (isStaleVersion(error)) onStale(); submitError.capture(error); }
  }

  return (
    <Dialog open onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader><DialogTitle>{media ? "Sửa media" : "Thêm media"}</DialogTitle><DialogDescription>Nhập metadata của file đã có trong storage.</DialogDescription></DialogHeader>
        <form onSubmit={submit} className="mt-6 space-y-5">
          {submitError.message ? <ErrorState error={mutation.error} message={submitError.message} /> : null}
          <div className="space-y-2"><Label htmlFor="media-storage-key">Storage key *</Label><Input id="media-storage-key" value={storageKey} required maxLength={1024} onChange={(event) => setStorageKey(event.target.value)} /></div>
          <div className="grid gap-5 sm:grid-cols-2">
            <div className="space-y-2"><Label htmlFor="media-type">Loại *</Label><NativeSelect id="media-type" value={mediaType} onChange={(event) => setMediaType(event.target.value as MediaType)}>{(Object.keys(MEDIA_TYPE_LABELS) as MediaType[]).map((type) => <option key={type} value={type}>{MEDIA_TYPE_LABELS[type].text}</option>)}</NativeSelect></div>
            <div className="space-y-2"><Label htmlFor="media-sku">Phạm vi</Label><NativeSelect id="media-sku" value={skuId} onChange={(event) => setSkuId(event.target.value)}><option value="">Cấp sản phẩm</option>{versioned.data.skus.map((sku) => <option key={sku.sku_id} value={sku.sku_id}>SKU {sku.sku_code}</option>)}</NativeSelect></div>
          </div>
          <div className="space-y-2"><Label htmlFor="media-alt">Mô tả thay thế</Label><Input id="media-alt" value={altText} maxLength={255} onChange={(event) => setAltText(event.target.value)} /><p className="text-caption text-muted-foreground">Mô tả nội dung ảnh cho người dùng trình đọc màn hình.</p></div>
          <div className="space-y-2"><Label htmlFor="media-order">Thứ tự *</Label><Input id="media-order" type="number" min={0} step={1} value={sortOrder} required onChange={(event) => setSortOrder(Math.max(0, Number.parseInt(event.target.value || "0", 10)))} /></div>
          <DialogFooter><Button type="button" variant="secondary" disabled={mutation.isPending} onClick={() => onOpenChange(false)}>Huỷ</Button><Button type="submit" disabled={mutation.isPending || !storageKey.trim() || !versioned.etag}>{mutation.isPending ? <LoaderCircle className="animate-spin" aria-hidden /> : null}{mutation.isPending ? "Đang lưu" : "Lưu media"}</Button></DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

function ProductLifecycle({
  versioned,
  onSaved,
  onReload,
}: {
  versioned: Versioned<Product>;
  onSaved: (product: Versioned<Product>) => void;
  onReload: () => Promise<void>;
}) {
  const { addToast } = useToast();
  const submitError = useSubmitError();
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [stale, setStale] = useState(false);
  const target = versioned.data.status === "ACTIVE" ? "HIDDEN" : "ACTIVE";
  const mutation = useMutation({
    mutationFn: () => {
      if (!versioned.etag) throw new Error("MISSING_ETAG");
      return productApi.changeProductStatus(versioned.data.product_id, versioned.etag, { status: target });
    },
    onSuccess: (product) => { onSaved(product); addToast({ type: "success", title: target === "ACTIVE" ? "Sản phẩm đang bán" : "Sản phẩm đã được ẩn" }); },
  });

  return (
    <FormSection title="Vòng đời sản phẩm" description="Trạng thái Product không phụ thuộc tồn kho. ACTIVE yêu cầu có ít nhất một SKU ACTIVE.">
      {stale ? <StaleAlert onReload={onReload} /> : null}
      {submitError.message ? <ErrorState error={mutation.error} message={submitError.message} /> : null}
      <div className="flex flex-col gap-5 rounded-xl border border-border bg-surface-2 p-5 sm:flex-row sm:items-center sm:justify-between">
        <div className="space-y-2"><StatusBadge value={versioned.data.status} labels={PRODUCT_STATUS_LABELS} /><p className="max-w-prose-narrow text-[14px] text-muted-foreground">{target === "ACTIVE" ? "Sản phẩm sẽ xuất hiện trong catalog sau khi các projection đồng bộ." : "Sản phẩm sẽ bị ẩn khỏi catalog nhưng dữ liệu vẫn được giữ lại."}</p></div>
        <Button variant={target === "ACTIVE" ? "default" : "outline"} disabled={!versioned.etag || stale} onClick={() => setConfirmOpen(true)}>{target === "ACTIVE" ? <Eye aria-hidden /> : <EyeOff aria-hidden />}{target === "ACTIVE" ? "Đưa vào bán" : "Ẩn sản phẩm"}</Button>
      </div>
      <ConfirmDialog
        open={confirmOpen}
        onOpenChange={setConfirmOpen}
        title={target === "ACTIVE" ? "Đưa sản phẩm vào bán?" : "Ẩn sản phẩm?"}
        description={target === "ACTIVE" ? "Product Service sẽ kiểm tra sản phẩm có ít nhất một SKU đang bán." : "Sản phẩm sẽ không còn xuất hiện cho người mua sau khi projection đồng bộ."}
        confirmLabel={target === "ACTIVE" ? "Đưa vào bán" : "Ẩn sản phẩm"}
        destructive={false}
        reasonRequired={false}
        onConfirm={async () => {
          try { await mutation.mutateAsync(); } catch (error) { setStale(isStaleVersion(error)); submitError.capture(error); throw error; }
        }}
      />
    </FormSection>
  );
}

function StaleAlert({ onReload }: { onReload: () => Promise<void> }) {
  return (
    <Alert variant="warning" title="Dữ liệu vừa được người khác thay đổi">
      Tải lại bản mới trước khi tiếp tục; hệ thống sẽ không tự ghi đè thay đổi của người khác.
      <div className="mt-3"><Button type="button" variant="outline" size="sm" onClick={() => void onReload()}>Tải lại dữ liệu</Button></div>
    </Alert>
  );
}

function ProductDetailSkeleton() {
  return <div className="space-y-8" aria-busy="true"><div className="space-y-3"><Skeleton className="h-10 w-72" /><Skeleton className="h-4 w-64" /></div><Skeleton className="h-11 w-full" /><Skeleton className="h-[520px] w-full rounded-xl" /></div>;
}
