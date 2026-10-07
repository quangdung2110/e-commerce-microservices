"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { LoaderCircle, Pencil, Plus, Trash2 } from "lucide-react";

import { ConfirmDialog } from "@/components/admin/confirm-dialog";
import { FormSection } from "@/components/admin/form-section";
import { PageHeader } from "@/components/admin/page-header";
import { StatusBadge } from "@/components/admin/status-badge";
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
import {
  productApi,
  type AttributeOption,
  type AttributeValueType,
} from "@/lib/api/product";
import { ATTRIBUTE_VALUE_TYPE_LABELS } from "@/lib/labels/product";

const VALUE_TYPES: AttributeValueType[] = ["TEXT", "NUMBER", "BOOLEAN", "SELECT"];

export function AttributeDetail({ attributeId }: { attributeId: string }) {
  const router = useRouter();
  const queryClient = useQueryClient();
  const { addToast } = useToast();
  const submitError = useSubmitError();
  const [code, setCode] = useState("");
  const [name, setName] = useState("");
  const [valueType, setValueType] = useState<AttributeValueType>("TEXT");
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [optionOpen, setOptionOpen] = useState(false);
  const [editingOption, setEditingOption] = useState<AttributeOption | null>(null);
  const [deleteOption, setDeleteOption] = useState<AttributeOption | null>(null);
  const [formSource, setFormSource] = useState<unknown>(null);

  const attributeQuery = useQuery({
    queryKey: ["product", "attribute", attributeId],
    queryFn: ({ signal }) => productApi.getAttribute(attributeId, signal),
  });
  if (attributeQuery.data && attributeQuery.data !== formSource) {
    setFormSource(attributeQuery.data);
    setCode(attributeQuery.data.code);
    setName(attributeQuery.data.name);
    setValueType(attributeQuery.data.value_type);
  }

  const replace = useMutation({
    mutationFn: () => productApi.replaceAttribute(attributeId, { code: code.trim(), name: name.trim(), value_type: valueType }),
    onSuccess: async (attribute) => {
      queryClient.setQueryData(["product", "attribute", attributeId], attribute);
      await queryClient.invalidateQueries({ queryKey: ["product", "attributes"] });
      addToast({ type: "success", title: "Đã lưu thuộc tính" });
    },
  });
  const remove = useMutation({
    mutationFn: () => productApi.deleteAttribute(attributeId),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["product", "attributes"] });
      addToast({ type: "success", title: "Đã xoá thuộc tính" });
      router.replace("/admin/attributes");
    },
  });
  const removeOption = useMutation({
    mutationFn: (optionId: string) => productApi.deleteAttributeOption(attributeId, optionId),
    onSuccess: async () => {
      await refreshAttribute();
      addToast({ type: "success", title: "Đã xoá lựa chọn" });
      setDeleteOption(null);
    },
  });

  async function refreshAttribute() {
    await queryClient.invalidateQueries({ queryKey: ["product", "attribute", attributeId] });
    await queryClient.invalidateQueries({ queryKey: ["product", "attributes"] });
  }

  async function save(event: React.FormEvent) {
    event.preventDefault();
    submitError.reset();
    try {
      await replace.mutateAsync();
    } catch (error) {
      submitError.capture(error);
    }
  }

  if (attributeQuery.isPending) return <AttributeDetailSkeleton />;
  if (attributeQuery.isError || !attributeQuery.data) {
    return <ErrorState error={attributeQuery.error} onRetry={() => void attributeQuery.refetch()} />;
  }

  const attribute = attributeQuery.data;
  const dirty = code !== attribute.code || name !== attribute.name || valueType !== attribute.value_type;
  const sortedOptions = attribute.options.slice().sort((left, right) => left.sort_order - right.sort_order || left.code.localeCompare(right.code));

  return (
    <div className="space-y-8">
      <PageHeader
        title={attribute.name}
        description={`Thuộc tính ${attribute.code}`}
        action={<Button variant="destructive" onClick={() => setDeleteOpen(true)}><Trash2 aria-hidden />Xoá thuộc tính</Button>}
      />

      <form onSubmit={save}>
        <FormSection title="Cấu hình thuộc tính" description="Đổi kiểu dữ liệu có thể bị từ chối nếu thuộc tính đã được dùng.">
          {submitError.message ? <ErrorState error={replace.error} message={submitError.message} /> : null}
          <div className="grid gap-5 sm:grid-cols-2">
            <div className="space-y-2">
              <Label htmlFor="attribute-name">Tên thuộc tính *</Label>
              <Input id="attribute-name" value={name} required maxLength={120} onChange={(event) => setName(event.target.value)} />
            </div>
            <div className="space-y-2">
              <Label htmlFor="attribute-code">Mã thuộc tính *</Label>
              <Input id="attribute-code" value={code} required maxLength={64} onChange={(event) => setCode(event.target.value.trim().toLowerCase())} />
            </div>
          </div>
          <div className="space-y-2">
            <Label htmlFor="attribute-type">Kiểu dữ liệu *</Label>
            <NativeSelect id="attribute-type" value={valueType} onChange={(event) => setValueType(event.target.value as AttributeValueType)}>
              {VALUE_TYPES.map((type) => <option key={type} value={type}>{ATTRIBUTE_VALUE_TYPE_LABELS[type].text}</option>)}
            </NativeSelect>
          </div>
          <div className="flex justify-end gap-3 border-t border-border pt-5">
            <Button type="button" variant="secondary" disabled={!dirty || replace.isPending} onClick={() => { setName(attribute.name); setCode(attribute.code); setValueType(attribute.value_type); }}>Hoàn tác</Button>
            <Button type="submit" disabled={!dirty || replace.isPending}>
              {replace.isPending ? <LoaderCircle className="animate-spin" aria-hidden /> : null}
              {replace.isPending ? "Đang lưu" : "Lưu thay đổi"}
            </Button>
          </div>
        </FormSection>
      </form>

      {attribute.value_type === "SELECT" ? (
        <FormSection title="Lựa chọn" description="Product và SKU lưu mã lựa chọn; nhãn dùng để hiển thị cho người quản trị và người mua.">
          <div className="flex justify-end">
            <Button variant="outline" onClick={() => { setEditingOption(null); setOptionOpen(true); }}><Plus aria-hidden />Thêm lựa chọn</Button>
          </div>
          {sortedOptions.length === 0 ? (
            <EmptyState icon={Plus} title="Chưa có lựa chọn" description="Thêm ít nhất một lựa chọn trước khi dùng thuộc tính SELECT cho sản phẩm." action={<Button variant="outline" size="sm" onClick={() => setOptionOpen(true)}>Thêm lựa chọn</Button>} />
          ) : (
            <div className="divide-y divide-border rounded-xl border border-border">
              {sortedOptions.map((option) => (
                <div key={option.option_id} className="grid gap-3 p-4 sm:grid-cols-[1fr_auto_auto] sm:items-center">
                  <div>
                    <p className="font-medium">{option.label}</p>
                    <p className="font-mono text-caption text-muted-foreground">{option.code} · thứ tự {option.sort_order}</p>
                  </div>
                  <Button variant="ghost" size="icon" aria-label={`Sửa lựa chọn ${option.label}`} onClick={() => { setEditingOption(option); setOptionOpen(true); }}><Pencil aria-hidden /></Button>
                  <Button variant="ghost" size="icon" aria-label={`Xoá lựa chọn ${option.label}`} onClick={() => setDeleteOption(option)}><Trash2 aria-hidden /></Button>
                </div>
              ))}
            </div>
          )}
        </FormSection>
      ) : (
        <FormSection title="Lựa chọn">
          <div className="flex items-center gap-3">
            <StatusBadge value={attribute.value_type} labels={ATTRIBUTE_VALUE_TYPE_LABELS} />
            <p className="text-[14px] text-muted-foreground">Chỉ thuộc tính kiểu Danh sách chọn mới có lựa chọn cấu hình sẵn.</p>
          </div>
        </FormSection>
      )}

      {optionOpen ? (
        <OptionDialog
          key={editingOption?.option_id ?? "new"}
          onOpenChange={setOptionOpen}
          attributeId={attributeId}
          option={editingOption}
          onSaved={refreshAttribute}
        />
      ) : null}
      <ConfirmDialog
        open={deleteOpen}
        onOpenChange={setDeleteOpen}
        title="Xoá vĩnh viễn thuộc tính?"
        description="Chỉ xoá được khi thuộc tính không còn được danh mục hoặc sản phẩm tham chiếu."
        confirmLabel="Xoá thuộc tính"
        expectedText={attribute.name}
        onConfirm={async () => remove.mutateAsync()}
      />
      <ConfirmDialog
        open={Boolean(deleteOption)}
        onOpenChange={(next) => { if (!next) setDeleteOption(null); }}
        title="Xoá lựa chọn?"
        description="Không thể xoá lựa chọn đang được sản phẩm hoặc SKU sử dụng."
        confirmLabel="Xoá lựa chọn"
        expectedText={deleteOption?.code}
        onConfirm={async () => { if (deleteOption) await removeOption.mutateAsync(deleteOption.option_id); }}
      />
    </div>
  );
}

