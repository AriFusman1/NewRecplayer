import { useState, type DragEvent } from 'react';

/**
 * Native HTML5 drag-and-drop reordering. Spread `rowProps(index)` on each row;
 * `onMove(from, to)` receives the target index in the list without the dragged row.
 */
export function useDragReorder(onMove: (from: number, to: number) => void, canDrag: (index: number) => boolean = () => true) {
  const [dragging, setDragging] = useState<number | null>(null);
  const [over, setOver] = useState<number | null>(null);

  const rowProps = (index: number) => ({
    draggable: canDrag(index),
    onDragStart: (e: DragEvent) => {
      setDragging(index);
      e.dataTransfer.effectAllowed = 'move';
      e.dataTransfer.setData('text/plain', String(index));
    },
    onDragOver: (e: DragEvent) => {
      if (dragging === null || !canDrag(index)) return;
      e.preventDefault();
      setOver(index);
    },
    onDrop: (e: DragEvent) => {
      e.preventDefault();
      if (dragging !== null && over !== null && dragging !== over) onMove(dragging, over);
      setDragging(null);
      setOver(null);
    },
    onDragEnd: () => {
      setDragging(null);
      setOver(null);
    },
    className: [dragging === index ? 'dragging' : '', over === index && dragging !== index ? 'drop-before' : ''].join(' '),
  });

  return { rowProps };
}
