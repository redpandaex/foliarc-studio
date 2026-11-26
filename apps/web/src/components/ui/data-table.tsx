import type { Key, ReactNode } from "react";
import { cn } from "cn";

export interface DataTableColumn<T> {
  id: string;
  header: string;
  cell: (row: T) => ReactNode;
  align?: "start" | "end";
  className?: string;
}

export interface DataTableProps<T> {
  caption: string;
  columns: readonly DataTableColumn<T>[];
  rows: readonly T[];
  rowKey: (row: T) => Key;
  empty: ReactNode;
  loading?: boolean;
  className?: string;
}

export function DataTable<T>({
  caption,
  columns,
  rows,
  rowKey,
  empty,
  loading = false,
  className,
}: DataTableProps<T>) {
  if (loading) {
    return (
      <div
        data-slot="data-table"
        className="py-8 text-center text-xs text-muted-foreground"
      >
        加载中...
      </div>
    );
  }

  if (rows.length === 0) {
    return <div data-slot="data-table">{empty}</div>;
  }

  return (
    <div data-slot="data-table" className={cn("overflow-x-auto", className)}>
      <table className="w-full text-left text-xs">
        <caption className="sr-only">{caption}</caption>
        <thead className="border-b border-border text-[11px] text-muted-foreground">
          <tr>
            {columns.map((col) => (
              <th
                key={col.id}
                scope="col"
                className={cn(
                  "py-2 pr-3 font-medium last:pr-0",
                  col.align === "end" && "text-right",
                  col.className,
                )}
              >
                {col.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-border">
          {rows.map((row) => (
            <tr
              key={rowKey(row)}
              className="transition-colors hover:bg-muted/40"
            >
              {columns.map((col) => (
                <td
                  key={col.id}
                  className={cn(
                    "py-2.5 pr-3 align-middle last:pr-0",
                    col.align === "end" && "text-right tabular-nums",
                    col.className,
                  )}
                >
                  {col.cell(row)}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
