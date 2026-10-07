"use client";

import { useMemo, useState } from "react";
import Link from "next/link";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { FolderTree, LoaderCircle, Plus } from "lucide-react";

import { PageHeader } from "@/components/admin/page-header";
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
import { useToast } from "@/components/ui/toast";
import { useSubmitError } from "@/hooks/use-submit-error";
import { flattenCategoryTree, slugifyCatalogName } from "@/lib/catalog";
import { productApi } from "@/lib/api/product";

export function CategoryList() {
  const queryClient = useQueryClient();
  const { addToast } = useToast();
  const [open, setOpen] = useState(false);
  const [name, setName] = useState("");
  const [slug, setSlug] = useState("");
  const [parentId, setParentId] = useState("");
  const [slugEdited, setSlugEdited] = useState(false);
  const submitError = useSubmitError();

  const categoriesQuery = useQuery({
    queryKey: ["product", "categories"],
    queryFn: ({ signal }) => productApi.listCategories({}, signal),
  });
  const tree = useMemo(
    () => flattenCategoryTree(categoriesQuery.data ?? []),
    [categoriesQuery.data]
  );

  const create = useMutation({
    mutationFn: productApi.createCategory,
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["product", "categories"] });
      addToast({ type: "success", title: "Đã tạo danh mục" });
      closeDialog();
    },
  });

  function closeDialog() {
    setOpen(false);
    setName("");
    setSlug("");
    setParentId("");
    setSlugEdited(false);
    submitError.reset();
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    submitError.reset();
    try {
      await create.mutateAsync({
        name: name.trim(),
        slug: slug.trim(),
        ...(parentId ? { parent_id: parentId } : {}),
      });
    } catch (error) {
      submitError.capture(error);
    }
  }

  return (
    <div className="space-y-8">
      <PageHeader
        title="Danh mục"
        description="Tổ chức catalog theo cây. Sản phẩm chỉ được gán vào danh mục cấp cuối."
        action={
          <Button onClick={() => setOpen(true)}>
            <Plus aria-hidden />
            Tạo danh mục
          </Button>
        }
      />

      {categoriesQuery.isPending ? (
        <CategoryTreeSkeleton />
      ) : categoriesQuery.isError ? (
        <ErrorState error={categoriesQuery.error} onRetry={() => void categoriesQuery.refetch()} />
      ) : tree.length === 0 ? (
        <EmptyState
          icon={FolderTree}
          title="Chưa có danh mục"
          description="Tạo danh mục gốc đầu tiên để bắt đầu tổ chức sản phẩm."
          action={
            <Button variant="outline" size="sm" onClick={() => setOpen(true)}>
              Tạo danh mục
            </Button>
          }
        />
      ) : (
        <div className="overflow-hidden rounded-xl border border-border bg-surface-overlay">
          <ul className="divide-y divide-border" aria-label="Cây danh mục">
            {tree.map(({ category, depth, isLeaf }) => (
              <li key={category.category_id}>
                <Link
                  href={`/admin/categories/${category.category_id}`}
                  className="focus-ring flex min-h-14 items-center justify-between gap-4 px-4 py-3 transition-colors hover:bg-surface-2 sm:px-5"
                  style={{ paddingLeft: `${20 + Math.min(depth, 8) * 24}px` }}
                >
                  <span className="min-w-0">
                    <span className="block truncate font-medium">{category.name}</span>
                    <span className="block truncate font-mono text-caption text-muted-foreground">
                      {category.slug}
                    </span>
                  </span>
                  <span className="shrink-0 text-caption text-muted-foreground">
                    {isLeaf ? "Danh mục lá" : `${category.attributes.length} thuộc tính`}
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        </div>
      )}

      <Dialog open={open} onOpenChange={(next) => (next ? setOpen(true) : closeDialog())}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Tạo danh mục</DialogTitle>
            <DialogDescription>
              Có thể tạo ở cấp gốc hoặc chọn một danh mục cha hiện có.
            </DialogDescription>
          </DialogHeader>
          <form onSubmit={submit} className="mt-6 space-y-5">
            {submitError.message ? (
              <ErrorState error={create.error} message={submitError.message} />
            ) : null}
            <div className="space-y-2">
              <Label htmlFor="category-name">Tên danh mục *</Label>
              <Input
                id="category-name"
                value={name}
                required
                maxLength={160}
                autoFocus
                onChange={(event) => {
                  setName(event.target.value);
                  if (!slugEdited) setSlug(slugifyCatalogName(event.target.value));
                }}
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="category-slug">Slug *</Label>
              <Input
                id="category-slug"
                value={slug}
                required
                maxLength={160}
                pattern="[a-z0-9]+(?:-[a-z0-9]+)*"
                onChange={(event) => {
                  setSlugEdited(true);
                  setSlug(event.target.value.toLowerCase());
                }}
              />
              <p className="text-caption text-muted-foreground">Chữ thường, số và dấu gạch ngang.</p>
            </div>
            <div className="space-y-2">
              <Label htmlFor="category-parent">Danh mục cha</Label>
              <NativeSelect id="category-parent" value={parentId} onChange={(event) => setParentId(event.target.value)}>
                <option value="">Không có — danh mục gốc</option>
                {tree.map(({ category, depth }) => (
                  <option key={category.category_id} value={category.category_id}>
                    {`${"— ".repeat(depth)}${category.name}`}
                  </option>
                ))}
              </NativeSelect>
            </div>
            <DialogFooter>
              <Button type="button" variant="secondary" disabled={create.isPending} onClick={closeDialog}>
                Huỷ
              </Button>
              <Button type="submit" disabled={create.isPending || !name.trim() || !slug.trim()}>
                {create.isPending ? <LoaderCircle className="animate-spin" aria-hidden /> : null}
                {create.isPending ? "Đang tạo" : "Tạo danh mục"}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </div>
  );
}

function CategoryTreeSkeleton() {
  return (
    <div className="space-y-0 overflow-hidden rounded-xl border border-border bg-surface-overlay" aria-busy="true">
      {Array.from({ length: 6 }, (_, index) => (
        <div key={index} className="border-b border-border px-5 py-4 last:border-0">
          <Skeleton className="h-5 w-48" />
          <Skeleton className="mt-2 h-3 w-32" />
        </div>
      ))}
    </div>
  );
}
