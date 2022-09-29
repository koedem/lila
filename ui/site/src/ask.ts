import * as xhr from 'common/xhr';

// i have never been accused me of proper OOP

lichess.load.then(() => $('.ask-container').each((_, e: EleLoose) => new Ask(e.firstElementChild!)));

class Ask {
  el: Element;
  submitEl?: Element;
  feedbackEl?: HTMLInputElement;

  constructor(askEl: Element) {
    this.el = askEl;
    wireExclusiveChoices(this);
    wireRankedChoices(this);
    this.feedbackEl = wireFeedback(this);
    this.submitEl = wireSubmit(this);
  }
  ranking = (): string => Array.from($('.ranked-choice', this.el), e => e?.getAttribute('value')).join('-');

  setState = (state: 'clean' | 'dirty' | 'success') => {
    this.submitEl?.classList.remove('dirty', 'success');
    if (state != 'clean') this.submitEl?.classList.add(state);
  };
}

const rewire = (el: Element | null, frag: string): Ask | undefined => {
  while (el && !el.classList.contains('ask-container')) el = el.parentElement;
  if (el && frag) {
    el.innerHTML = frag;
    return new Ask(el.firstElementChild!);
  }
};

const failure = (reason: any): void => {
  console.log(`Ask XHR failed with: ${reason}`);
};

const wireExclusiveChoices = (ask: Ask): void => {
  $('.exclusive-choice', ask.el).on('click', function (e: Event) {
    const target = e.target as Element;
    const value = target.classList.contains('selected') ? '' : target?.getAttribute('value');
    xhr
      .text(`/ask/${ask.el.id}?picks=${value}`, { method: 'post' })
      .then((frag: string) => rewire(ask.el, frag), failure);
  });
};

const wireFeedback = (ask: Ask): HTMLInputElement | undefined => {
  const feedbackEl = $('.feedback', ask.el)
    .on('input', () => ask.setState(ask.feedbackEl?.value == initialFeedback ? 'clean' : 'dirty'))
    .on('keypress', (e: KeyboardEvent) => {
      if (
        e.key != 'Enter' ||
        e.shiftKey ||
        e.ctrlKey ||
        e.altKey ||
        e.metaKey ||
        !ask.submitEl?.classList.contains('dirty')
      )
        return;
      $('input', ask.submitEl).trigger('click');
      e.preventDefault();
    })
    .get(0) as HTMLInputElement;
  const initialFeedback = feedbackEl?.value;
  return feedbackEl;
};

const wireSubmit = (ask: Ask): Element | undefined => {
  const submitEl = $('.ask__submit', ask.el).get(0);
  $('input', submitEl).on('click', () => {
    const path = `/ask/feedback/${ask.el.id}`;
    xhr
      .text(path, {
        method: 'post',
        body: ask.feedbackEl?.value && xhr.form({ text: ask.feedbackEl?.value }),
      })
      .then((frag: string) => rewire(ask.el, frag)?.setState(ask.feedbackEl?.value ? 'success' : 'clean'), failure);
  });
  return submitEl;
};

