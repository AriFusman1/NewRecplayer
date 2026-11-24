import { useEffect, useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import { createPortal } from 'react-dom';

const GAP_PX = 4;

interface Props {
  /** The element the popover opens under. */
  anchor: HTMLElement | null;
  onClose: () => void;
  children: ReactNode;
  className?: string;
}

/**
 * A small floating panel under a field (autocomplete list, calendar, option list). It is rendered
 * into <body>, so a scrolling table around the field can't clip it. It follows the field when the
 * page scrolls, and closes on Esc or a click outside both the field and the panel.
 */
export function Popover({ anchor, onClose, children, className }: Props) {
  const panel = useRef<HTMLDivElement>(null);
  const [rect, setRect] = useState<DOMRect | null>(() => anchor?.getBoundingClientRect() ?? null);

  useLayoutEffect(() => {
    if (!anchor) return;
    const place = () => setRect(anchor.getBoundingClientRect());
    place();
    window.addEventListener('scroll', place, true); // true: also scrolling inside the table
    window.addEventListener('resize', place);
    return () => {
      window.removeEventListener('scroll', place, true);
      window.removeEventListener('resize', place);
    };
  }, [anchor]);

  useEffect(() => {
    const outside = (event: MouseEvent) => {
      const target = event.target as Node;
      if (!panel.current?.contains(target) && !anchor?.contains(target)) onClose();
    };
    const escape = (event: KeyboardEvent) => { if (event.key === 'Escape') onClose(); };
    document.addEventListener('mousedown', outside);
    document.addEventListener('keydown', escape);
    return () => {
      document.removeEventListener('mousedown', outside);
      document.removeEventListener('keydown', escape);
    };
  }, [anchor, onClose]);

  if (!rect) return null;
  // open towards the middle of the screen, so a panel wider than its field stays visible
  const alignRight = rect.left + rect.width / 2 > window.innerWidth / 2;
  const style = {
    top: rect.bottom + GAP_PX,
    minWidth: rect.width,
    ...(alignRight ? { right: window.innerWidth - rect.right } : { left: rect.left }),
  };
  return createPortal(<div ref={panel} className={`popover ${className ?? ''}`} style={style}>{children}</div>, document.body);
}
