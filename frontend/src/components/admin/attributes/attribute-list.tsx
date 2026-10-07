"use client";

import { useMemo, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type { ColumnDef } from "@tanstack/react-table";
import { ChevronRight, LoaderCircle, Plus, Tags } from "lucide-react";

import { DataTable } from "@/components/admin/data-table";
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
import { ErrorState } from "@/components/ui/error-state";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { NativeSelect } from "@/components/ui/native-select";
import { useToast } from "@/components/ui/toast";
import { useSubmitError } from "@/hooks/use-submit-error";
import {
  productApi,
  type Attribute,
  type AttributeValueType,
} from "@/lib/api/product";
import { ATTRIBUTE_VALUE_TYPE_LABELS } from "@/lib/labels/product";

const VALUE_TYPES: AttributeValueType[] = ["TEXT", "NUMBER", "BOOLEAN", "SELECT"];

export function AttributeList() {
  const router = useRouter();
  const queryClient = useQueryClient();
  const { addToast } = useToast();
  const submitError = useSubmitError();
  const [open, setOpen] = useState(false);
  const [code, setCode] = useState("");
  const [name, setName] = useState("");
  const [valueType, setValueType] = useState<AttributeValueType>("TEXT");

  const attributesQuery = useQuery({
    queryKey: ["product", "attributes"],
    queryFn: ({ signal }) => productApi.listAttributes(signal),
  });
  const create = useMutation({
    mutationFn: productApi.createAttribute,
    onSuccess: async (attribute) => {
      await queryClient.invalidateQueries({ queryKey: ["product", "attributes"] });
      addToast({ type: "success", title: "Đã tạo thuộc tính" });
      setOpen(false);
      router.push(`/admin/attributes/${attribute.attribute_id}`);
    },
  });

  const columns = useMemo<ColumnDef<Attribute, unknown>[]>(
    () => [
      {
        accessorKey: "name",
        header: "Thuộc tính",
        cell: ({ row }) => (
          <div className="space-y-1">
            <Link className="focus-ring rounded-sm font-medium hover:underline" href={`/admin/attributes/${row.original.attribute_id}`}>
              {row.original.name}
            </Link>
            <p className="font-mono text-caption text-muted-foreground">{row.original.code}</p>
          </div>
        ),
      },
      {
        accessorKey: "value_type",
        header: "Kiểu dữ liệu",
        cell: ({ row }) => <StatusBadge value={row.original.value_type} labels={ATTRIBUTE_VALUE_TYPE_LABELS} />,
      },
      {
        accessorKey: "options",
        header: "Lựa chọn",
        cell: ({ row }) => <span className="tabular-nums">{row.original.options.length}</span>,
      },
      {
        id: "actions",
        header: "",
        cell: ({ row }) => (
          <Button variant="ghost" size="icon-sm" aria-label={`Mở thuộc tính ${row.original.name}`} render={<Link href={`/admin/attributes/${row.original.attribute_id}`} />}>
            <ChevronRight aria-hidden />
          </Button>
        ),
      },
    ],
    []
  );

  function resetDialog() {
    setOpen(false);
    setCode("");
    setName("");
    setValueType("TEXT");
    submitError.reset();
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    submitError.reset();
    try {
      await create.mutateAsync({ code: code.trim(), name: name.trim(), value_type: valueType });
    } catch (error) {
      submitError.capture(error);
    }
  }

  const attributes = attributesQuery.data ?? [];
  return (
    <div className="space-y-8">
      <PageHeader
        title="Thuộc tính"
        description="Cấu hình kiểu dữ liệu và lựa chọn dùng chung cho các danh mục trong catalog."
        action={
          <Button onClick={() => setOpen(true)}>
            <Plus aria-hidden />
            Tạo thuộc tính
          </Button>
        }
      />

      <DataTable
        columns={columns}
        data={attributes}
        getRowId={(attribute) => attribute.attribute_id}
        caption="Danh sách thuộc tính catalog"
        isLoading={attributesQuery.isPending}
        error={attributesQuery.error}
        onRetry={() => void attributesQuery.refetch()}
        emptyTitle="Chưa có thuộc tính"
        emptyDescription="Tạo thuộc tính để mô tả sản phẩm hoặc làm trục biến thể cho SKU."
        emptyAction={<Button variant="outline" size="sm" onClick={() => setOpen(true)}><Tags aria-hidden />Tạo thuộc tính</Button>}
        renderMobileCard={(attribute) => (
          <Link href={`/admin/attributes/${attribute.attribute_id}`} className="focus-ring block rounded-lg">
            <div className="flex items-start justify-between gap-4">
              <div className="min-w-0 space-y-1">
                <h2 className="truncate text-headline-6 font-serif font-normal">{attribute.name}</h2>
                <p className="truncate font-mono text-caption text-muted-foreground">{attribute.code}</p>
              </div>
              <StatusBadge value={attribute.value_type} labels={ATTRIBUTE_VALUE_TYPE_LABELS} />
            </div>
            <p className="mt-4 text-caption text-muted-foreground">{attribute.options.length} lựa chọn</p>
          </Link>
        )}
      />

      <Dialog open={open} onOpenChange={(next) => (next ? setOpen(true) : resetDialog())}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Tạo thuộc tính</DialogTitle>
            <DialogDescription>Kiểu dữ liệu chỉ nên đổi khi thuộc tính chưa được sử dụng.</DialogDescription>
          </DialogHeader>
          <form onSubmit={submit} className="mt-6 space-y-5">
            {submitError.message ? <ErrorState error={create.error} message={submitError.message} /> : null}
            <div className="space-y-2">
              <Label htmlFor="attribute-name">Tên thuộc tính *</Label>
              <Input id="attribute-name" value={name} required maxLength={120} autoFocus onChange={(event) => setName(event.target.value)} />
            </div>
            <div className="space-y-2">
              <Label htmlFor="attribute-code">Mã thuộc tính *</Label>
              <Input id="attribute-code" value={code} required maxLength={64} placeholder="color" onChange={(event) => setCode(event.target.value.trim().toLowerCase())} />
            </div>
            <div className="space-y-2">
              <Label htmlFor="attribute-type">Kiểu dữ liệu *</Label>
              <NativeSelect id="attribute-type" value={valueType} onChange={(event) => setValueType(event.target.value as AttributeValueType)}>
                {VALUE_TYPES.map((type) => <option key={type} value={type}>{ATTRIBUTE_VALUE_TYPE_LABELS[type].text}</option>)}
              </NativeSelect>
            </div>
            <DialogFooter>
              <Button type="button" variant="secondary" disabled={create.isPending} onClick={resetDialog}>Huỷ</Button>
              <Button type="submit" disabled={create.isPending || !name.trim() || !code.trim()}>
                {create.isPending ? <LoaderCircle className="animate-spin" aria-hidden /> : null}
                {create.isPending ? "Đang tạo" : "Tạo thuộc tính"}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </div>
  );
}