const wireRankedChoices = (ask: Ask): void => {
  let initialOrder = ask.ranking();
  let d: DragContext;

  const vertical: boolean = $('.ask__choices', ask.el) // wire the container
    .on('dragover', (e: DragEvent) => updateCursor(d, e))
    .on('dragleave', (e: DragEvent) => updateCursor(d, e))
    .hasClass('vertical');

  const [cursorEl, breakEl] = createCursor(vertical);
  const updateCursor: (d: DragContext, e: DragEvent) => void = vertical ? updateCursorV : updateCursorH;

  $('.ranked-choice', ask.el) // wire each draggable
    .on('dragstart', (e: DragEvent) => {
      e.dataTransfer!.effectAllowed = 'move';
      e.dataTransfer!.setData('text/plain', '');
      const dragEl = e.target as Element;
      dragEl.classList.add('dragging');
      d = {
        dragEl: dragEl,
        parentEl: dragEl.parentElement!,
        box: dragEl.parentElement!.getBoundingClientRect(),
        cursorEl: cursorEl!,
        breakEl: breakEl,
        choices: Array.from($('.ranked-choice', ask.el), e => e!),
      };
    })
    .on('dragend', (e: DragEvent) => {
      e.preventDefault();
      d.dragEl.classList.remove('dragging');
      if (d.cursorEl.parentElement != d.parentEl) return;
      d.parentEl.insertBefore(d.dragEl, d.cursorEl);
      clearCursor(d);

      const newOrder = ask.ranking();
      if (newOrder == initialOrder) return;
      const path = `/ask/${ask.el.id}?picks=${newOrder}`;
      xhr.text(path, { method: 'post' }).then(() => {
        initialOrder = newOrder;
        updateBadges(ask);
      }, failure);
    });
};

type DragContext = {
  dragEl: Element;
  parentEl: Element;
  box: DOMRect;
  cursorEl: Element;
  breakEl: Element | null; // null if vertical
  choices: Array<Element>;
};

const createCursor = (vertical: boolean) => {
  if (vertical) return [document.createElement('hr'), null];

  const cursorEl = document.createElement('div');
  cursorEl.classList.add('cursor');
  const breakEl = document.createElement('div');
  breakEl.style.flexBasis = '100%';
  return [cursorEl, breakEl];
};

const clearCursor = (d: DragContext): void => {
  if (d.cursorEl.parentNode) d.parentEl.removeChild(d.cursorEl);
  if (d.breakEl?.parentNode) d.parentEl.removeChild(d.breakEl);
};

const updateCursorH = (d: DragContext, e: MouseEvent): void => {
  e.preventDefault();

  if (e.x <= d.box.left || e.x >= d.box.right || e.y <= d.box.top || e.y >= d.box.bottom) {
    clearCursor(d);
    return;
  }
  const rtl = document.dir == 'rtl';
  let target: { el: Element | null; break: 'beforebegin' | 'afterend' | null } | null = null;
  for (let i = 0, lastY = 0; i < d.choices.length && !target; i++) {
    const r = d.choices[i].getBoundingClientRect();
    const x = r.right - r.width / 2;
    const y = r.bottom + 4; // +4 because there's about 8 device px between rows
    const rowBreak = i > 0 && y != lastY;
    if (rowBreak && e.y <= lastY && (rtl ? e.x >= d.box.left : e.x <= d.box.right))
      target = { el: d.choices[i], break: 'afterend' };
    else if (e.y <= y && (rtl ? e.x >= x : e.x <= x))
      target = { el: d.choices[i], break: rowBreak ? 'beforebegin' : null };
    lastY = y;
  }
  if (!target) {
    d.parentEl.insertBefore(d.cursorEl, null);
    return;
  }
  d.parentEl.insertBefore(d.cursorEl, target.el);

  if (target.break) d.cursorEl.insertAdjacentElement(target.break, d.breakEl!);
  else if (d.breakEl!.parentNode) d.parentEl.removeChild(d.breakEl!);
};

const updateCursorV = (d: DragContext, e: DragEvent): void => {
  e.preventDefault();
  if (e.x <= d.box.left || e.x >= d.box.right || e.y <= d.box.top || e.y >= d.box.bottom) {
    clearCursor(d);
    return;
  }
  let target: Element | null = null;
  for (let i = 0; i < d.choices.length && !target; i++) {
    const r = d.choices[i].getBoundingClientRect();
    if (e.y < r.top + r.height / 2) target = d.choices[i];
  }
  d.parentEl.insertBefore(d.cursorEl, target);
};

const updateBadges = (ask: Ask): void => {
  $('.rank-badge', ask.el).each((index: number, el: HTMLElement) => {
    el.innerText = `${index + 1}`;
  });
};