function OptionDialog({
  onOpenChange,
  attributeId,
  option,
  onSaved,
}: {
  onOpenChange: (open: boolean) => void;
  attributeId: string;
  option: AttributeOption | null;
  onSaved: () => Promise<void>;
}) {
  const { addToast } = useToast();
  const submitError = useSubmitError();
  const [code, setCode] = useState(option?.code ?? "");
  const [label, setLabel] = useState(option?.label ?? "");
  const [sortOrder, setSortOrder] = useState(option?.sort_order ?? 0);

  const mutation = useMutation({
    mutationFn: () => option
      ? productApi.replaceAttributeOption(attributeId, option.option_id, { code: code.trim(), label: label.trim(), sort_order: sortOrder })
      : productApi.createAttributeOption(attributeId, { code: code.trim(), label: label.trim(), sort_order: sortOrder }),
    onSuccess: async () => {
      await onSaved();
      addToast({ type: "success", title: option ? "Đã lưu lựa chọn" : "Đã thêm lựa chọn" });
      onOpenChange(false);
    },
  });

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    submitError.reset();
    try { await mutation.mutateAsync(); } catch (error) { submitError.capture(error); }
  }

  return (
    <Dialog open onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{option ? "Sửa lựa chọn" : "Thêm lựa chọn"}</DialogTitle>
          <DialogDescription>Mã được lưu trong dữ liệu Product; nhãn có thể thay đổi để hiển thị.</DialogDescription>
        </DialogHeader>
        <form onSubmit={submit} className="mt-6 space-y-5">
          {submitError.message ? <ErrorState error={mutation.error} message={submitError.message} /> : null}
          <div className="space-y-2"><Label htmlFor="option-label">Nhãn *</Label><Input id="option-label" value={label} required maxLength={120} onChange={(event) => setLabel(event.target.value)} /></div>
          <div className="space-y-2"><Label htmlFor="option-code">Mã *</Label><Input id="option-code" value={code} required maxLength={64} onChange={(event) => setCode(event.target.value.trim().toLowerCase())} /></div>
          <div className="space-y-2"><Label htmlFor="option-order">Thứ tự *</Label><Input id="option-order" type="number" min={0} step={1} value={sortOrder} required onChange={(event) => setSortOrder(Math.max(0, Number.parseInt(event.target.value || "0", 10)))} /></div>
          <DialogFooter>
            <Button type="button" variant="secondary" disabled={mutation.isPending} onClick={() => onOpenChange(false)}>Huỷ</Button>
            <Button type="submit" disabled={mutation.isPending || !code.trim() || !label.trim()}>{mutation.isPending ? <LoaderCircle className="animate-spin" aria-hidden /> : null}{mutation.isPending ? "Đang lưu" : "Lưu lựa chọn"}</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

function AttributeDetailSkeleton() {
  return <div className="space-y-8" aria-busy="true"><div className="space-y-3"><Skeleton className="h-10 w-64" /><Skeleton className="h-4 w-40" /></div><Skeleton className="h-80 w-full rounded-xl" /><Skeleton className="h-72 w-full rounded-xl" /></div>;
}
