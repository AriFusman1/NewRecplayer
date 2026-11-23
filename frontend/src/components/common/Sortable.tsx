import {
  DndContext, KeyboardSensor, PointerSensor, closestCenter, useSensor, useSensors, type DragEndEvent,
} from '@dnd-kit/core';
import { SortableContext, sortableKeyboardCoordinates, useSortable, verticalListSortingStrategy } from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import type { CSSProperties, HTMLAttributes, ReactNode } from 'react';

/**
 * A vertical list reordered by dragging a handle (mouse, touch or keyboard), built on dnd-kit.
 * `onMove(from, to)` gets indices into `ids`; `to` is where the item ends up.
 */
export function SortableList({ ids, onMove, children }: { ids: string[]; onMove: (from: number, to: number) => void; children: ReactNode }) {
  const sensors = useSensors(
    // a small distance before dragging starts, so clicks on the handle still work
    useSensor(PointerSensor, { activationConstraint: { distance: 4 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  );
  const onDragEnd = ({ active, over }: DragEndEvent) => {
    if (!over || active.id === over.id) return;
    onMove(ids.indexOf(String(active.id)), ids.indexOf(String(over.id)));
  };
  return (
    <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={onDragEnd}>
      <SortableContext items={ids} strategy={verticalListSortingStrategy}>{children}</SortableContext>
    </DndContext>
  );
}

export interface SortableRowProps {
  /** Spread on the row's outer element. */
  rowProps: { ref: (element: HTMLElement | null) => void; style: CSSProperties };
  /** Spread on the drag handle. */
  handleProps: HTMLAttributes<HTMLElement>;
  dragging: boolean;
}

/** One item of a SortableList. `disabled` items can't be dragged or dropped on. */
export function SortableRow({ id, disabled, children }: { id: string; disabled?: boolean; children: (sortable: SortableRowProps) => ReactNode }) {
  const { setNodeRef, transform, transition, attributes, listeners, isDragging } = useSortable({ id, disabled });
  return (
    <>
      {children({
        rowProps: { ref: setNodeRef, style: { transform: CSS.Transform.toString(transform), transition } },
        handleProps: disabled ? {} : { ...attributes, ...listeners },
        dragging: isDragging,
      })}
    </>
  );
}
