"use client";

import { useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { LoaderCircle, Plus, Trash2 } from "lucide-react";

import { ConfirmDialog } from "@/components/admin/confirm-dialog";
import { FormSection } from "@/components/admin/form-section";
import { PageHeader } from "@/components/admin/page-header";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { ErrorState } from "@/components/ui/error-state";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { NativeSelect } from "@/components/ui/native-select";
import { Skeleton } from "@/components/ui/skeleton";
import { useToast } from "@/components/ui/toast";
import { useSubmitError } from "@/hooks/use-submit-error";
import { flattenCategoryTree } from "@/lib/catalog";
import { productApi, type CategoryAttribute } from "@/lib/api/product";
import { problemMessage } from "@/lib/api/problem";

export function CategoryDetail({ categoryId }: { categoryId: string }) {
  const router = useRouter();
  const queryClient = useQueryClient();
  const { addToast } = useToast();
  const submitError = useSubmitError();
  const [name, setName] = useState("");
  const [slug, setSlug] = useState("");
  const [parentId, setParentId] = useState("");
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [newAttributeId, setNewAttributeId] = useState("");
  const [newRequired, setNewRequired] = useState(false);
  const [newVariant, setNewVariant] = useState(false);
  const [formSource, setFormSource] = useState<unknown>(null);

  const categoryQuery = useQuery({
    queryKey: ["product", "category", categoryId],
    queryFn: ({ signal }) => productApi.getCategory(categoryId, signal),
  });
  const categoriesQuery = useQuery({
    queryKey: ["product", "categories"],
    queryFn: ({ signal }) => productApi.listCategories({}, signal),
  });
  const attributesQuery = useQuery({
    queryKey: ["product", "attributes"],
    queryFn: ({ signal }) => productApi.listAttributes(signal),
  });

  if (categoryQuery.data && categoryQuery.data !== formSource) {
    setFormSource(categoryQuery.data);
    setName(categoryQuery.data.name);
    setSlug(categoryQuery.data.slug);
    setParentId(categoryQuery.data.parent_id ?? "");
  }

  const tree = useMemo(
    () => flattenCategoryTree(categoriesQuery.data ?? []),
    [categoriesQuery.data]
  );
  const invalidParentIds = useMemo(() => {
    const children = new Map<string, string[]>();
    for (const category of categoriesQuery.data ?? []) {
      if (!category.parent_id) continue;
      children.set(category.parent_id, [...(children.get(category.parent_id) ?? []), category.category_id]);
    }
    const invalid = new Set<string>([categoryId]);
    const queue = [categoryId];
    while (queue.length) {
      const current = queue.shift();
      if (!current) continue;
      for (const child of children.get(current) ?? []) {
        if (invalid.has(child)) continue;
        invalid.add(child);
        queue.push(child);
      }
    }
    return invalid;
  }, [categoriesQuery.data, categoryId]);

  const replace = useMutation({
    mutationFn: () =>
      productApi.replaceCategory(categoryId, {
        name: name.trim(),
        slug: slug.trim(),
        parent_id: parentId || null,
      }),
    onSuccess: async (category) => {
      queryClient.setQueryData(["product", "category", categoryId], category);
      await queryClient.invalidateQueries({ queryKey: ["product", "categories"] });
      addToast({ type: "success", title: "Đã lưu danh mục" });
    },
  });
  const remove = useMutation({
    mutationFn: () => productApi.deleteCategory(categoryId),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["product", "categories"] });
      addToast({ type: "success", title: "Đã xoá danh mục" });
      router.replace("/admin/categories");
    },
  });
  const putMapping = useMutation({
    mutationFn: ({ attributeId, input }: { attributeId: string; input: { is_required: boolean; is_variant: boolean } }) =>
      productApi.putCategoryAttribute(categoryId, attributeId, input),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["product", "category", categoryId] });
      await queryClient.invalidateQueries({ queryKey: ["product", "categories"] });
      addToast({ type: "success", title: "Đã cập nhật thuộc tính danh mục" });
    },
  });
  const removeMapping = useMutation({
    mutationFn: (attributeId: string) => productApi.deleteCategoryAttribute(categoryId, attributeId),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["product", "category", categoryId] });
      await queryClient.invalidateQueries({ queryKey: ["product", "categories"] });
      addToast({ type: "success", title: "Đã bỏ thuộc tính khỏi danh mục" });
    },
  });

  async function save(event: React.FormEvent) {
    event.preventDefault();
    submitError.reset();
    try {
      await replace.mutateAsync();
    } catch (error) {
      submitError.capture(error);
    }
  }

  async function updateMapping(mapping: CategoryAttribute, field: "is_required" | "is_variant", value: boolean) {
    try {
      await putMapping.mutateAsync({
        attributeId: mapping.attribute_id,
        input: {
          is_required: field === "is_required" ? value : mapping.is_required,
          is_variant: field === "is_variant" ? value : mapping.is_variant,
        },
      });
    } catch (error) {
      submitError.capture(error);
      addToast({ type: "error", title: "Không cập nhật được thuộc tính", message: problemMessage(error) });
    }
  }

  async function addMapping() {
    if (!newAttributeId) return;
    try {
      await putMapping.mutateAsync({
        attributeId: newAttributeId,
        input: { is_required: newRequired, is_variant: newVariant },
      });
      setNewAttributeId("");
      setNewRequired(false);
      setNewVariant(false);
    } catch (error) {
      submitError.capture(error);
    }
  }

  if (categoryQuery.isPending) return <CategoryDetailSkeleton />;
  if (categoryQuery.isError || !categoryQuery.data) {
    return <ErrorState error={categoryQuery.error} onRetry={() => void categoryQuery.refetch()} />;
  }

  const category = categoryQuery.data;
  const assigned = new Set(category.attributes.map((mapping) => mapping.attribute_id));
  const availableAttributes = (attributesQuery.data ?? []).filter((attribute) => !assigned.has(attribute.attribute_id));
  const dirty = name !== category.name || slug !== category.slug || parentId !== (category.parent_id ?? "");

  return (
    <div className="space-y-8">
      <PageHeader
        title={category.name}
        description={`Danh mục · revision ${category.category_revision}`}
        action={
          <Button variant="destructive" onClick={() => setDeleteOpen(true)}>
            <Trash2 aria-hidden />
            Xoá danh mục
          </Button>
        }
      />

      <form onSubmit={save} className="space-y-5">
        <FormSection title="Thông tin danh mục" description="Đổi parent sẽ yêu cầu Search reindex lại đường dẫn catalog.">
          {submitError.message ? <ErrorState error={replace.error} message={submitError.message} /> : null}
          {categoriesQuery.isError ? (
            <ErrorState
              error={categoriesQuery.error}
              onRetry={() => void categoriesQuery.refetch()}
              message="Không tải được cây danh mục. Bạn vẫn có thể sửa tên và slug."
            />
          ) : null}
          <div className="grid gap-5 sm:grid-cols-2">
            <div className="space-y-2">
              <Label htmlFor="category-name">Tên danh mục *</Label>
              <Input id="category-name" value={name} required maxLength={160} onChange={(event) => setName(event.target.value)} />
            </div>
            <div className="space-y-2">
              <Label htmlFor="category-slug">Slug *</Label>
              <Input
                id="category-slug"
                value={slug}
                required
                maxLength={160}
                pattern="[a-z0-9]+(?:-[a-z0-9]+)*"
                onChange={(event) => setSlug(event.target.value.toLowerCase())}
              />
            </div>
          </div>
          <div className="space-y-2">
            <Label htmlFor="category-parent">Danh mục cha</Label>
            <NativeSelect
              id="category-parent"
              value={parentId}
              disabled={categoriesQuery.isPending || categoriesQuery.isError}
              onChange={(event) => setParentId(event.target.value)}
            >
              <option value="">Không có — danh mục gốc</option>
              {tree.map(({ category: option, depth }) => (
                <option key={option.category_id} value={option.category_id} disabled={invalidParentIds.has(option.category_id)}>
                  {`${"— ".repeat(depth)}${option.name}`}
                </option>
              ))}
            </NativeSelect>
          </div>
          <div className="flex justify-end gap-3 border-t border-border pt-5">
            <Button
              type="button"
              variant="secondary"
              disabled={!dirty || replace.isPending}
              onClick={() => {
                setName(category.name);
                setSlug(category.slug);
                setParentId(category.parent_id ?? "");
              }}
            >
              Hoàn tác
            </Button>
            <Button type="submit" disabled={!dirty || replace.isPending}>
              {replace.isPending ? <LoaderCircle className="animate-spin" aria-hidden /> : null}
              {replace.isPending ? "Đang lưu" : "Lưu thay đổi"}
            </Button>
          </div>
        </FormSection>
      </form>

      <FormSection
        title="Thuộc tính của danh mục"
        description="Thuộc tính thường nằm ở Product; thuộc tính biến thể nằm ở từng SKU."
      >
        {attributesQuery.isError ? (
          <ErrorState error={attributesQuery.error} onRetry={() => void attributesQuery.refetch()} />
        ) : null}
        {category.attributes.length === 0 ? (
          <p className="text-[14px] text-muted-foreground">Danh mục chưa có thuộc tính nào.</p>
        ) : (
          <div className="divide-y divide-border rounded-xl border border-border">
            {category.attributes.map((mapping) => (
              <div key={mapping.attribute_id} className="grid gap-4 p-4 sm:grid-cols-[1fr_auto_auto_auto] sm:items-center">
                <div>
                  <p className="font-medium">{mapping.attribute_name}</p>
                  <p className="font-mono text-caption text-muted-foreground">{mapping.attribute_code} · {mapping.value_type}</p>
                </div>
                <label className="flex min-h-11 items-center gap-3 text-[14px]">
                  <Checkbox
                    checked={mapping.is_required}
                    disabled={putMapping.isPending || removeMapping.isPending}
                    onCheckedChange={(checked) => void updateMapping(mapping, "is_required", checked === true)}
                  />
                  Bắt buộc
                </label>
                <label className="flex min-h-11 items-center gap-3 text-[14px]">
                  <Checkbox
                    checked={mapping.is_variant}
                    disabled={putMapping.isPending || removeMapping.isPending}
                    onCheckedChange={(checked) => void updateMapping(mapping, "is_variant", checked === true)}
                  />
                  Trục biến thể
                </label>
                <Button
                  type="button"
                  variant="ghost"
                  size="icon"
                  aria-label={`Bỏ thuộc tính ${mapping.attribute_name}`}
                  disabled={removeMapping.isPending}
                  onClick={() => void removeMapping.mutateAsync(mapping.attribute_id).catch((error) => submitError.capture(error))}
                >
                  <Trash2 aria-hidden />
                </Button>
              </div>
            ))}
          </div>
        )}

        <div className="grid gap-4 rounded-xl border border-border bg-surface-2 p-4 sm:grid-cols-[1fr_auto_auto_auto] sm:items-end">
          <div className="space-y-2">
            <Label htmlFor="new-category-attribute">Thêm thuộc tính</Label>
            <NativeSelect id="new-category-attribute" value={newAttributeId} disabled={attributesQuery.isPending} onChange={(event) => setNewAttributeId(event.target.value)}>
              <option value="">Chọn thuộc tính</option>
              {availableAttributes.map((attribute) => (
                <option key={attribute.attribute_id} value={attribute.attribute_id}>{attribute.name} ({attribute.code})</option>
              ))}
            </NativeSelect>
          </div>
          <label className="flex min-h-11 items-center gap-3 text-[14px]">
            <Checkbox checked={newRequired} onCheckedChange={(checked) => setNewRequired(checked === true)} />
            Bắt buộc
          </label>
          <label className="flex min-h-11 items-center gap-3 text-[14px]">
            <Checkbox checked={newVariant} onCheckedChange={(checked) => setNewVariant(checked === true)} />
            Trục biến thể
          </label>
          <Button type="button" variant="outline" disabled={!newAttributeId || putMapping.isPending} onClick={() => void addMapping()}>
            {putMapping.isPending ? <LoaderCircle className="animate-spin" aria-hidden /> : <Plus aria-hidden />}
            Thêm
          </Button>
        </div>
      </FormSection>

      <ConfirmDialog
        open={deleteOpen}
        onOpenChange={setDeleteOpen}
        title="Xoá vĩnh viễn danh mục?"
        description="Chỉ xoá được danh mục không còn danh mục con hoặc sản phẩm. Hành động này không thể hoàn tác."
        confirmLabel="Xoá danh mục"
        expectedText={category.name}
        onConfirm={async () => remove.mutateAsync()}
      />
    </div>
  );
}

function CategoryDetailSkeleton() {
  return (
    <div className="space-y-8" aria-busy="true">
      <div className="space-y-3"><Skeleton className="h-10 w-64" /><Skeleton className="h-4 w-48" /></div>
      <Skeleton className="h-80 w-full rounded-xl" />
      <Skeleton className="h-96 w-full rounded-xl" />
    </div>
  );
}
